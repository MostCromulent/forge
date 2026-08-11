package forge.gamemodes.net.server;

import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.ReplyPool;
import forge.trackable.TrackableObject;
import forge.trackable.Tracker;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.util.IHasForgeLog;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.concurrent.Future;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public final class RemoteClient implements IToClient, IHasForgeLog {

    /** Special value indicating the client hasn't been assigned a slot yet. */
    public static final int UNASSIGNED_SLOT = -1;

    private volatile Channel channel;
    private String username;
    private int index = UNASSIGNED_SLOT;
    private boolean libgdx;
    private volatile ReplyPool replies = new ReplyPool();
    private volatile Tracker codecTracker;
    private volatile Predicate<TrackableObject> codecReceiverKnows;
    private final AtomicInteger sendErrors = new AtomicInteger(0);
    // Volatile: set on the game thread, read by the write listeners on Netty threads.
    private volatile RemoteClientGuiGame gui;

    // Package-private: SaturationLoggingHandler reads/resets these on writability transitions
    volatile long saturationStartMs = 0L;
    final AtomicInteger sendsDuringSaturation = new AtomicInteger(0);

    private void recordSendIfSaturated(final Channel ch) {
        if (!ch.isWritable()) {
            sendsDuringSaturation.incrementAndGet();
        }
    }

    public RemoteClient(final Channel channel) {
        this.channel = channel;
    }

    /**
     * Swap the underlying channel for a reconnecting client.
     * Updates the channel, creates a fresh ReplyPool, and re-applies the codec
     * tracker to the new channel's pipeline so IdRef resolution keeps working.
     */
    public void swapChannel(final Channel newChannel) {
        this.channel = newChannel;
        this.replies = new ReplyPool();
        applyCodecTracker(newChannel);
    }

    /**
     * Check if this client has been assigned a valid lobby slot.
     * @return true if the client has a valid slot (index >= 0)
     */
    public boolean hasValidSlot() {
        return index >= 0;
    }

    /** Remote peer address, for admission limits and logging. */
    public SocketAddress getRemoteAddress() {
        final Channel ch = channel;
        return ch == null ? null : ch.remoteAddress();
    }

    /** Encodes synchronously on the caller's thread. Returns null on failure (logged). */
    private ByteBuf encodeOnCallingThread(final NetEvent event) {
        final Channel ch = channel;
        final CompatibleObjectEncoder encoder = ch.pipeline().get(CompatibleObjectEncoder.class);
        if (encoder == null) {
            deliveryFailed("encode", event, null, "no encoder in pipeline");
            return null;
        }
        try {
            return encoder.encodeToBuf(event, ch.alloc());
        } catch (Exception e) {
            deliveryFailed("encode", event, e, null);
            return null;
        }
    }

    /**
     * Report a message that did not reach the socket, and reseed this client's delta
     * baseline.
     *
     * <p>The baseline records what the server believes this client holds, and it advances
     * when a packet is built rather than when it lands. Every way a message can be lost
     * ends up here — a missing encoder, an encode that threw, a write that failed or was
     * cancelled — so this is the one place that can stop a silent, permanent divergence:
     * without the reseed the server would never send that state again.
     */
    private void deliveryFailed(final String stage, final NetEvent event,
                                final Throwable cause, final String detail) {
        sendErrors.incrementAndGet();
        if (cause != null) {
            netLog.error(cause, "Network {} error for {} (event: {})", stage, username, event);
        } else {
            netLog.error("Network {} error for {} (event: {}, cause: {})", stage, username, event, detail);
        }
        final RemoteClientGuiGame g = gui;
        if (g != null) {
            g.resetDeltaBaseline();
        }
    }

    private String failureDetail(final Future<?> f) {
        return f.isCancelled() ? "cancelled" : "no cause";
    }

    @Override
    public void send(final NetEvent event) {
        final Channel ch = channel;
        recordSendIfSaturated(ch);
        final ByteBuf encoded = encodeOnCallingThread(event);
        if (encoded == null) return;
        ch.writeAndFlush(encoded).addListener(f -> {
            if (!f.isSuccess()) {
                deliveryFailed("send", event, f.cause(), failureDetail(f));
            }
        });
    }

    @Override
    public void write(final NetEvent event) {
        final Channel ch = channel;
        recordSendIfSaturated(ch);
        final ByteBuf encoded = encodeOnCallingThread(event);
        if (encoded == null) return;
        ch.write(encoded).addListener(f -> {
            if (!f.isSuccess()) {
                deliveryFailed("write", event, f.cause(), failureDetail(f));
            }
        });
    }

    @Override
    public Object sendAndWait(final IdentifiableNetEvent event) {
        replies.initialize(event.getId());
        final Channel ch = channel;
        recordSendIfSaturated(ch);
        final ByteBuf encoded = encodeOnCallingThread(event);
        if (encoded == null) {
            replies.complete(event.getId(), null);
            return replies.get(event.getId());
        }
        ch.writeAndFlush(encoded).addListener(f -> {
            if (!f.isSuccess()) {
                deliveryFailed("sendAndWait", event, f.cause(), failureDetail(f));
                replies.complete(event.getId(), null);
            }
        });
        return replies.get(event.getId());
    }

    public String getUsername() {
        return username;
    }
    public void setUsername(final String username) {
        this.username = username;
    }

    public int getIndex() {
        return index;
    }
    public void setIndex(final int index) {
        this.index = index;
    }

    public boolean isLibgdx() {
        return libgdx;
    }
    public void setLibgdx(final boolean libgdx) {
        this.libgdx = libgdx;
    }

    public RemoteClientGuiGame getGui() {
        return gui;
    }
    void setGui(final RemoteClientGuiGame gui) {
        this.gui = gui;
    }

    /**
     * Set the tracker and the encoder's IdRef gate on the channel's codecs.
     * Called when the game starts (before any client protocol messages
     * arrive). Cached so that {@link #swapChannel} can re-apply after a
     * reconnect. The gate answers whether this client has already been told
     * about an object, so a reference to it can travel as an id.
     */
    public void setCodecTracker(Tracker tracker, Predicate<TrackableObject> receiverKnows) {
        this.codecTracker = tracker;
        this.codecReceiverKnows = receiverKnows;
        applyCodecTracker(channel);
    }

    private void applyCodecTracker(Channel ch) {
        if (codecTracker == null || ch == null) {
            return;
        }
        CompatibleObjectEncoder encoder = ch.pipeline().get(CompatibleObjectEncoder.class);
        if (encoder != null) {
            encoder.setTracker(codecTracker);
            encoder.setReceiverKnows(codecReceiverKnows);
        }
        CompatibleObjectDecoder decoder = ch.pipeline().get(CompatibleObjectDecoder.class);
        if (decoder != null) {
            decoder.setTracker(codecTracker);
        }
    }

    public int getSendErrorCount() {
        return sendErrors.get();
    }

    ReplyPool getReplyPool() {
        return replies;
    }
}
