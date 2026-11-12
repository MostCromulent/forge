package forge.net;

import com.google.common.eventbus.Subscribe;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameStage;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.HostingServer;
import forge.gamemodes.net.server.HostingServer.AfkTimeout;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.interfaces.ILobbyListener;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** One lobby plays several matches at once, each between some of its seats. */
public class ConcurrentMatchesTest {
    private static final int REMOTE_SEATS = 4;

    private FServerManager server;
    private ServerGameLobby lobby;
    private final List<HeadlessNetworkClient> clients = new ArrayList<>();

    private static Deck deck(final String name) {
        final Deck deck = new Deck(name);
        deck.getMain().add("Forest", 40);
        deck.getMain().add("Grizzly Bears", 20);
        return deck;
    }

    @BeforeMethod
    public void seatFourRemotePlayers() throws InterruptedException {
        TestUtils.ensureFModelInitialized();
        final int port = PortAllocator.allocatePort();
        server = FServerManager.getInstance();
        server.startServer(port);
        lobby = new ServerGameLobby();
        server.setLobby(lobby);
        server.setLobbyListener(new ILobbyListener() {
            @Override public void update(final GameLobbyData state, final int slot) { }
            @Override public void message(final String source, final String message, final ChatMessage.MessageType type) { }
            @Override public void close() { }
            @Override public ClientGameLobby getLobby() {
                return null;
            }
        });

        // The host does not play, so every match in this test is between remote seats only
        lobby.getSlot(0).setType(LobbySlotType.AI);
        for (int i = 1; i <= REMOTE_SEATS; i++) {
            if (i > 1) {
                lobby.addSlot();
            }
            lobby.getSlot(i).setType(LobbySlotType.OPEN);
            lobby.getSlot(i).setDeck(deck("Seat" + i));
        }
        for (int i = 1; i <= REMOTE_SEATS; i++) {
            final HeadlessNetworkClient client = new HeadlessNetworkClient("Seat" + i, "localhost", port);
            clients.add(client);
            Assert.assertTrue(client.connect(10_000), "Seat" + i + " did not connect");
            Assert.assertEquals(client.getAssignedSlot(), i);
            lobby.getSlot(i).setIsReady(true);
        }
    }

    @AfterMethod(alwaysRun = true)
    public void stopServer() {
        for (int i = 1; i <= REMOTE_SEATS; i++) {
            // A concession while the game still deals its opening hands breaks that game's start
            if (lobby.getController(i) instanceof PlayerControllerHuman controller && controller.getGame().getAge() == GameStage.Play) {
                controller.concede();
            }
        }
        // Before the clients close: a client that leaves a running match is kept for its reconnect, and would outlive this server
        if (HostingServer.isHosting()) {
            server.stopServer();
        }
        clients.forEach(HeadlessNetworkClient::close);
        clients.clear();
    }

    private static void await(final BooleanSupplier condition, final String why) throws InterruptedException {
        for (int i = 0; i < 3000 && !condition.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        Assert.assertTrue(condition.getAsBoolean(), why);
    }

    private Runnable matchBetween(final int... seats) {
        final List<LobbySlot> slots = new ArrayList<>();
        for (final int seat : seats) {
            slots.add(lobby.getSlot(seat));
        }
        return lobby.startGame(slots);
    }

    private PlayerControllerHuman controllerOf(final int seat) {
        return (PlayerControllerHuman) lobby.getController(seat);
    }

    private HeadlessNetworkClient clientOf(final int seat) {
        return clients.get(seat - 1);
    }

    // Fails if a second match cannot start beside a running one, or if a seat is answered by the other match's game
    @Test(timeOut = 120_000)
    public void eachSeatPlaysInItsOwnMatch() throws Exception {
        matchBetween(1, 2).run();
        final HostedMatch first = lobby.getHostedMatch(1);
        matchBetween(3, 4).run();
        final HostedMatch second = lobby.getHostedMatch(3);

        Assert.assertNotNull(first);
        Assert.assertNotNull(second);
        Assert.assertNotSame(first, second);
        Assert.assertSame(lobby.getHostedMatch(2), first);
        Assert.assertSame(lobby.getHostedMatch(4), second);
        Assert.assertNull(lobby.getHostedMatch(0), "the host's AI seat plays in no match");
        Assert.assertFalse(first.getGame().isGameOver());
        Assert.assertFalse(second.getGame().isGameOver());
        for (int seat = 1; seat <= REMOTE_SEATS; seat++) {
            Assert.assertSame(controllerOf(seat).getGame(), (seat <= 2 ? first : second).getGame(), "seat " + seat);
        }

        Assert.assertNull(matchBetween(3, 4), "a seat started a second match while it was playing");
        Assert.assertNull(matchBetween(2, 3), "a seat started a second match while it was playing");
    }

    // Fails if a finished match nobody quit keeps its seats, survives their next match, or comes back on a late continue
    @Test(timeOut = 120_000)
    public void aFinishedMatchIsQuitWhenItsSeatsPlayAgain() throws Exception {
        matchBetween(1, 2).run();
        matchBetween(3, 4).run();
        final HostedMatch first = lobby.getHostedMatch(1);
        final HostedMatch second = lobby.getHostedMatch(3);
        final Game firstGame = first.getGame();

        await(() -> clientOf(1).getGameController() != null, "the first match never opened on its client");
        await(() -> firstGame.getAge() == GameStage.Play, "the first match never dealt its opening hands");
        clientOf(1).getGameController().concede();
        await(firstGame::isGameOver, "the concession did not end the game");

        matchBetween(1, 2).run();
        final HostedMatch third = lobby.getHostedMatch(1);
        Assert.assertNotNull(third, "the seats of a finished match could not play again");
        Assert.assertNotSame(third, first);
        Assert.assertTrue(first.isMatchOver(), "the finished match was not quit");
        Assert.assertTrue(lobby.getSlot(1).isReady() && lobby.getSlot(2).isReady(), "quitting the old match unreadied seats that play now");
        Assert.assertNotNull(server.findClientByIndex(1).getGui(), "the new match's client has no GUI");
        Assert.assertSame(lobby.getHostedMatch(4), second);

        first.continueMatch();
        first.restartMatch();
        Assert.assertNull(first.getGame(), "a quit match started another game");
    }

    // Fails if a rematch of a finished match still counts as over, which opens the lobby to logins while it plays
    @Test(timeOut = 120_000)
    public void aRematchCountsAsRunning() throws Exception {
        final String gamesPerMatch = FModel.getPreferences().getPref(FPref.UI_MATCHES_PER_GAME);
        FModel.getPreferences().setPref(FPref.UI_MATCHES_PER_GAME, "1");
        try {
            matchBetween(1, 2).run();
            final HostedMatch match = lobby.getHostedMatch(1);
            final Game game = match.getGame();
            await(() -> clientOf(1).getGameController() != null, "the match never opened on its client");
            await(() -> game.getAge() == GameStage.Play, "the match never dealt its opening hands");
            clientOf(1).getGameController().concede();
            await(match::isMatchOver, "a one-game match did not end with its game");

            match.restartMatch();
            Assert.assertTrue(match.isGameRunning(), "the rematch did not start");
            Assert.assertFalse(match.isMatchOver(), "the rematch counts as over");
            Assert.assertTrue(lobby.isMatchActive());
            Assert.assertNull(matchBetween(2, 3), "a seat of the rematch started another match");
        } finally {
            FModel.getPreferences().setPref(FPref.UI_MATCHES_PER_GAME, gamesPerMatch);
        }
    }

    // Fails if the team check counts seats that are not in the match being started
    @Test(timeOut = 120_000)
    public void theTeamCheckCoversOnlyTheStartingSeats() {
        lobby.getSlot(1).setTeam(1);
        lobby.getSlot(2).setTeam(1);
        Assert.assertNull(matchBetween(1, 2), "two seats of one team started a match");
    }

    // Fails if games share a random stream, so that one game's draws count against another game's undo
    @Test(timeOut = 120_000)
    public void eachGameDrawsFromItsOwnRandomStream() throws Exception {
        matchBetween(1, 2).run();
        matchBetween(3, 4).run();
        final List<AtomicReference<Random>> streams = new ArrayList<>();
        for (final int seat : new int[] {1, 3}) {
            final AtomicReference<Random> stream = new AtomicReference<>();
            streams.add(stream);
            lobby.getHostedMatch(seat).getGame().subscribeToEvents(new Object() {
                @Subscribe
                public void turnBegan(final GameEventTurnBegan event) {
                    stream.compareAndSet(null, MyRandom.getRandom());
                }
            });
        }
        await(() -> streams.get(0).get() != null && streams.get(1).get() != null, "a game never began a turn");
        Assert.assertNotSame(streams.get(0).get(), streams.get(1).get(), "two games draw from one stream");
        Assert.assertNotSame(streams.get(0).get(), MyRandom.getRandom(), "a game draws from the stream of the thread that started it");
    }

    // Fails if the AFK timer is armed only for one of the matches
    @Test(timeOut = 120_000)
    public void afkTimerArmsInEveryMatch() {
        matchBetween(1, 2).run();
        matchBetween(3, 4).run();
        for (int seat = 1; seat <= REMOTE_SEATS; seat++) {
            final AfkTimeout timeout = server.armAfkTimeout(controllerOf(seat), null);
            Assert.assertNotSame(timeout, AfkTimeout.NOOP, "seat " + seat);
            timeout.cancel();
        }
    }

    // Fails if replacing a player with the AI reaches into the other match
    @Test(timeOut = 120_000)
    public void aiTakesOverOnlyTheSeatThatLeft() {
        matchBetween(1, 2).run();
        matchBetween(3, 4).run();
        final Player third = controllerOf(3).getPlayer();

        server.convertToAI(server.findClientByIndex(3));

        Assert.assertTrue(third.getController().isAI());
        for (final int seat : new int[] {1, 2, 4}) {
            final Player player = controllerOf(seat).getPlayer();
            Assert.assertSame(player.getController(), controllerOf(seat), "seat " + seat + " lost its human controller");
        }
    }

    // Fails if a client's call reaches the other match's game, or if one match ending takes the other's seats with it
    @Test(timeOut = 120_000)
    public void oneMatchEndsAndTheOtherPlaysOn() throws Exception {
        matchBetween(1, 2).run();
        matchBetween(3, 4).run();
        final HostedMatch first = lobby.getHostedMatch(1);
        final HostedMatch second = lobby.getHostedMatch(3);
        final Game firstGame = first.getGame();
        final Game secondGame = second.getGame();
        final RemoteClient fourth = server.findClientByIndex(4);
        final Object fourthGui = fourth.getGui();
        Assert.assertNotNull(fourthGui);

        await(() -> clientOf(1).getGameController() != null && clientOf(2).getGameController() != null, "the first match never opened on its clients");
        await(() -> firstGame.getAge() == GameStage.Play, "the first match never dealt its opening hands");
        clientOf(1).getGameController().concede();
        await(firstGame::isGameOver, "the concession did not end the conceding seat's game");
        Assert.assertFalse(secondGame.isGameOver(), "the concession ended the other match's game");

        clientOf(2).getGameController().nextGameDecision(NextGameDecision.QUIT);
        await(() -> lobby.getHostedMatch(1) == null && lobby.getHostedMatch(2) == null, "the finished match kept its seats");

        Assert.assertTrue(lobby.isMatchActive());
        Assert.assertSame(lobby.getHostedMatch(3), second);
        Assert.assertSame(lobby.getHostedMatch(4), second);
        Assert.assertSame(controllerOf(4).getGame(), secondGame);
        // A reconnecting client is paused, resumed and resynced through this GUI
        Assert.assertSame(fourth.getGui(), fourthGui, "the other match's client lost its GUI");
        Assert.assertNull(server.findClientByIndex(2).getGui());
        Assert.assertFalse(lobby.getSlot(2).isReady());
        Assert.assertTrue(lobby.getSlot(4).isReady(), "a seat still playing was made unready");

        lobby.getSlot(1).setIsReady(true);
        lobby.getSlot(2).setIsReady(true);
        matchBetween(1, 2).run();
        final HostedMatch third = lobby.getHostedMatch(1);
        Assert.assertNotNull(third);
        Assert.assertNotSame(third, first);
        Assert.assertSame(lobby.getHostedMatch(4), second);
    }
}
