package forge.perf.tokenheavy;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.perf.OptimizationContext;
import forge.game.perf.PerfCounters;
import forge.game.player.RegisteredPlayer;
import forge.net.TestDeckLoader;
import forge.net.TestUtils;
import forge.perf.tokenheavy.InstrumentedController.Mode;
import forge.perf.tokenheavy.InstrumentedController.VariantSlot;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Test(groups = "stress")
public class TokenPerfTest {

    private static final int TIMEOUT_SECONDS = 120;
    private static final String FIXTURE_DIR = "perf/fixtures/tokenheavy";
    private static final String DEFAULT_FIXTURE = FIXTURE_DIR + "/empty-board-commander.txt";

    @BeforeClass
    public void setup() { TestUtils.ensureFModelInitialized(); }

    @Test(description = "Run via -Dhypothesis=H000_Noop (or H<id>_<name>); sweeps all fixtures by default, or -Dfixture=<path> for one")
    public void runHypothesis() throws Exception {
        String hypothesisId = System.getProperty("hypothesis", "H000_Noop");
        OptimizationContext variantCtx = loadVariant(hypothesisId);

        List<String> fixtures = resolveFixtures();
        System.out.println("Running " + hypothesisId + " against " + fixtures.size() + " fixture(s):");
        for (String f : fixtures) System.out.println("  " + f);

        String branch = System.getProperty("branch", "unknown");
        for (String fixtureRes : fixtures) {
            runOneHypothesis(hypothesisId, variantCtx, fixtureRes, branch);
        }
    }

    // Run hypothesis against one fixture. Each fixture gets its own warmup
    // pass (same game, counters off, slot timings discarded) before the
    // measured pass so JIT state is roughly equal between baseline and variant
    // slots from the first decision.
    private void runOneHypothesis(String hypothesisId, OptimizationContext variantCtx,
                                  String fixtureRes, String branch) throws Exception {
        // --- Warmup pass (discarded) ---
        {
            List<VariantSlot> warmupSlots = new ArrayList<>();
            warmupSlots.add(new VariantSlot("BASELINE", OptimizationContext.BASELINE));
            warmupSlots.add(new VariantSlot(hypothesisId, variantCtx));
            LobbyPlayerInstrumented warmupLobby =
                new LobbyPlayerInstrumented("AI-1", Mode.QUERY, warmupSlots);
            runOneFixture(fixtureRes, warmupLobby);
        }

        // --- Measured pass ---
        List<VariantSlot> slots = new ArrayList<>();
        slots.add(new VariantSlot("BASELINE", OptimizationContext.BASELINE));
        slots.add(new VariantSlot(hypothesisId, variantCtx));
        LobbyPlayerInstrumented lobby =
            new LobbyPlayerInstrumented("AI-1", Mode.QUERY, slots);

        // JFR needs several hundred samples to produce a useful flame graph;
        // short fixtures only yield ~20 samples per run. When -Djfr.loops=N is
        // set, we re-run the measured game N times so JFR accumulates samples
        // from all runs. Decision and counter data come from the LAST iteration
        // so totals stay comparable. Default = 1 (no loop).
        int loops = Math.max(1, Integer.getInteger("jfr.loops", 1));
        PerfCounters.resetAll();
        PerfCounters.enabled = true;
        jdk.jfr.Recording jfr = startJfrIfEnabled(hypothesisId, fixtureRes);
        long gameT0 = System.nanoTime();
        try {
            for (int i = 0; i < loops; i++) {
                if (i > 0) {
                    // Reset inter-iteration state: clear counters, clear slot
                    // timings/decisions so the final report reflects just one run.
                    PerfCounters.resetAll();
                    for (VariantSlot vs : slots) {
                        vs.decisions.clear();
                        vs.totalNanos = 0;
                    }
                }
                runOneFixture(fixtureRes, lobby);
            }
        } finally {
            PerfCounters.enabled = false;
            stopJfr(jfr);
        }
        long gameWallNanos = (System.nanoTime() - gameT0) / loops;
        if (loops > 1) {
            System.out.println("JFR loop: " + loops + " iterations, reporting last");
        }

        VariantSlot baseline = slots.get(0);
        VariantSlot variant = slots.get(1);
        int divergences = compareDecisions(baseline.decisions, variant.decisions);

        Map<String, Long> baselineCalls = new HashMap<>();
        Map<String, Long> variantCalls = new HashMap<>();
        Map<String, Long> baselineNanos = new HashMap<>();
        Map<String, Long> variantNanos = new HashMap<>();
        // QUERY mode: the live game runs under baseline context only, so
        // engine counters reflect baseline's totals. Variant slot counters
        // are populated from the same snapshot for the oracle — the variant
        // context's influence on engine counts isn't observable in QUERY mode
        // (it would be in RUN mode, where the variant drives a separate game).
        PerfCounters.snapshot().forEach((k, v) -> {
            baselineCalls.put(k, v.calls());
            variantCalls.put(k, v.calls());
            baselineNanos.put(k, v.nanos());
            variantNanos.put(k, v.nanos());
        });

        VerdictEvaluator.Verdict verdict = VerdictEvaluator.evaluate(
            baseline.totalNanos, variant.totalNanos,
            baselineCalls, variantCalls, divergences);

        System.out.println(ReportGenerator.renderText(
            hypothesisId, fixtureRes, baseline, variant, gameWallNanos,
            baselineCalls, variantCalls, baselineNanos, variantNanos,
            divergences, verdict));

        String json = ReportGenerator.renderJson(
            hypothesisId, fixtureRes, baseline, variant, gameWallNanos,
            baselineCalls, variantCalls, baselineNanos, variantNanos,
            divergences, verdict);
        HypothesisLog.appendJsonl(
            HypothesisLog.branchNotesPath(branch).resolve("hypotheses.jsonl"), json);
    }

    // Resolve fixture list: -Dfixture=<path> overrides (single); otherwise
    // enumerate all .txt resources under perf/fixtures/tokenheavy/.
    private List<String> resolveFixtures() throws Exception {
        String override = System.getProperty("fixture");
        if (override != null && !override.isBlank()) {
            return Collections.singletonList(override);
        }
        URL url = getClass().getClassLoader().getResource(FIXTURE_DIR);
        if (url == null) {
            return Collections.singletonList(DEFAULT_FIXTURE);
        }
        File dir = new File(url.toURI());
        if (!dir.isDirectory()) {
            return Collections.singletonList(DEFAULT_FIXTURE);
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".txt"));
        if (files == null || files.length == 0) {
            return Collections.singletonList(DEFAULT_FIXTURE);
        }
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(FIXTURE_DIR + "/" + f.getName());
        Collections.sort(out);
        return out;
    }

    // Start a JFR recording around the measured run when -Djfr=on is set.
    // Output lands at target/perf/<hypothesis>-<fixture-stem>.jfr, one file
    // per (hypothesis, fixture) pair. Render via .claude/tools/JfrFlameGraph.java.
    private jdk.jfr.Recording startJfrIfEnabled(String hypothesisId, String fixtureRes) {
        String flag = System.getProperty("jfr", "");
        if (!(flag.equalsIgnoreCase("on") || flag.equalsIgnoreCase("true"))) return null;
        try {
            jdk.jfr.Recording r = new jdk.jfr.Recording(
                jdk.jfr.Configuration.getConfiguration("profile"));
            // Override default 10ms execution-sample period to 1ms so short
            // fixtures (~50-300ms) yield dense enough data for a useful flame
            // graph without needing -Djfr.loops.
            long periodMs = Long.getLong("jfr.periodMs", 1L);
            r.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(periodMs));
            r.enable("jdk.NativeMethodSample").withPeriod(java.time.Duration.ofMillis(periodMs));
            r.setName("tokenperf-" + hypothesisId);
            String stem = fixtureRes.replaceAll(".*/", "").replaceAll("\\.txt$", "");
            java.nio.file.Path out = HypothesisLog.repoRoot()
                .resolve("target").resolve("perf")
                .resolve(hypothesisId + "-" + stem + ".jfr");
            java.nio.file.Files.createDirectories(out.getParent());
            r.setDestination(out);
            r.start();
            System.out.println("JFR recording: " + out + " (exec-sample period=" + periodMs + "ms)");
            return r;
        } catch (Exception e) {
            System.err.println("JFR start failed: " + e);
            return null;
        }
    }

    private void stopJfr(jdk.jfr.Recording r) {
        if (r == null) return;
        try {
            r.stop();
            r.close();
        } catch (Exception e) {
            System.err.println("JFR stop failed: " + e);
        }
    }

    private OptimizationContext loadVariant(String hypothesisId) throws Exception {
        if ("BASELINE".equalsIgnoreCase(hypothesisId)) return OptimizationContext.BASELINE;
        String fqn = "forge.perf.tokenheavy.variants." + hypothesisId;
        Class<?> cls = Class.forName(fqn);
        return (OptimizationContext) cls.getDeclaredConstructor().newInstance();
    }

    private void runOneFixture(String fixtureRes, LobbyPlayerInstrumented lobby) throws Exception {
        // Determinism: seed both the deck picker (TestDeckLoader.random) and the
        // game engine's shared RNG (MyRandom) with fixed seeds before each run so
        // back-to-back runs produce identical decision logs.
        java.lang.reflect.Field randField = TestDeckLoader.class.getDeclaredField("random");
        randField.setAccessible(true);
        ((java.util.Random) randField.get(null)).setSeed(0xF0D6L);
        forge.util.MyRandom.setRandom(new java.util.Random(0xF0D6L));

        PerfFixtureState state = PerfFixtureState.fromResource(fixtureRes);

        GameRules rules = new GameRules(GameType.Constructed);
        rules.setPlayForAnte(false);
        rules.setMatchAnteRarity(false);
        rules.setGamesPerMatch(1);
        rules.setManaBurn(false);
        rules.setSimTimeout(TIMEOUT_SECONDS);

        int playerCount = detectPlayerCount(fixtureRes);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            Deck deck = TestDeckLoader.getRandomPrecon();
            RegisteredPlayer rp = new RegisteredPlayer(deck);
            // In 2-player fixtures the AI-under-test uses human* keys, so it
            // becomes p0 (index 0). Otherwise instrument p0 as before.
            rp.setPlayer(i == 0 ? lobby : new forge.ai.LobbyPlayerAi("AI-" + (i + 1), java.util.Set.of()));
            players.add(rp);
        }

        Match match = new Match(rules, players, "TokenPerfTest");
        Game game = match.createGame();

        state.applyToGame(game);
        game.setAge(forge.game.GameStage.Play);

        try {
            forge.view.TimeLimitedCodeBlock.runWithTimeout(
                () -> game.getPhaseHandler().mainGameLoop(), TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ignored) {
            // OK — scripted fixtures are expected to time out; we measure decisions made so far.
        } catch (Exception | StackOverflowError e) {
            System.err.println("Harness error: " + e);
            e.printStackTrace(System.err);
        } finally {
            if (!game.isGameOver()) game.setGameOver(GameEndReason.Draw);
        }
    }

    // Peek at the fixture to decide how many RegisteredPlayers to create.
    // Rule: if any p2/p3 keys appear, it's 4-player; if any human/ai keys
    // appear (but no p2/p3), it's 2-player; else default 4.
    private int detectPlayerCount(String fixtureRes) throws java.io.IOException {
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(fixtureRes)) {
            if (in == null) return 4;
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in))) {
                int maxP = -1;
                boolean sawHumanAi = false;
                String line;
                while ((line = br.readLine()) != null) {
                    String lower = line.trim().toLowerCase();
                    if (lower.startsWith("p") && lower.length() > 1 && Character.isDigit(lower.charAt(1))) {
                        int idx = Character.digit(lower.charAt(1), 10);
                        if (idx > maxP) maxP = idx;
                    } else if (lower.startsWith("human") || lower.startsWith("ai")) {
                        sawHumanAi = true;
                    }
                }
                if (maxP >= 0) return maxP + 1;
                if (sawHumanAi) return 2;
                return 4;
            }
        }
    }

    private int compareDecisions(List<DecisionRecord> b, List<DecisionRecord> v) {
        int divergences = Math.abs(b.size() - v.size());
        for (int i = 0; i < Math.min(b.size(), v.size()); i++) {
            if (!b.get(i).equals(v.get(i))) {
                divergences++;
                if (divergences == 1) {
                    System.err.println("First divergence at index " + i);
                    System.err.println("  baseline: " + b.get(i));
                    System.err.println("  variant : " + v.get(i));
                }
            }
        }
        return divergences;
    }

    // Minimum decisions the common prefix must span for the determinism gate
    // to consider the fixture "deterministic enough" for variant comparison.
    // Total length may still differ — the engine's token-heavy run occasionally
    // throws a ConcurrentModificationException from forEachCardInGame at a
    // GC/JIT-timing-sensitive point, truncating one of the runs. That's an
    // orthogonal engine bug; the common prefix is what the oracle compares.
    private static final int DETERMINISM_MIN_PREFIX = 50;

    @Test(description = "Determinism gate — baseline run twice must produce identical decisions over a meaningful common prefix")
    public void assertDeterminism() throws Exception {
        java.util.List<DecisionRecord> first = runOnceForDeterminismCheck();
        java.util.List<DecisionRecord> second = runOnceForDeterminismCheck();
        System.out.println("Run 1 decisions: " + first.size());
        System.out.println("Run 2 decisions: " + second.size());
        int minLen = Math.min(first.size(), second.size());
        for (int i = 0; i < minLen; i++) {
            if (!first.get(i).equals(second.get(i))) {
                throw new AssertionError(
                    "Non-deterministic fixture at decision " + i
                    + "\n  first : " + first.get(i)
                    + "\n  second: " + second.get(i));
            }
        }
        if (minLen < DETERMINISM_MIN_PREFIX) {
            throw new AssertionError("Common prefix too short (" + minLen
                + " < " + DETERMINISM_MIN_PREFIX + ") — not enough decisions to trust oracle comparisons");
        }
        if (first.size() != second.size()) {
            System.out.println("Determinism OK over " + minLen + " common decisions"
                + " (length diff " + first.size() + " vs " + second.size()
                + " — engine CME truncation, tracked separately)");
        } else {
            System.out.println("Determinism OK: " + first.size() + " decisions captured identically twice");
        }
    }

    private java.util.List<DecisionRecord> runOnceForDeterminismCheck() throws Exception {
        java.util.List<InstrumentedController.VariantSlot> slots = new java.util.ArrayList<>();
        slots.add(new InstrumentedController.VariantSlot("BASELINE", OptimizationContext.BASELINE));
        LobbyPlayerInstrumented lobby =
            new LobbyPlayerInstrumented("AI-1", InstrumentedController.Mode.RUN, slots);
        runOneFixture(DEFAULT_FIXTURE, lobby);
        return slots.get(0).decisions;
    }

    // --- Real-games discovery-mode profiling -------------------------------
    //
    // Complementary to the fixture-based hypothesis A/B workflow: run N full
    // 4-player AI-vs-AI games to natural completion (or timeout) under a single
    // JFR recording. No instrumentation, no oracle — pure profile collection
    // to validate that fixture-based hot paths reflect real-gameplay hot paths.
    //
    // Run: mvn -pl forge-gui-desktop -am test -Dtest=TokenPerfTest#profileRealGames
    //        [-Dperf.games=5] [-Dperf.warmup=1] [-Dperf.maxSecondsPerGame=180]
    //        [-Dperf.decks=tokenish]   # filter precon names containing substring
    //
    // Gated by "stress" group same as the rest of the class; not run in CI.

    private static final int REAL_GAMES_DEFAULT = 5;
    private static final int REAL_GAMES_WARMUP = 1;
    private static final int REAL_GAMES_PLAYERS = 4;

    @Test(description = "Discovery mode — JFR profile N full AI-vs-AI games with random precons; optionally under a variant context via -Dhypothesis=<name>")
    public void profileRealGames() throws Exception {
        int games = Math.max(1, Integer.getInteger("perf.games", REAL_GAMES_DEFAULT));
        int warmup = Math.max(0, Integer.getInteger("perf.warmup", REAL_GAMES_WARMUP));
        int perGameTimeoutSec = Math.max(30, Integer.getInteger("perf.maxSecondsPerGame", 180));
        String deckFilter = System.getProperty("perf.decks", "").toLowerCase();
        String hypothesisId = System.getProperty("hypothesis", "");

        OptimizationContext variantCtx = OptimizationContext.BASELINE;
        if (!hypothesisId.isEmpty()) {
            variantCtx = loadVariant(hypothesisId);
        }

        System.out.println("=== Real-games profile: " + games + " games ("
            + warmup + " warmup), " + REAL_GAMES_PLAYERS + " players each, timeout "
            + perGameTimeoutSec + "s/game"
            + (deckFilter.isEmpty() ? "" : ", deck filter: '" + deckFilter + "'")
            + (hypothesisId.isEmpty() ? " [BASELINE context]" : " [variant: " + hypothesisId + "]")
            + " ===");

        // Seed RNGs so the whole batch is repeatable. Different seed per game
        // so games differ from each other but the batch as a whole is stable.
        seedRandoms(0xF0D6L);

        // Warmup (not profiled, not reported — just absorbs JVM startup / JIT)
        OptimizationContext.set(variantCtx);
        try {
            for (int i = 0; i < warmup; i++) {
                runOneRealGame(i + 1, perGameTimeoutSec, deckFilter, true);
            }
        } finally {
            OptimizationContext.reset();
        }

        PerfCounters.resetAll();
        PerfCounters.enabled = true;
        jdk.jfr.Recording jfr = startRealGamesJfr();
        long batchT0 = System.nanoTime();
        List<RealGameResult> results = new ArrayList<>();
        OptimizationContext.set(variantCtx);
        try {
            for (int i = 0; i < games; i++) {
                results.add(runOneRealGame(i + 1, perGameTimeoutSec, deckFilter, false));
            }
        } finally {
            OptimizationContext.reset();
            PerfCounters.enabled = false;
            stopJfr(jfr);
        }
        long batchNanos = System.nanoTime() - batchT0;

        printRealGamesReport(results, batchNanos);
        printRealGamesCounters();

        // H005 verify: shouldAttack cache stats.
        if (variantCtx instanceof forge.perf.tokenheavy.variants.H005_ShouldAttackCache_Verify) {
            forge.perf.tokenheavy.variants.H005_ShouldAttackCache_Verify v =
                (forge.perf.tokenheavy.variants.H005_ShouldAttackCache_Verify) variantCtx;
            forge.game.perf.ShouldAttackCache c = v.getCacheForReport();
            System.out.println();
            System.out.println("=== H005 VERIFY STATS ===");
            System.out.printf("  divergences:    %d%n",
                forge.perf.tokenheavy.variants.H005_ShouldAttackCache_Verify.divergenceCount());
            System.out.printf("  cache hits:     %d%n", c.hits());
            System.out.printf("  cache misses:   %d%n", c.misses());
            System.out.printf("  cache size:     %d%n", c.size());
            long total = c.hits() + c.misses();
            if (total > 0) {
                System.out.printf("  hit rate:       %.1f%%%n", 100.0 * c.hits() / total);
            }
        }

        // H002 verify: surface cache hit/miss stats + divergences so absence of
        // error output doesn't hide an unused-cache false positive.
        if (variantCtx instanceof forge.perf.tokenheavy.variants.H002_CanBlockCache_Verify) {
            forge.perf.tokenheavy.variants.H002_CanBlockCache_Verify v =
                (forge.perf.tokenheavy.variants.H002_CanBlockCache_Verify) variantCtx;
            forge.game.perf.CanBlockCache c = v.getCacheForReport();
            System.out.println();
            System.out.println("=== H002 VERIFY STATS ===");
            System.out.printf("  divergences:    %d%n",
                forge.perf.tokenheavy.variants.H002_CanBlockCache_Verify.divergenceCount());
            System.out.printf("  cache hits:     %d%n", c.pureHits());
            System.out.printf("  cache misses:   %d%n", c.pureMisses());
            System.out.printf("  cache size:     %d%n", c.pureSize());
            long total = c.pureHits() + c.pureMisses();
            if (total > 0) {
                System.out.printf("  hit rate:       %.1f%%%n", 100.0 * c.pureHits() / total);
            }
        }
    }

    private void printRealGamesCounters() {
        System.out.println();
        System.out.println("=== ENGINE / AI COUNTERS (batch totals) ===");
        java.util.Map<String, forge.game.perf.PerfCounters.Counter> snap = PerfCounters.snapshot();
        if (snap.isEmpty()) {
            System.out.println("  (no counters fired)");
            return;
        }
        snap.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().nanos(), a.getValue().nanos()))
            .forEach(e -> System.out.printf("  %-55s %10d calls %8d ms%n",
                e.getKey(), e.getValue().calls(), e.getValue().nanos() / 1_000_000));
    }

    private static final class RealGameResult {
        int gameNum;
        long gameMs;
        int turns;
        int totalBoard;
        String winner;
        List<String> deckNames;
        String status;   // "ok" | "timeout" | "error:<msg>"
    }

    private RealGameResult runOneRealGame(int gameNum, int timeoutSec, String deckFilter, boolean warmup) {
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setPlayForAnte(false);
        rules.setMatchAnteRarity(false);
        rules.setGamesPerMatch(1);
        rules.setManaBurn(false);
        rules.setSimTimeout(timeoutSec);

        List<RegisteredPlayer> players = new ArrayList<>();
        List<String> deckNames = new ArrayList<>();
        for (int i = 0; i < REAL_GAMES_PLAYERS; i++) {
            Deck deck = pickDeck(deckFilter);
            deckNames.add(deck.getName());
            RegisteredPlayer rp = new RegisteredPlayer(deck);
            rp.setPlayer(new forge.ai.LobbyPlayerAi("AI-" + (i + 1), java.util.Set.of()));
            players.add(rp);
        }

        Match match = new Match(rules, players, "TokenPerfTest-RealGames");
        Game game = match.createGame();

        RealGameResult r = new RealGameResult();
        r.gameNum = gameNum;
        r.deckNames = deckNames;
        r.status = "ok";

        org.apache.commons.lang3.time.StopWatch sw = new org.apache.commons.lang3.time.StopWatch();
        sw.start();
        try {
            forge.view.TimeLimitedCodeBlock.runWithTimeout(
                () -> match.startGame(game), timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            r.status = "timeout";
        } catch (Exception | StackOverflowError e) {
            r.status = "error:" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            if (sw.isStarted()) sw.stop();
            if (!game.isGameOver()) game.setGameOver(GameEndReason.Draw);
        }

        r.gameMs = sw.getTime();
        r.turns = game.getPhaseHandler().getTurn();
        try {
            int board = 0;
            for (forge.game.player.Player p : game.getPlayers()) {
                board += p.getCardsIn(forge.game.zone.ZoneType.Battlefield).size();
            }
            r.totalBoard = board;
        } catch (Exception ignored) { r.totalBoard = -1; }
        try {
            r.winner = game.getOutcome().isDraw() ? "Draw"
                : game.getOutcome().getWinningLobbyPlayer().getName();
        } catch (Exception e) { r.winner = "?"; }

        String prefix = warmup ? "  [warmup] " : "  ";
        System.out.printf("%sGame %d: %dms, %d turns, board=%d, winner=%s, status=%s%n    decks=%s%n",
            prefix, r.gameNum, r.gameMs, r.turns, r.totalBoard, r.winner, r.status,
            String.join(" | ", r.deckNames));
        return r;
    }

    // Pick a deck from TestDeckLoader with optional name-substring filter.
    // Falls back to unfiltered random pick if the filter matches nothing.
    private Deck pickDeck(String deckFilter) {
        if (deckFilter.isEmpty()) return TestDeckLoader.getRandomPrecon();
        // Try up to 20 random picks looking for a deck whose name contains the filter.
        for (int i = 0; i < 20; i++) {
            Deck d = TestDeckLoader.getRandomPrecon();
            if (d.getName().toLowerCase().contains(deckFilter)) return d;
        }
        // Filter too restrictive; fall back to unfiltered.
        return TestDeckLoader.getRandomPrecon();
    }

    private jdk.jfr.Recording startRealGamesJfr() {
        try {
            jdk.jfr.Recording r = new jdk.jfr.Recording(
                jdk.jfr.Configuration.getConfiguration("profile"));
            long periodMs = Long.getLong("jfr.periodMs", 10L);  // default 10ms for long runs
            r.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(periodMs));
            r.enable("jdk.NativeMethodSample").withPeriod(java.time.Duration.ofMillis(periodMs));
            r.setName("tokenperf-realgames");
            String ts = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            java.nio.file.Path out = HypothesisLog.repoRoot()
                .resolve("target").resolve("perf").resolve("realgames-" + ts + ".jfr");
            java.nio.file.Files.createDirectories(out.getParent());
            r.setDestination(out);
            r.start();
            System.out.println("JFR recording: " + out + " (exec-sample period=" + periodMs + "ms)");
            return r;
        } catch (Exception e) {
            System.err.println("JFR start failed: " + e);
            return null;
        }
    }

    private void seedRandoms(long seed) throws Exception {
        java.lang.reflect.Field randField = TestDeckLoader.class.getDeclaredField("random");
        randField.setAccessible(true);
        ((java.util.Random) randField.get(null)).setSeed(seed);
        forge.util.MyRandom.setRandom(new java.util.Random(seed));
    }

    private void printRealGamesReport(List<RealGameResult> results, long batchNanos) {
        System.out.println();
        System.out.println("=== REAL-GAMES RESULTS ===");
        long sumMs = 0;
        int sumTurns = 0;
        int sumBoard = 0;
        int okCount = 0;
        for (RealGameResult r : results) {
            sumMs += r.gameMs;
            sumTurns += r.turns;
            if (r.totalBoard > 0) sumBoard += r.totalBoard;
            if ("ok".equals(r.status)) okCount++;
        }
        int n = results.size();
        System.out.printf("  Games: %d  (ok: %d, non-ok: %d)%n", n, okCount, n - okCount);
        System.out.printf("  Batch wall: %.1fs  (avg per game: %.1fs)%n",
            batchNanos / 1e9, n > 0 ? batchNanos / 1e9 / n : 0.0);
        System.out.printf("  Avg game length: %d turns, final board: %d permanents%n",
            n > 0 ? sumTurns / n : 0, n > 0 ? sumBoard / n : 0);
        System.out.println();
        System.out.println("  Render flame graph:");
        System.out.println("    pushd target && java ../.claude/tools/JfrFlameGraph.java perf/realgames-*.jfr && popd");
    }
}
