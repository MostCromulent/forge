package forge.web;

import com.google.common.primitives.Ints;
import forge.deck.Deck;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.interfaces.ILobbyListener;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.tinylog.Logger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** The loopback netplay host local games run on, and the web client's seat in each match. Call on the host UI thread. */
public final class LocalGame {
    private static final long JOIN_TIMEOUT_SECONDS = 15;
    private final FServerManager server = FServerManager.getInstance();
    private int port = -1;
    private ServerGameLobby lobby;
    private FGameClient client;

    public void startMatch(final String playerName, final Deck playerDeck, final String aiName, final Deck aiDeck, final WebGuiGame gui) {
        if (port < 0) {
            port = server.startLoopbackServer();
            server.setLobbyListener(new LogOnlyListener());
        }
        endMatch();
        lobby = new ServerGameLobby();
        server.setLobby(lobby);
        final LobbySlot ai = lobby.getSlot(0);
        ai.setType(LobbySlotType.AI);
        ai.setName(aiName);
        ai.setDeck(aiDeck);
        // Slot 0 would otherwise carry the host's own avatar and sleeve
        ai.setAvatarIndex(storedIndex(FPref.UI_AVATARS, 1));
        ai.setSleeveIndex(storedIndex(FPref.UI_SLEEVES, 1));
        ai.setIsReady(true);
        final LobbySlot seat = lobby.getSlot(1);
        seat.setType(LobbySlotType.OPEN);
        seat.setDeck(playerDeck);
        seat.setIsReady(false);

        final ClientGameLobby clientLobby = new ClientGameLobby();
        // AbstractGuiGame.getDeckForPlayer reads the client lobby
        gui.setClientLobby(clientLobby);
        final CountDownLatch joined = new CountDownLatch(1);
        client = new FGameClient(playerName, gui, "127.0.0.1", port);
        client.setDispatchExecutor(gui.dispatchExecutor());
        client.addLobbyListener(new ClientListener(clientLobby, joined));
        client.connect();
        try {
            if (!joined.await(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The web client did not take its seat");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while joining", e);
        }
        seat.setIsReady(true);
        final Runnable start = lobby.startGame();
        if (start == null) {
            throw new IllegalStateException("The lobby refused to start the match");
        }
        start.run();
    }

    /** The saved avatar or sleeve for a lobby seat, falling back to the seat number as the desktop lobby does. */
    static int storedIndex(final FPref pref, final int seat) {
        final String[] stored = FModel.getPreferences().getPref(pref).split(",");
        final Integer v = seat < stored.length ? Ints.tryParse(stored[seat].trim()) : null;
        return v == null || v < 0 ? seat : v;
    }

    public HostedMatch hostedMatch() {
        return lobby == null ? null : lobby.getHostedMatch();
    }

    public void endMatch() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (port >= 0) {
            server.clearPlayerGuis();
        }
        lobby = null;
    }

    public void shutdown() {
        endMatch();
        if (port >= 0) {
            server.stopServer();
            port = -1;
        }
    }

    private record ClientListener(ClientGameLobby clientLobby, CountDownLatch joined) implements ILobbyListener {
        @Override public void message(final String source, final String message, final ChatMessage.MessageType type) { }
        @Override public void update(final GameLobbyData state, final int slot) {
            clientLobby.setData(state);
            clientLobby.setLocalPlayer(slot);
            if (slot >= 0) {
                joined.countDown();
            }
        }
        @Override public void close() { }
        @Override public ClientGameLobby getLobby() { return clientLobby; }
    }

    // FServerManager requires a lobby listener: a client dropping mid-match calls it without a null check
    private static final class LogOnlyListener implements ILobbyListener {
        @Override public void message(final String source, final String message, final ChatMessage.MessageType type) {
            Logger.info("Local host: {}", message);
        }
        @Override public void update(final GameLobbyData state, final int slot) { }
        @Override public void close() { }
        @Override public ClientGameLobby getLobby() { return null; }
    }
}
