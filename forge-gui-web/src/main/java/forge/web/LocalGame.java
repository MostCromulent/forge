package forge.web;

import com.google.common.primitives.Ints;
import forge.deck.Deck;
import forge.game.GameType;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.interfaces.ILobbyListener;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.tinylog.Logger;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** The loopback netplay host local games run on, and the web client's seat in each match. Call on the host UI thread. */
public final class LocalGame {
    private static final long JOIN_TIMEOUT_SECONDS = 15;
    private static final int SPECTATE_WAIT_MILLIS = 5000;
    /** The lobby slot the browser sits in, chosen when the match starts. */
    private int webSeat = 1;
    private final FServerManager server = FServerManager.getInstance();
    private int port = -1;
    private ServerGameLobby lobby;
    private FGameClient client;

    /** One seat of the offline lobby. The browser plays exactly one of them; the host plays the rest. */
    public record Seat(String name, boolean ai, int avatar, int sleeve, Deck deck) {
    }

    public void startMatch(final String playerName, final Deck playerDeck, final String aiName, final Deck aiDeck, final WebGuiGame gui) {
        startMatch(List.of(
                new Seat(aiName, true, storedIndex(FPref.UI_AVATARS, 1), storedIndex(FPref.UI_SLEEVES, 1), aiDeck),
                new Seat(playerName, false, 0, 0, playerDeck)), GameType.Constructed, gui);
    }

    /** Seats are taken in the order given; exactly one must be the browser's. */
    public void startMatch(final List<Seat> seats, final GameType format, final WebGuiGame gui) {
        webSeat = -1;
        for (int i = 0; i < seats.size(); i++) {
            if (!seats.get(i).ai()) {
                if (webSeat >= 0) {
                    throw new IllegalArgumentException("Only one seat can be the browser's");
                }
                webSeat = i;
            }
        }
        if (webSeat < 0) {
            throw new IllegalArgumentException("No seat for the browser");
        }
        if (port < 0) {
            port = server.startLoopbackServer();
            server.setLobbyListener(new LogOnlyListener());
        }
        endMatch();
        lobby = new ServerGameLobby();
        server.setLobby(lobby);
        // Constructed is the absence of a format variant rather than one of its own, as the desktop lobby has it
        if (format != GameType.Constructed) {
            lobby.applyVariant(format);
        }
        while (lobby.getNumberOfSlots() < seats.size()) {
            lobby.addSlot();
        }
        for (int i = 0; i < seats.size(); i++) {
            final Seat s = seats.get(i);
            final LobbySlot slot = lobby.getSlot(i);
            slot.setDeck(s.deck());
            if (i == webSeat) {
                // The browser's own name, avatar and sleeve arrive with the client's login
                slot.setType(LobbySlotType.OPEN);
                slot.setIsReady(false);
                continue;
            }
            slot.setType(LobbySlotType.AI);
            slot.setName(s.name());
            // A host slot would otherwise carry the host's own avatar and sleeve
            slot.setAvatarIndex(s.avatar());
            slot.setSleeveIndex(s.sleeve());
            slot.setIsReady(true);
        }
        final String playerName = seats.get(webSeat).name();
        final LobbySlot seat = lobby.getSlot(webSeat);

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

    /** Hands the web seat to an AI, the way the host does for a player who never reconnects, so the browser
     *  spectates two AI players instead of playing one of them. */
    public void spectate() {
        final HostedMatch match = hostedMatch();
        for (int i = 0; i < SPECTATE_WAIT_MILLIS / 50 && (match == null || match.getGame() == null); i++) {
            try {
                Thread.sleep(50);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        final RemoteClient client = server.getClientBySlotIndex(webSeat);
        if (client == null) {
            Logger.warn("No web seat to hand to the AI");
            return;
        }
        server.convertToAI(client);
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
