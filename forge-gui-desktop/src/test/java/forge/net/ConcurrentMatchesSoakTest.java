package forge.net;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameView;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.HostingServer;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.interfaces.ILobbyListener;
import forge.player.PlayerControllerHuman;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/** Plays rounds of AI-driven matches side by side in one lobby and checks that they stay apart and leave nothing behind. */
public class ConcurrentMatchesSoakTest {
    private static final int MATCHES = 3;
    private static final int SEATS = MATCHES * 2;
    private static final int ROUNDS = Integer.getInteger("soak.rounds", 3);
    private static final ZoneType[] ZONES = {ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Battlefield, ZoneType.Exile};

    private FServerManager server;
    private ServerGameLobby lobby;
    private int port;
    private final List<HeadlessNetworkClient> clients = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    private static void log(final String fmt, final Object... args) {
        System.out.println("[SOAK] " + String.format(fmt, args));
    }

    private void problem(final String fmt, final Object... args) {
        final String p = String.format(fmt, args);
        problems.add(p);
        log("PROBLEM: %s", p);
    }

    private static boolean await(final BooleanSupplier condition, final long ms) throws InterruptedException {
        final long end = System.currentTimeMillis() + ms;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private static Map<String, Integer> threadCounts() {
        final Map<String, Integer> counts = new TreeMap<>();
        for (final Thread t : Thread.getAllStackTraces().keySet()) {
            if (!t.isAlive()) {
                continue;
            }
            final String name = t.getName();
            counts.merge(name.startsWith("nioEventLoopGroup") ? name.replaceAll("-\\d+$", "-#") : name.replaceAll("[-#]?\\d+", "#"), 1, Integer::sum);
        }
        return counts;
    }

    private void seatClients(final int round) throws InterruptedException {
        for (int i = 1; i <= SEATS; i++) {
            final LobbySlot slot = lobby.getSlot(i);
            slot.setType(LobbySlotType.OPEN);
            final Deck deck = TestDeckLoader.getRandomPrecon();
            slot.setDeck(deck);
        }
        for (int i = 1; i <= SEATS; i++) {
            final HeadlessNetworkClient client = new HeadlessNetworkClient("R" + round + "S" + i, "localhost", port);
            clients.add(client);
            Assert.assertTrue(client.connect(15_000), "seat " + i + " did not connect in round " + round);
            Assert.assertEquals(client.getAssignedSlot(), i, "round " + round);
            // Joining can replace the deck, so it is set again once the seat is taken
            lobby.getSlot(i).setDeck(TestDeckLoader.getRandomPrecon());
            lobby.getSlot(i).setIsReady(true);
        }
    }

    private void leaveClients() throws InterruptedException {
        clients.forEach(HeadlessNetworkClient::close);
        clients.clear();
        if (!await(() -> {
            for (int i = 1; i <= SEATS; i++) {
                if (server.findClientByIndex(i) != null) {
                    return false;
                }
            }
            return true;
        }, 15_000)) {
            problem("clients still registered after they left");
        }
    }

    private static String describe(final PlayerView p) {
        final StringBuilder sb = new StringBuilder(p.getName()).append(" life=").append(p.getLife());
        for (final ZoneType z : ZONES) {
            sb.append(' ').append(z.name()).append('=').append(p.getZoneSize(z));
        }
        return sb.toString();
    }

    private static String describe(final Player p) {
        final StringBuilder sb = new StringBuilder(p.getName()).append(" life=").append(p.getLife());
        for (final ZoneType z : ZONES) {
            sb.append(' ').append(z.name()).append('=').append(p.getZone(z).size());
        }
        return sb.toString();
    }

    private Set<String> hostState(final Game game) {
        final Set<String> s = new HashSet<>();
        for (final Player p : game.getRegisteredPlayers()) {
            s.add(describe(p));
        }
        return s;
    }

    private static Set<String> clientState(final HeadlessNetworkClient client) {
        final Set<String> s = new HashSet<>();
        final GameView view = client.getGameView();
        if (view != null) {
            for (final PlayerView p : view.getPlayers()) {
                s.add(describe(p));
            }
        }
        return s;
    }

    // Fails if matches running side by side share a game, show a client another match's players, end up out of sync with
    // their clients, or keep games, seats, GUIs or threads after they end
    @Test(timeOut = 3_600_000)
    public void roundsOfConcurrentMatchesLeaveNoTrace() throws Exception {
        if (!Boolean.getBoolean("run.stress.tests")) {
            throw new SkipException("stress test; run with -Drun.stress.tests=true");
        }
        TestUtils.ensureFModelInitialized();
        port = PortAllocator.allocatePort();
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
        lobby.getSlot(0).setType(LobbySlotType.AI);
        for (int i = 2; i <= SEATS; i++) {
            lobby.addSlot();
        }

        final List<List<WeakReference<Game>>> gamesByRound = new ArrayList<>();
        final Map<String, Integer> baselineThreads = threadCounts();
        Map<String, Integer> afterFirstRound = null;
        log("baseline threads %s", baselineThreads);
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                final long started = System.currentTimeMillis();
                seatClients(round);

                final List<Game> games = new ArrayList<>();
                final List<HostedMatch> matches = new ArrayList<>();
                for (int m = 0; m < MATCHES; m++) {
                    final int a = 2 * m + 1;
                    final int b = a + 1;
                    final Runnable start = lobby.startGame(List.of(lobby.getSlot(a), lobby.getSlot(b)));
                    Assert.assertNotNull(start, "round " + round + " match " + m + " refused to start");
                    start.run();
                    final HostedMatch match = lobby.getHostedMatch(a);
                    Assert.assertNotNull(match);
                    matches.add(match);
                    games.add(match.getGame());
                }
                final List<WeakReference<Game>> refs = new ArrayList<>();
                games.forEach(g -> refs.add(new WeakReference<>(g)));
                gamesByRound.add(refs);

                final Set<Integer> ids = new HashSet<>();
                games.forEach(g -> ids.add(g.getId()));
                if (ids.size() != MATCHES) {
                    problem("round %d: game ids not distinct %s", round, ids);
                }

                // One seat per match goes to the AI so the game ends. The other stays a remote human who passes.
                for (int m = 0; m < MATCHES; m++) {
                    final int aiSeat = 2 * m + 1 + (round + m) % 2;
                    final RemoteClient rc = server.findClientByIndex(aiSeat);
                    Assert.assertTrue(await(() -> clients.get(aiSeat - 1).getGameView() != null, 30_000), "seat " + aiSeat + " never saw its game");
                    server.convertToAI(rc);
                }

                for (int seat = 1; seat <= SEATS; seat++) {
                    final int m = (seat - 1) / 2;
                    final HeadlessNetworkClient client = clients.get(seat - 1);
                    final Set<String> expected = Set.of("R" + round + "S" + (2 * m + 1), "R" + round + "S" + (2 * m + 2));
                    final Set<String> seen = new HashSet<>();
                    for (final PlayerView p : client.getGameView().getPlayers()) {
                        seen.add(p.getName());
                    }
                    if (!seen.equals(expected)) {
                        problem("round %d seat %d sees players %s, expected %s", round, seat, seen, expected);
                    }
                    if (lobby.getController(seat) instanceof PlayerControllerHuman h && h.getGame() != games.get(m)) {
                        problem("round %d seat %d controller is in another match's game", round, seat);
                    }
                }

                for (int m = 0; m < MATCHES; m++) {
                    final Game game = games.get(m);
                    if (!await(game::isGameOver, 600_000)) {
                        problem("round %d match %d did not finish (turn %d)", round, m, game.getPhaseHandler().getTurn());
                        continue;
                    }
                    final int humanSeat = 2 * m + 1 + (round + m + 1) % 2;
                    final HeadlessNetworkClient human = clients.get(humanSeat - 1);
                    final Set<String> host = hostState(game);
                    if (!await(() -> clientState(human).equals(host), 10_000)) {
                        problem("round %d match %d client view differs from host%n  host   %s%n  client %s", round, m, host, clientState(human));
                    }
                    log("round %d match %d: game %d over at turn %d, winner %s", round, m, game.getId(),
                            game.getPhaseHandler().getTurn(), game.getOutcome() == null || game.getOutcome().getWinningLobbyPlayer() == null ? "draw" : game.getOutcome().getWinningLobbyPlayer().getName());
                    human.getGameController().nextGameDecision(NextGameDecision.QUIT);
                }

                if (!await(() -> !lobby.isMatchActive(), 30_000)) {
                    problem("round %d: lobby still has an active match", round);
                }
                // A quit is handled on the GUI thread, after the match already counts as over
                await(() -> {
                    for (int seat = 1; seat <= SEATS; seat++) {
                        if (lobby.getHostedMatch(seat) != null) {
                            return false;
                        }
                    }
                    return true;
                }, 30_000);
                for (int seat = 1; seat <= SEATS; seat++) {
                    if (lobby.getHostedMatch(seat) != null) {
                        problem("round %d seat %d still has a match after quitting", round, seat);
                    }
                    final RemoteClient rc = server.findClientByIndex(seat);
                    if (rc != null && rc.getGui() != null) {
                        problem("round %d seat %d client kept its GUI", round, seat);
                    }
                }
                long mismatches = 0;
                long fullSyncs = 0;
                for (final HeadlessNetworkClient c : clients) {
                    mismatches += c.getEventStateMismatches();
                    fullSyncs += c.getFullStateSyncsReceived();
                }
                if (mismatches != 0) {
                    problem("round %d: %d event/state mismatches on clients", round, mismatches);
                }
                log("round %d done in %d s: full state syncs %d, send errors %d", round,
                        (System.currentTimeMillis() - started) / 1000, fullSyncs, server.getTotalSendErrors());

                matches.clear();
                games.clear();
                leaveClients();
                final Map<String, Integer> threads = threadCounts();
                log("round %d threads %s", round, threads);
                if (afterFirstRound == null) {
                    afterFirstRound = threads;
                }
            }

            for (int i = 0; i < 5; i++) {
                System.gc();
                Thread.sleep(200);
            }
            for (int r = 0; r < gamesByRound.size(); r++) {
                int alive = 0;
                for (final WeakReference<Game> ref : gamesByRound.get(r)) {
                    if (ref.get() != null) {
                        alive++;
                    }
                }
                log("round %d: %d of %d games still reachable after GC", r + 1, alive, MATCHES);
                // The AI cache keeps the last game it saw until an AI thinks again, so only the last round may keep one
                if (alive > (r == gamesByRound.size() - 1 ? 1 : 0)) {
                    problem("round %d: %d games still reachable after GC", r + 1, alive);
                }
            }
            final Map<String, Integer> end = threadCounts();
            log("end threads %s", end);
            // Netty starts a worker per new connection up to a fixed number, and the test clients' own threads wind down on their own
            for (final Map.Entry<String, Integer> e : end.entrySet()) {
                final String name = e.getKey();
                final int before = afterFirstRound == null ? 0 : afterFirstRound.getOrDefault(name, 0);
                if (!name.startsWith("nioEventLoopGroup") && !name.startsWith("HeadlessClient") && e.getValue() > before) {
                    problem("%s threads grew from %d after the first round to %d", name, before, e.getValue());
                }
            }
        } finally {
            if (HostingServer.isHosting()) {
                server.stopServer();
            }
            clients.forEach(HeadlessNetworkClient::close);
            clients.clear();
        }
        Assert.assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
