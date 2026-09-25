package forge.web;

import com.google.common.primitives.Ints;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameType;
import forge.game.player.Player;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.interfaces.IDraftEventHandler;
import forge.interfaces.ILobbyListener;
import forge.interfaces.IUpdateable;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.player.PlayerControllerHuman;
import forge.model.FModel;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/**
 * The netplay seat one browser plays from, and the game behind it when that browser is the host. Every browser
 * is a client of the same loopback server: the host starts it and takes a seat, and each guest takes another.
 * Nothing is served to the network here, so only the web port is ever reachable from outside this machine.
 *
 * <p>There is one of these per browser. The host's calls come on the host UI thread; a guest's join runs on a
 * background thread, because taking a seat waits on the server.
 */
public final class LocalGame {
    private static final long JOIN_TIMEOUT_SECONDS = 15;
    /** How long a new table waits for the last one's connections to be gone. */
    private static final long FREE_SEATS_TIMEOUT_MILLIS = 5_000;
    /** More seats than any table has, so every connection a table can hold is checked. */
    private static final int MOST_SEATS = 16;
    private static final int SPECTATE_WAIT_MILLIS = 5000;

    private final FServerManager server = FServerManager.getInstance();
    private int port = -1;
    /** The lobby this session owns, or null when another browser is hosting the game. */
    private ServerGameLobby hosted;
    /** Whether this session started the server. It outlives any one game, so the open lobby cannot say. */
    private boolean startedServer;
    private ClientGameLobby joined;
    private FGameClient client;
    private int webSeat = -1;
    /** Hears the draft and pool events the host sends this seat; set before a table is opened or joined. */
    private IDraftEventHandler draftHandler;

    /** One seat of a game set up in a single call. */
    public record Seat(String name, boolean ai, int avatar, int sleeve, Deck deck) {
    }

    public void setDraftHandler(final IDraftEventHandler handler) {
        draftHandler = handler;
    }

    /** Sends an event to the host as this seat's client, which is how a draft pick reaches the draft host. */
    public void sendToHost(final NetEvent event) {
        final FGameClient c = client;
        if (c != null) {
            c.send(event);
        }
    }

    public ServerGameLobby hostedLobby() {
        return hosted;
    }

    public ClientGameLobby clientLobby() {
        return joined;
    }

    public boolean isHost() {
        return hosted != null;
    }

    /** The seat the browser sits in, or -1 before it has one. */
    public int webSeat() {
        return webSeat;
    }

    /** Starts a game this machine owns and takes a seat in it, with an AI in each of the others. */
    public void openHost(final String playerName, final WebGuiGame gui, final Runnable onUpdate,
            final BiConsumer<String, String> onChat) {
        endMatch();
        awaitOldSeatsFreed();
        // Stopping the server frees its event loops before it finishes recreating them, so a restart can find
        // them terminated. It costs nothing to leave running, so it outlives every game it serves.
        if (port < 0) {
            port = server.startLoopbackServer();
            startedServer = true;
        }
        openHosted(playerName, gui, onUpdate, onChat);
        // The browser took the first open seat, so the rest of the table is the host's to fill
        for (int i = 0; i < hosted.getNumberOfSlots(); i++) {
            if (i != webSeat) {
                final LobbySlot slot = hosted.getSlot(i);
                slot.setType(LobbySlotType.AI);
                slot.setName(Lobby.computerName(hosted));
                slot.setIsReady(true);
            }
        }
        // The client took its seat before these were filled, so its copy of the table is a step behind
        pushLobby();
    }

    private void openHosted(final String playerName, final WebGuiGame gui, final Runnable onUpdate,
            final BiConsumer<String, String> onChat) {
        hosted = new ServerGameLobby();
        server.setLobby(hosted);
        // Slot 0 starts as the host's own local seat; the browser plays through a client, so it is opened up
        hosted.getSlot(0).setType(LobbySlotType.OPEN);
        hosted.setListener(new IUpdateable() {
            @Override public void update(final boolean fullUpdate) {
                server.updateLobbyState();
                onUpdate.run();
            }
            @Override public void update(final int slot, final LobbySlotType type) { }
        });
        server.setLobbyListener(new HostChat(onChat));
        // The host reads chat through its own listener, so the client one would only repeat it. Its own
        // connection ends only because the host ended it, which has already told the browser.
        connect(playerName, gui, port, onUpdate, (from, text) -> { }, () -> { });
    }

    /**
     * Waits until no connection from an earlier table is left on the server. A connection closes in the background,
     * and the server frees its seat when it notices, in whichever lobby is current by then: were the new table set up
     * first, a seat in it would be cleared under whoever had just taken it.
     */
    private void awaitOldSeatsFreed() {
        if (port < 0) {
            return;
        }
        final long giveUp = System.currentTimeMillis() + FREE_SEATS_TIMEOUT_MILLIS;
        while (anySeatConnected()) {
            if (System.currentTimeMillis() > giveUp) {
                Logger.warn("A connection from the last table is still open; setting up the new one anyway.");
                return;
            }
            try {
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean anySeatConnected() {
        for (int i = 0; i < MOST_SEATS; i++) {
            if (server.findClientByIndex(i) != null) {
                return true;
            }
        }
        return false;
    }

    /** Takes a seat in a game another browser on this machine is hosting. Nothing is served from here. */
    public void openGuest(final String playerName, final WebGuiGame gui, final int hostPort,
            final Runnable onUpdate, final BiConsumer<String, String> onChat, final Runnable onClosed) {
        close();
        port = hostPort;
        connect(playerName, gui, hostPort, onUpdate, onChat, onClosed);
    }

    /** Every seat, the host's included, reaches the game over loopback. */
    private void connect(final String playerName, final WebGuiGame gui, final int onPort,
            final Runnable onUpdate, final BiConsumer<String, String> onChat, final Runnable onClosed) {
        joined = new ClientGameLobby();
        // AbstractGuiGame.getDeckForPlayer reads the client lobby
        gui.setClientLobby(joined);
        final CountDownLatch ready = new CountDownLatch(1);
        client = new FGameClient(playerName, gui, "127.0.0.1", onPort);
        client.setDispatchExecutor(gui.dispatchExecutor());
        client.setDraftHandler(draftHandler);
        client.addLobbyListener(new ClientListener(joined, ready, onChat, onClosed, seat -> {
            webSeat = seat;
            onUpdate.run();
        }));
        client.connect();
        try {
            if (!ready.await(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("No seat was free in that game");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while joining", e);
        }
    }

    /** The loopback port this game is served on, which is where its other seats connect. */
    public int port() {
        return port;
    }

    /**
     * Sends the table out to every client. A slot edited straight on the server does not announce itself, so
     * without this the browser's own copy of the lobby keeps the old answer.
     */
    public void pushLobby() {
        if (hosted != null) {
            server.updateLobbyState();
        }
    }

    /** Changes to the browser's own seat travel as they would from any client. */
    public void updateOwnSeat(final UpdateLobbyPlayerEvent event) {
        if (client != null) {
            client.send(event);
        }
    }

    public void sendChat(final String message) {
        if (client != null) {
            client.send(new MessageEvent(message));
        }
    }

    /** Starts the match. Only the machine hosting it can. */
    public void start() {
        if (hosted == null) {
            throw new IllegalStateException("Only the host can start the match");
        }
        final Runnable start = hosted.startGame();
        if (start == null) {
            throw new IllegalStateException("The lobby refused to start the match");
        }
        start.run();
    }

    /** Sets a game up and starts it at once, which is what a test wants. */
    public void startMatch(final String playerName, final Deck playerDeck, final String aiName, final Deck aiDeck,
            final WebGuiGame gui) {
        startMatch(List.of(
                new Seat(playerName, false, 0, 0, playerDeck),
                new Seat(aiName, true, storedIndex(FPref.UI_AVATARS, 1), storedIndex(FPref.UI_SLEEVES, 1), aiDeck)),
                GameType.Constructed, gui);
    }

    /** Seats are taken in the order given; exactly one must be the browser's. */
    public void startMatch(final List<Seat> seats, final GameType format, final WebGuiGame gui) {
        final Seat mine = seats.stream().filter(s -> !s.ai()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No seat for the browser"));
        openHost(mine.name(), gui, () -> { }, (from, text) -> { });
        if (format != GameType.Constructed) {
            hosted.applyVariant(format);
        }
        seatAndStart(seats);
    }

    /** A sealed or draft match against computer seats, typed as limitedType, as desktop's offline limited screens start one. */
    public void startLimitedMatch(final List<Seat> seats, final GameType limitedType, final WebGuiGame gui) {
        final Seat mine = seats.stream().filter(s -> !s.ai()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No seat for the browser"));
        openHost(mine.name(), gui, () -> { }, (from, text) -> { });
        hosted.setLimitedMode(true);
        hosted.setLimitedType(limitedType);
        seatAndStart(seats);
    }

    private void seatAndStart(final List<Seat> seats) {
        while (hosted.getNumberOfSlots() < seats.size()) {
            hosted.addSlot();
        }
        int next = 0;
        for (final Seat seat : seats) {
            final int index = seat.ai() ? nextAiSlot(next) : webSeat;
            if (seat.ai()) {
                next = index + 1;
                final LobbySlot slot = hosted.getSlot(index);
                slot.setType(LobbySlotType.AI);
                slot.setName(seat.name());
                slot.setAvatarIndex(seat.avatar());
                slot.setSleeveIndex(seat.sleeve());
                slot.setIsReady(true);
            }
            hosted.getSlot(index).setDeck(seat.deck());
        }
        hosted.getSlot(webSeat).setIsReady(true);
        start();
    }

    private int nextAiSlot(final int from) {
        for (int i = from; i < hosted.getNumberOfSlots(); i++) {
            if (i != webSeat) {
                return i;
            }
        }
        throw new IllegalStateException("No seat left for an AI");
    }

    /** The saved avatar or sleeve for a lobby seat, falling back to the seat number as the desktop lobby does. */
    static int storedIndex(final FPref pref, final int seat) {
        final String[] stored = FModel.getPreferences().getPref(pref).split(",");
        final Integer v = seat < stored.length ? Ints.tryParse(stored[seat].trim()) : null;
        return v == null || v < 0 ? seat : v;
    }

    /** Hands the web seat to an AI, the way the host does for a player who never reconnects, so the browser
     *  spectates instead of playing. Only the host can: the seat belongs to its server. */
    public void spectate() {
        if (hosted == null) {
            Logger.warn("Only the host can hand its seat to the AI");
            return;
        }
        final HostedMatch match = hostedMatch();
        for (int i = 0; i < SPECTATE_WAIT_MILLIS / 50 && (match == null || match.getGame() == null); i++) {
            try {
                Thread.sleep(50);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        final RemoteClient seat = server.getClientBySlotIndex(webSeat);
        if (seat == null) {
            Logger.warn("No web seat to hand to the AI");
            return;
        }
        server.convertToAI(seat);
    }

    public HostedMatch hostedMatch() {
        return hosted == null ? null : hosted.getHostedMatch();
    }

    /** The host's own player in the running game, or null: not the host, no game, or the seat handed to the AI. */
    public Player ownPlayer() {
        final HostedMatch match = hostedMatch();
        final RemoteClient seat = hosted == null ? null : server.getClientBySlotIndex(webSeat);
        if (match == null || match.getGame() == null || seat == null) {
            return null;
        }
        for (final Player p : match.getGame().getPlayers()) {
            if (p.getController() instanceof PlayerControllerHuman human && human.getGui() instanceof RemoteClientGuiGame gui
                    && gui.getClient() == seat) {
                return p;
            }
        }
        return null;
    }

    public void endMatch() {
        abandonGame();
        // A draft still running keeps its timers and would deal packs to whoever sits at the next table
        if (hosted != null) {
            hosted.clearCurrentEvent();
        }
        // The server notices a closed connection later, in whichever lobby it is serving by then. Left with this
        // table's, it would count a finished match whose players have not yet chosen what next as still going, and
        // hold the seat for a reconnect under the player's name, which the next table's seat of that name then
        // walks into instead of taking a seat. An empty lobby of its own takes those disconnects instead.
        if (hosted != null) {
            server.setLobby(new ServerGameLobby());
        }
        if (client != null) {
            client.close();
            client = null;
        }
        // Every seat's GUI lives on the one server, so only the host may clear them
        if (hosted != null) {
            server.clearPlayerGuis();
        }
        hosted = null;
        joined = null;
        webSeat = -1;
    }

    /**
     * Ends a game still being played at a table that is closing. Its thread is waiting on players who are leaving
     * and will never answer, so without this it waits for good, holding the whole game. Ended the way a concession
     * ends one: the game is over, and every human's waiting input is let go so the thread can finish.
     */
    private void abandonGame() {
        final HostedMatch match = hostedMatch();
        final Game game = match == null ? null : match.getGame();
        if (game == null || game.isGameOver()) {
            return;
        }
        // Remote players' controllers have no event handler here to let their inputs go, as in concede(), and ending
        // the game clears the players' controllers, so they are found first
        final List<PlayerControllerHuman> humans = new ArrayList<>();
        for (final Player p : game.getRegisteredPlayers()) {
            if (p.getController() instanceof PlayerControllerHuman human) {
                humans.add(human);
            }
        }
        game.getAction().invoke(() -> {
            game.setGameOver(GameEndReason.Draw);
            for (final PlayerControllerHuman human : humans) {
                human.getInputQueue().onGameOver(true);
            }
        });
    }

    /** Leaves whatever game is open. The server stays up, ready for the next one. */
    public void close() {
        endMatch();
    }

    /** Gives up the seat for good. Only the host stops the server, because only the host started it. */
    public void shutdown() {
        endMatch();
        if (startedServer) {
            server.stopServer();
            startedServer = false;
        }
        port = -1;
    }

    private record ClientListener(ClientGameLobby clientLobby, CountDownLatch ready, BiConsumer<String, String> onChat,
            Runnable onClosed, IntConsumer onSeat) implements ILobbyListener {
        @Override public void message(final String source, final String message, final ChatMessage.MessageType type) {
            onChat.accept(source, message);
        }
        @Override public void update(final GameLobbyData state, final int slot) {
            clientLobby.setData(state);
            clientLobby.setLocalPlayer(slot);
            if (slot >= 0) {
                onSeat.accept(slot);
                ready.countDown();
            }
        }
        @Override public void close() { onClosed.run(); }
        @Override public ClientGameLobby getLobby() { return clientLobby; }
    }

    /** The host sees chat through its own listener; a joined client gets it through the client's. */
    private record HostChat(BiConsumer<String, String> onChat) implements ILobbyListener {
        @Override public void message(final String source, final String message, final ChatMessage.MessageType type) {
            onChat.accept(source, message);
        }
        @Override public void update(final GameLobbyData state, final int slot) { }
        @Override public void close() { }
        @Override public ClientGameLobby getLobby() { return null; }
    }
}
