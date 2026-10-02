package forge.web;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;

import java.util.concurrent.atomic.LongAdder;

/** Counts every byte through the web port, with what is sent split by the kind its connection was last marked as carrying. */
@ChannelHandler.Sharable
final class ServerTraffic extends ChannelDuplexHandler {
    enum Kind { GAME, CARD_ART, AUDIO, PAGE }

    private static final AttributeKey<Kind> KIND = AttributeKey.valueOf("trafficKind");

    private final long started = System.currentTimeMillis();
    private final LongAdder received = new LongAdder();
    private final LongAdder[] sent = new LongAdder[Kind.values().length];

    ServerTraffic() {
        for (int i = 0; i < sent.length; i++) {
            sent[i] = new LongAdder();
        }
    }

    /** What the connection sends from here on, until it is told otherwise. */
    static void carrying(final Channel channel, final Kind kind) {
        channel.attr(KIND).set(kind);
    }

    long started() {
        return started;
    }

    long received() {
        return received.sum();
    }

    long sent(final Kind kind) {
        return sent[kind.ordinal()].sum();
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof ByteBuf buf) {
            received.add(buf.readableBytes());
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf buf) {
            final Kind kind = ctx.channel().attr(KIND).get();
            sent[(kind == null ? Kind.PAGE : kind).ordinal()].add(buf.readableBytes());
        }
        super.write(ctx, msg, promise);
    }
}
