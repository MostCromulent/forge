package forge.web;

import com.google.common.primitives.Ints;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameType;
import forge.game.player.Player;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.PreparedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.NetworkLogConfig;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.HostingServer;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.interfaces.IDraftEventHandler;
import forge.interfaces.ILobbyListener;
import forge.interfaces.IUpdateable;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.player.PlayerControllerHuman;
import forge.model.FModel;
import forge.util.Localizer;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/** The netplay seat one browser plays from, as a client of a server inside this process that opens no socket of its own. */
public final class LocalGame {
    private static final long JOIN_TIMEOUT_SECONDS = 15;
    /** How long a new table waits for the last one's connections to be gone. */
    private static final long FREE_SEATS_TIMEOUT_MILLIS = 5_000;
    /** More seats than any table has, so every connection a table can hold is checked. */
    private static final int MOST_SEATS = 16;
    private static final int SPECTATE_WAIT_MILLIS = 5000;

    private final FServerManager server = FServerManager.getInstance();
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
        // The server costs nothing to leave running, so it outlives every game it serves
        if (!HostingServer.isHosting()) {
            NetworkLogConfig.activateNetworkLogging();
            server.startLoopbackServer();
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
        // The host reads chat through its own listener, so the client one would only repeat it
        connect(playerName, gui, onUpdate, (from, text) -> { }, () -> { });
    }

    /** Waits for the last table's connections to go, since the server frees a late-closing seat in whichever lobby is current by then. */
    private void awaitOldSeatsFreed() {
        if (!HostingServer.isHosting()) {
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
    public void openGuest(final String playerName, final WebGuiGame gui,
            final Runnable onUpdate, final BiConsumer<String, String> onChat, final Runnable onClosed) {
        endMatch();
        connect(playerName, gui, onUpdate, onChat, onClosed);
    }

    /** Every seat, the host's included, reaches the game as a netplay client. */
    private void connect(final String playerName, final WebGuiGame gui,
            final Runnable onUpdate, final BiConsumer<String, String> onChat, final Runnable onClosed) {
        joined = new ClientGameLobby();
        // AbstractGuiGame.getDeckForPlayer reads the client lobby
        gui.setClientLobby(joined);
        final CountDownLatch ready = new CountDownLatch(1);
        client = new FGameClient(playerName, gui, FServerManager.LOOPBACK);
        client.setDispatchExecutor(gui.dispatchExecutor());
        client.setDraftHandler(draftHandler);
        client.addLobbyListener(new ClientListener(joined, ready, onChat, onClosed, seat -> {
            webSeat = seat;
            onUpdate.run();
        }));
        client.connect();
        try {
            if (!ready.await(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(Localizer.getInstance().getMessage("lblWebLocalGameNoFreeSeat"));
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while joining", e);
        }
    }

    /** Sends the table out to every client, because a slot edited straight on the server does not announce itself. */
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

    /** Readies the match, which only the host can, and returns what starts it, or null when it will not start. */
    public Runnable prepare() {
        if (hosted == null) {
            throw new IllegalStateException(Localizer.getInstance().getMessage("lblWebLocalGameOnlyHostStarts"));
        }
        return hosted.startGame();
    }

    /** Sets a game up and starts it at once, which is what a test wants. */
    public void startMatch(final String playerName, final Deck playerDeck, final String aiName, final Deck aiDeck,
            final WebGuiGame gui) {
        openHost(playerName, gui, () -> { }, (from, text) -> { });
        seatAndStart(List.of(
                new Seat(playerName, false, 0, 0, playerDeck),
                new Seat(aiName, true, storedIndex(FPref.UI_AVATARS, 1), storedIndex(FPref.UI_SLEEVES, 1), aiDeck)));
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

    /** A match a game mode prepared itself, played from the browser's seat as every other web match is. */
    public void startPrepared(final String playerName, final PreparedMatch match, final WebGuiGame gui) {
        openHost(playerName, gui, () -> { }, (from, text) -> { });
        hosted.startPrepared(match, webSeat);
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
        final Runnable begin = prepare();
        if (begin == null) {
            throw new IllegalStateException(Localizer.getInstance().getMessage("lblWebLocalGameLobbyRefused"));
        }
        begin.run();
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

    /** Hands the web seat to an AI so the browser spectates, which only the host can do because the seat belongs to its server. */
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

    /** Hands another seat of the running match to an AI, which only the host can do. Outside a match it does nothing. */
    public void handToAi(final int seat) {
        if (hosted != null) {
            server.replaceWithAI(seat);
        }
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
        // An empty lobby takes the late disconnects, which would otherwise hold this table's seats for a reconnect at the next table
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

    /** Ends a game still being played at a closing table, whose thread would otherwise wait for good on players who have left. */
    private void abandonGame() {
        final HostedMatch match = hostedMatch();
        final Game game = match == null ? null : match.getGame();
        if (game == null || game.isGameOver()) {
            return;
        }
        // Ending the game clears the players' controllers, so the humans are found first
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

    /** Gives up the seat for good. Only the host stops the server, because only the host started it. */
    public void shutdown() {
        endMatch();
        if (startedServer) {
            server.stopServer();
            startedServer = false;
        }
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
