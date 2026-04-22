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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Test(groups = "stress")
public class TokenPerfTest {

    private static final int TIMEOUT_SECONDS = 120;
    private static final String DEFAULT_FIXTURE =
        "perf/fixtures/tokenheavy/empty-board-commander.txt";

    @BeforeClass
    public void setup() { TestUtils.ensureFModelInitialized(); }

    @Test(description = "Run via -Dhypothesis=H000_Noop (or H<id>_<name>); default fixture empty-board")
    public void runHypothesis() throws Exception {
        String hypothesisId = System.getProperty("hypothesis", "H000_Noop");
        String fixtureRes = System.getProperty("fixture", DEFAULT_FIXTURE);

        OptimizationContext variantCtx = loadVariant(hypothesisId);

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
        String branch = System.getProperty("branch", "unknown");
        HypothesisLog.appendJsonl(
            Path.of(".claude", "notes", branch, "hypotheses.jsonl"), json);
    }

    private OptimizationContext loadVariant(String hypothesisId) throws Exception {
        if ("BASELINE".equalsIgnoreCase(hypothesisId)) return OptimizationContext.BASELINE;
        String fqn = "forge.perf.tokenheavy.variants." + hypothesisId;
        Class<?> cls = Class.forName(fqn);
        return (OptimizationContext) cls.getDeclaredConstructor().newInstance();
    }

    private void runOneFixture(String fixtureRes, LobbyPlayerInstrumented lobby) throws Exception {
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

        try {
            forge.view.TimeLimitedCodeBlock.runWithTimeout(
                () -> match.startGame(game), TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ignored) {
            // OK — short fixtures time out by design; we measure decisions made so far.
        } catch (Exception | StackOverflowError e) {
            System.err.println("Harness error: " + e.getMessage());
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
}
