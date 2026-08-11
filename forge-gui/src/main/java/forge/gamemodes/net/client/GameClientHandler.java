package forge.gamemodes.net.client;

import forge.game.*;
import forge.game.player.PlayerView;
import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.DeltaPacket;
import forge.gamemodes.net.GameProtocolHandler;
import forge.gui.GuiBase;
import forge.util.IHasForgeLog;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.ReplyPool;
import forge.gamemodes.net.event.LoginEvent;
import forge.gui.interfaces.IGuiGame;
import forge.util.BuildInfo;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.trackable.TrackableProperty;
import forge.trackable.TrackableTypes;
import forge.trackable.Tracker;
import io.netty.channel.ChannelHandlerContext;

import java.util.Iterator;
import java.util.Map;

final class GameClientHandler extends GameProtocolHandler<IGuiGame> implements IHasForgeLog {

    private final FGameClient client;
    private final IGuiGame gui;
    private Tracker tracker;

    public GameClientHandler(final FGameClient client) {
        super(true);
        this.client = client;
        this.gui = client.getGui();
        this.tracker = null;
    }

    @Override
    protected ReplyPool getReplyPool(final ChannelHandlerContext ctx) {
        return client.getReplyPool();
    }

    @Override
    protected IRemote getRemote(final ChannelHandlerContext ctx) {
        return client;
    }

    @Override
    protected IGuiGame getToInvoke(final ChannelHandlerContext ctx) {
        return gui;
    }

    @Override
    protected boolean shouldDispatchToGuiThread(final ProtocolMethod protocolMethod) {
        // Libgdx modal prompts block via WaitCallback and deadlock on the GL thread. Return-value
        // methods always block; message/showErrorDialog return void but still open a blocking modal.
        if (GuiBase.getInterface().isLibgdxPort()
                && (!protocolMethod.getReturnType().equals(Void.TYPE)
                    || protocolMethod == ProtocolMethod.message
                    || protocolMethod == ProtocolMethod.showErrorDialog)) {
            return false;
        }
        return super.shouldDispatchToGuiThread(protocolMethod);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void beforeCall(final ChannelHandlerContext ctx, final ProtocolMethod protocolMethod, final Object[] args) {
        switch (protocolMethod) {
            case applyDelta:
                ensureTracker(ctx);
                if (args.length > 0 && args[0] instanceof DeltaPacket packet) {
                    bootstrapGameView(packet);
                }
                break;
            case openView:
                ensureTracker(ctx);
                gui.setNetGame();
                // Carries ids only, and is null for a spectator, who controls nobody. Keep
                // the instance this client already holds where there is one, so the
                // controller attaches to the object the deltas populate; otherwise adopt the
                // carrier and register it, and the first delta fills it in place.
                final TrackableCollection<PlayerView> myPlayers =
                        (TrackableCollection<PlayerView>) args[0];
                final TrackableCollection<PlayerView> localPlayers = new TrackableCollection<>();
                if (myPlayers != null) {
                    for (PlayerView incoming : myPlayers) {
                        PlayerView known = this.tracker.getObj(TrackableTypes.PlayerViewType, incoming.getId());
                        if (known == null) {
                            incoming.setTracker(this.tracker);
                            this.tracker.putObj(TrackableTypes.PlayerViewType, incoming.getId(), incoming);
                            known = incoming;
                        }
                        localPlayers.add(known);
                    }
                }
                client.setGameControllers(localPlayers);
                break;
            default:
                break;
        }
        if (this.tracker != null) {
            updateTrackers(args);
        }
    }

    /**
     * Create the tracker and wire it into the codecs, unless that has already happened.
     *
     * <p>Whichever message arrives first has to do this, rather than one nominated message:
     * the tracker is what id references resolve against in both directions, so nothing can
     * be decoded before it exists.
     */
    private void ensureTracker(final ChannelHandlerContext ctx) {
        if (this.tracker != null) {
            return;
        }
        this.tracker = new Tracker();
        // Encoder uses the tracker to emit IdRef for client→server CardView args
        // (presence check only — stale detection is server-only).
        // Ephemerals absent from the tracker serialize as full objects in both directions.
        CompatibleObjectEncoder encoder = ctx.pipeline().get(CompatibleObjectEncoder.class);
        if (encoder != null) {
            encoder.setTracker(this.tracker);
        }
        CompatibleObjectDecoder decoder = ctx.pipeline().get(CompatibleObjectDecoder.class);
        if (decoder != null) {
            decoder.setTracker(this.tracker);
        }
    }

    /**
     * Build the GameView from the first delta.
     *
     * <p>{@code applyDelta} does nothing at all without one, and the client cannot derive it
     * the way the server does — that constructor needs a {@code Game}. The delta carries the
     * view's own properties under its own key, so the id is there to be read, and the
     * properties arrive through the ordinary apply once the instance exists.
     *
     * <p>A view left over from a previous connection is replaced rather than kept. Deltas are
     * applied into the tracker that view holds, while ids arriving on this connection resolve
     * against this handler's; a GUI that outlives a connection, as the mobile one does, would
     * otherwise fill one and be read from the other.
     */
    private void bootstrapGameView(final DeltaPacket packet) {
        final GameView existing = gui.getGameView();
        if (existing != null && existing.getTracker() == this.tracker) {
            return;
        }
        for (final Integer key : packet.getNewObjects().keySet()) {
            if (DeltaPacket.getTypeFromDeltaKey(key) == DeltaPacket.TYPE_GAME_VIEW) {
                final GameView view = new GameView(DeltaPacket.getIdFromDeltaKey(key), this.tracker);
                view.initGameLog();
                gui.setGameView(view);
                netLog.info("[DeltaSync] Built GameView id={} from delta seq={}",
                        view.getId(), packet.getSequenceNumber());
                return;
            }
        }
    }

    /**
     * This method is used to recursively update the <b>tracker</b>
     * references on all objects and their props.
     *
     * <p>Inline-serialized CardViews are intentionally NOT registered in the
     * tracker's id lookup: a tracker miss is the symmetric signal that a
     * CardView is ephemeral, mirroring the host's encoder check.
     *
     * @param objs
     */
    private void updateTrackers(final Object[] objs) {
        for (Object obj: objs) {
            if (obj instanceof TrackableObject trackableObject) {
                if (trackableObject.getTracker() == null) {
                    trackableObject.setTracker(this.tracker);
                    // walk the props
                    Map<TrackableProperty, Object> props = trackableObject.getPropsCopy();
                    if (props != null) {
                        for (Object propObj : props.values()) {
                            updateTrackers(new Object[]{propObj});
                        }
                    }
                }
            } else if (obj instanceof TrackableCollection collection) {
                Iterator itrCollection = collection.iterator();
                while (itrCollection.hasNext()) {
                    Object objCollection = itrCollection.next();
                    updateTrackers(new Object[]{objCollection});
                }
            }
        }
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        String loginName = client.getUsername();
        if (loginName == null || loginName.isEmpty()) {
            loginName = FModel.getPreferences().getPref(FPref.PLAYER_NAME);
        }
        // Don't use send() here, as this.channel is not yet set!
        ctx.channel().writeAndFlush(new LoginEvent(
                loginName,
                Integer.parseInt(FModel.getPreferences().getPref(FPref.UI_AVATARS).split(",")[0]),
                Integer.parseInt(FModel.getPreferences().getPref(FPref.UI_SLEEVES).split(",")[0]),
                BuildInfo.getVersionString(),
                GuiBase.getInterface().isLibgdxPort()
        ));
    }

}