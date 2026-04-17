package forge.perf;

import forge.ai.AvailableActions.Variant;
import forge.deck.Deck;
import forge.game.*;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.net.TestDeckLoader;
import forge.net.TestUtils;
import forge.perf.InstrumentedPlayerController.VariantAggregate;
import org.apache.commons.lang3.time.StopWatch;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Performance test for {@link forge.ai.AvailableActions} across 2, 3, and 4
 * player games. Each priority pass runs the heuristic once per
 * {@link Variant} (BASELINE, FLASHBACK, SORTED, FLASHBACK_SORTED) so results
 * are comparable on identical game states.
 *
 * Run via IntelliJ "Yield Perf Test" config, or:
 *   java -cp &lt;classpath&gt; forge.perf.YieldPerfTest
 *   -Dperf.2p=20 -Dperf.3p=15 -Dperf.4p=15 -Dperf.warmup=5
 */
@Test(groups = "stress")
public class YieldPerfTest {

    private static final int TIMEOUT_SECONDS = 180;

    @BeforeClass
    public void setup() {
        TestUtils.ensureFModelInitialized();
    }

    @Test(description = "Run manually via main()")
    public void compareYieldEvaluationCost() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        TestUtils.ensureFModelInitialized();

        int games2p = Integer.getInteger("perf.2p", 20);
        int games3p = Integer.getInteger("perf.3p", 15);
        int games4p = Integer.getInteger("perf.4p", 15);
        int warmup = Integer.getInteger("perf.warmup", 5);
        boolean jfrEnabled = Boolean.getBoolean("jfr.enabled");

        System.out.println("=== AvailableActions Performance Test ===");
        System.out.println("Games: " + games2p + "x 2-player, " + games3p + "x 3-player, " + games4p + "x 4-player");
        System.out.println("Warmup: " + warmup + " games");
        System.out.println("Each priority pass runs all four variants on the same game state.");
        if (jfrEnabled) System.out.println("JFR recording enabled");
        System.out.println();

        // Warmup
        System.out.println("--- Warmup ---");
        for (int i = 0; i < warmup; i++) {
            int pc = (i % 3) + 2; // cycle 2,3,4,2,3...
            runSingleGame(pc, i + 1, true);
        }
        System.out.println();

        // Main run
        jdk.jfr.Recording jfrRec = jfrEnabled ? startJfr("yield-perf") : null;

        List<GameResult> results = new ArrayList<>();
        int gameNum = 1;

        // Interleave player counts to spread JVM state evenly
        int max = Math.max(games2p, Math.max(games3p, games4p));
        int done2 = 0, done3 = 0, done4 = 0;
        System.out.println("--- Measuring ---");
        for (int round = 0; round < max; round++) {
            if (done2 < games2p) {
                results.add(runSingleGame(2, gameNum++, false));
                done2++;
            }
            if (done3 < games3p) {
                results.add(runSingleGame(3, gameNum++, false));
                done3++;
            }
            if (done4 < games4p) {
                results.add(runSingleGame(4, gameNum++, false));
                done4++;
            }
        }

        if (jfrRec != null) stopJfr(jfrRec, "target/yield-perf.jfr");

        // Report
        printReport(results);

        if (jfrEnabled) {
            System.out.println();
            System.out.println("JFR recording: target/yield-perf.jfr");
            System.out.println("  pushd target && java ../.claude/tools/JfrFlameGraph.java yield-perf.jfr && popd");
        }
    }

    private static GameResult runSingleGame(int playerCount, int gameNum, boolean warmup) {
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setPlayForAnte(false);
        rules.setMatchAnteRarity(true);
        rules.setGamesPerMatch(1);
        rules.setManaBurn(false);
        rules.setSimTimeout(TIMEOUT_SECONDS);

        List<RegisteredPlayer> players = new ArrayList<>();
        List<LobbyPlayerInstrumented> lobbies = new ArrayList<>();

        for (int i = 0; i < playerCount; i++) {
            Deck deck = TestDeckLoader.getRandomPrecon();
            LobbyPlayerInstrumented lobby = new LobbyPlayerInstrumented("AI-" + (i + 1), true);
            RegisteredPlayer rp = new RegisteredPlayer(deck);
            rp.setPlayer(lobby);
            players.add(rp);
            lobbies.add(lobby);
        }

        Match match = new Match(rules, players, "YieldPerfTest");
        Game game = match.createGame();

        StopWatch sw = new StopWatch();
        sw.start();
        try {
            forge.view.TimeLimitedCodeBlock.runWithTimeout(() -> {
                match.startGame(game);
            }, TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            if (!warmup) System.out.printf("  Game %d: TIMEOUT (%dp)%n", gameNum, playerCount);
        } catch (Exception | StackOverflowError e) {
            if (!warmup) System.out.printf("  Game %d: ERROR (%dp) - %s%n", gameNum, playerCount, e.getMessage());
        } finally {
            if (sw.isStarted()) sw.stop();
            if (!game.isGameOver()) game.setGameOver(GameEndReason.Draw);
        }

        long ms = sw.getTime();
        int turns = game.getPhaseHandler().getTurn();

        int totalBoard = 0;
        try {
            for (Player p : game.getPlayers()) {
                totalBoard += p.getCardsIn(ZoneType.Battlefield).size();
            }
        } catch (Exception ignored) {}

        long evalCount = 0;
        List<InstrumentedPlayerController> controllers = new ArrayList<>();
        for (LobbyPlayerInstrumented lobby : lobbies) {
            InstrumentedPlayerController ctrl = lobby.getLastController();
            if (ctrl != null) {
                controllers.add(ctrl);
                evalCount += ctrl.getEvalCount();
            }
        }

        String winner;
        try {
            winner = game.getOutcome().isDraw() ? "Draw"
                    : game.getOutcome().getWinningLobbyPlayer().getName();
        } catch (Exception e) { winner = "?"; }

        String prefix = warmup ? "  [warmup] " : "  ";
        double baselineMs = 0;
        for (InstrumentedPlayerController ctrl : controllers) {
            baselineMs += ctrl.getAggregate(Variant.BASELINE).getTotalMs();
        }
        System.out.printf("%sGame %d (%dp): %dms, %d turns, board=%d, winner=%s, evals=%d (baseline=%.1fms)%n",
                prefix, gameNum, playerCount, ms, turns, totalBoard, winner, evalCount, baselineMs);

        GameResult r = new GameResult();
        r.playerCount = playerCount;
        r.gameMs = ms;
        r.turns = turns;
        r.totalBoard = totalBoard;
        r.evalCount = evalCount;
        r.controllers = controllers;
        return r;
    }

    private static void printReport(List<GameResult> results) {
        System.out.println();
        System.out.println("=== RESULTS ===");

        for (int pc : new int[]{2, 3, 4}) {
            List<GameResult> group = results.stream()
                    .filter(r -> r.playerCount == pc).collect(Collectors.toList());
            if (group.isEmpty()) continue;

            long totalCalls = group.stream().mapToLong(r -> r.evalCount).sum();
            double avgTurns = group.stream().mapToInt(r -> r.turns).average().orElse(0);
            double avgBoard = group.stream().mapToInt(r -> r.totalBoard).average().orElse(0);

            System.out.println();
            System.out.printf("--- %d-player games (%d games) ---%n", pc, group.size());
            System.out.printf("Avg turns: %.0f  |  Avg total board: %.1f cards  |  Total evaluations: %d%n",
                    avgTurns, avgBoard, totalCalls);

            printVariantTable(group, totalCalls);
            printPhaseBreakdown(group);
            printExitZoneBreakdown(group);
            printBoardStateContext(group);
        }
    }

    private static void printVariantTable(List<GameResult> group, long totalCalls) {
        System.out.println();
        System.out.printf("  %-20s  %10s  %10s  %10s  %10s  %10s  %8s  %8s  %8s  %9s%n",
                "Variant", "Total ms", "Per-game", "Avg/call", "Max/call", "vs base",
                "Found %", "Disagree", "Timeouts", "canAfford");
        double baselineTotalMs = aggregateMs(group, Variant.BASELINE);
        int gameCount = group.size();
        for (Variant v : Variant.values()) {
            double total = 0, max = 0;
            long canAfford = 0, timeouts = 0, found = 0, disagreements = 0, calls = 0;
            for (GameResult r : group) {
                for (InstrumentedPlayerController ctrl : r.controllers) {
                    VariantAggregate a = ctrl.getAggregate(v);
                    total += a.getTotalMs();
                    if (a.getMaxCallMs() > max) max = a.getMaxCallMs();
                    canAfford += a.canAffordCalls;
                    timeouts += a.timeouts;
                    found += a.foundAction;
                    disagreements += a.disagreements;
                    calls += a.calls;
                }
            }
            double perGame = gameCount > 0 ? total / gameCount : 0;
            double avgCall = totalCalls > 0 ? total / totalCalls : 0;
            double ratio = baselineTotalMs > 0 ? total / baselineTotalMs : 0;
            double foundPct = calls > 0 ? 100.0 * found / calls : 0;
            System.out.printf("  %-20s  %10.1f  %10.1f  %10.4f  %10.2f  %10.2fx  %7.1f%%  %8d  %8d  %9d%n",
                    v.name(), total, perGame, avgCall, max, ratio,
                    foundPct, disagreements, timeouts, canAfford);
        }
    }

    private static void printExitZoneBreakdown(List<GameResult> group) {
        System.out.println();
        System.out.printf("  %-20s  %8s  %8s  %8s  %8s  %8s%n",
                "Exit-zone %", "Hand", "Battlfld", "External", "None", "Timeout");
        for (Variant v : Variant.values()) {
            long hand = 0, bf = 0, ext = 0, none = 0, timeout = 0, calls = 0;
            for (GameResult r : group) {
                for (InstrumentedPlayerController ctrl : r.controllers) {
                    VariantAggregate a = ctrl.getAggregate(v);
                    hand += a.exitHand;
                    bf += a.exitBattlefield;
                    ext += a.exitExternal;
                    none += a.exitNone;
                    timeout += a.exitTimeout;
                    calls += a.calls;
                }
            }
            double denom = calls > 0 ? 100.0 / calls : 0;
            System.out.printf("  %-20s  %7.1f%%  %7.1f%%  %7.1f%%  %7.1f%%  %7.1f%%%n",
                    v.name(),
                    hand * denom, bf * denom, ext * denom, none * denom, timeout * denom);
        }
    }

    private static double aggregateMs(List<GameResult> group, Variant v) {
        double total = 0;
        for (GameResult r : group) {
            for (InstrumentedPlayerController ctrl : r.controllers) {
                total += ctrl.getAggregate(v).getTotalMs();
            }
        }
        return total;
    }

    private static void printPhaseBreakdown(List<GameResult> group) {
        System.out.println();
        System.out.printf("  %-20s  %10s  %10s  %10s%n",
                "Variant phases (ms)", "Hand", "Battlfld", "External");
        for (Variant v : Variant.values()) {
            double hand = 0, bf = 0, ext = 0;
            for (GameResult r : group) {
                for (InstrumentedPlayerController ctrl : r.controllers) {
                    VariantAggregate a = ctrl.getAggregate(v);
                    hand += a.getHandMs();
                    bf += a.getBattlefieldMs();
                    ext += a.getExternalZonesMs();
                }
            }
            System.out.printf("  %-20s  %10.1f  %10.1f  %10.1f%n", v.name(), hand, bf, ext);
        }
    }

    private static void printBoardStateContext(List<GameResult> group) {
        double handSize = 0, bfSize = 0, extSize = 0, fbSize = 0;
        long evals = 0;
        for (GameResult r : group) {
            for (InstrumentedPlayerController ctrl : r.controllers) {
                long n = ctrl.getEvalCount();
                handSize += ctrl.getAvgHandSize() * n;
                bfSize += ctrl.getAvgBattlefieldSize() * n;
                extSize += ctrl.getAvgExternalZonesSize() * n;
                fbSize += ctrl.getAvgFlashbackSize() * n;
                evals += n;
            }
        }
        if (evals == 0) return;
        System.out.println();
        System.out.printf("  Avg zone sizes: hand=%.1f, battlefield=%.1f, graveyard+exile+command=%.1f, flashback=%.1f%n",
                handSize / evals, bfSize / evals, extSize / evals, fbSize / evals);
    }

    private static jdk.jfr.Recording startJfr(String name) {
        try {
            jdk.jfr.Configuration config = jdk.jfr.Configuration.getConfiguration("profile");
            jdk.jfr.Recording recording = new jdk.jfr.Recording(config);
            recording.setName(name);
            recording.start();
            return recording;
        } catch (Exception e) {
            System.err.println("Failed to start JFR: " + e.getMessage());
            return null;
        }
    }

    private static void stopJfr(jdk.jfr.Recording recording, String path) {
        try {
            recording.stop();
            recording.dump(java.nio.file.Path.of(path));
            recording.close();
            System.out.println("  JFR saved: " + path);
        } catch (Exception e) {
            System.err.println("Failed to save JFR: " + e.getMessage());
        }
    }

    static class GameResult {
        int playerCount;
        long gameMs;
        int turns, totalBoard;
        long evalCount;
        List<InstrumentedPlayerController> controllers;
    }
}
