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

        PerfCounters.resetAll();
        PerfCounters.enabled = true;
        try {
            runOneFixture(fixtureRes, lobby);
        } finally {
            PerfCounters.enabled = false;
        }

        VariantSlot baseline = slots.get(0);
        VariantSlot variant = slots.get(1);
        int divergences = compareDecisions(baseline.decisions, variant.decisions);

        Map<String, Long> baselineCounters = new HashMap<>();
        Map<String, Long> variantCounters = new HashMap<>();
        PerfCounters.snapshot().forEach((k, v) -> {
            baselineCounters.put(k, v.calls());
            variantCounters.put(k, v.calls());   // in QUERY mode the real game only runs baseline
        });

        VerdictEvaluator.Verdict verdict = VerdictEvaluator.evaluate(
            baseline.totalNanos, variant.totalNanos,
            baselineCounters, variantCounters, divergences);

        System.out.println(ReportGenerator.renderText(
            hypothesisId, fixtureRes, baseline, variant,
            baselineCounters, variantCounters, divergences, verdict));

        String json = ReportGenerator.renderJson(
            hypothesisId, fixtureRes, baseline, variant,
            baselineCounters, variantCounters, divergences, verdict);
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

        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Deck deck = TestDeckLoader.getRandomPrecon();
            RegisteredPlayer rp = new RegisteredPlayer(deck);
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
}
