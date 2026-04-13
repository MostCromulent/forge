package forge.ai;

import java.util.List;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

import org.testng.annotations.Test;

/**
 * Profiling test for AiCombatCache. Runs AI combat decisions with large boards
 * and reports wall-clock timing. The cache mode is controlled by the system
 * property {@code -Dai.cache=disabled|enabled}.
 *
 * <p>Profiling workflow:
 * <pre>
 * # Baseline (no caching):
 * mvn -pl forge-gui-desktop -am verify -Dtest="AiCombatCacheProfileTest" \
 *     -Drun.stress.tests=true -Dai.cache=disabled -Dai.instrument=true
 *
 * # Optimized (caching enabled):
 * mvn -pl forge-gui-desktop -am verify -Dtest="AiCombatCacheProfileTest" \
 *     -Drun.stress.tests=true -Dai.cache=enabled -Dai.instrument=true
 *
 * # With JFR flame graph (add to MAVEN_OPTS):
 * export MAVEN_OPTS="-XX:StartFlightRecording=filename=target/profile.jfr,dumponexit=true"
 * </pre>
 */
public class AiCombatCacheProfileTest extends AITest {

    private static void skipUnlessStressTestsEnabled() {
        if (!"true".equalsIgnoreCase(System.getProperty("run.stress.tests"))) {
            throw new org.testng.SkipException("Stress tests skipped. Use -Drun.stress.tests=true to run.");
        }
    }

    @Test
    public void profileAttackDecisionWithTokens() {
        skipUnlessStressTestsEnabled();

        final int ITERATIONS = 20;
        final int TOKEN_COUNT = 20;
        final int BLOCKER_COUNT = 10;
        final AiCacheMode mode = AiCacheMode.get();

        System.out.println("\n=== AI Combat Cache Profile: Attack Decision ===");
        System.out.println("Mode: " + mode + ", Tokens: " + TOKEN_COUNT
                + ", Blockers: " + BLOCKER_COUNT + ", Iterations: " + ITERATIONS);

        // Warm up
        runAttackDecision(TOKEN_COUNT, BLOCKER_COUNT);

        long total = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            total += runAttackDecision(TOKEN_COUNT, BLOCKER_COUNT);
        }

        double avgMs = total / (double) ITERATIONS / 1_000_000.0;
        System.out.printf("  Mode %s avg: %.1fms per decision%n%n", mode, avgMs);
    }

    @Test
    public void profileBlockDecisionWithTokens() {
        skipUnlessStressTestsEnabled();

        final int ITERATIONS = 20;
        final int ATTACKER_COUNT = 10;
        final int BLOCKER_TOKEN_COUNT = 20;
        final AiCacheMode mode = AiCacheMode.get();

        System.out.println("\n=== AI Combat Cache Profile: Block Decision ===");
        System.out.println("Mode: " + mode + ", Attackers: " + ATTACKER_COUNT
                + ", Blocker tokens: " + BLOCKER_TOKEN_COUNT + ", Iterations: " + ITERATIONS);

        // Warm up
        runBlockDecision(ATTACKER_COUNT, BLOCKER_TOKEN_COUNT);

        long total = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            total += runBlockDecision(ATTACKER_COUNT, BLOCKER_TOKEN_COUNT);
        }

        double avgMs = total / (double) ITERATIONS / 1_000_000.0;
        System.out.printf("  Mode %s avg: %.1fms per decision%n%n", mode, avgMs);
    }

    @Test
    public void profileLargeTokenBoard() {
        skipUnlessStressTestsEnabled();

        final int ITERATIONS = 10;
        final int TOKEN_COUNT = 40;
        final int BLOCKER_COUNT = 15;
        final AiCacheMode mode = AiCacheMode.get();

        System.out.println("\n=== AI Combat Cache Profile: Large Board (40 tokens) ===");
        System.out.println("Mode: " + mode + ", Tokens: " + TOKEN_COUNT
                + ", Blockers: " + BLOCKER_COUNT + ", Iterations: " + ITERATIONS);

        // Warm up
        runAttackDecision(TOKEN_COUNT, BLOCKER_COUNT);

        long total = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            total += runAttackDecision(TOKEN_COUNT, BLOCKER_COUNT);
        }

        double avgMs = total / (double) ITERATIONS / 1_000_000.0;
        System.out.printf("  Mode %s avg: %.1fms per decision%n%n", mode, avgMs);
    }

    @Test
    public void profileSimCommandInfo() {
        skipUnlessStressTestsEnabled();

        System.out.println("\n=== Full-game profiling with sim command ===");
        System.out.println("For full-game profiling, use the built-in sim command:");
        System.out.println("  java -Dai.cache=disabled -Dai.instrument=true -jar forge.jar sim -d deck1 deck2 -n 50 -q");
        System.out.println("  java -Dai.cache=enabled  -Dai.instrument=true -jar forge.jar sim -d deck1 deck2 -n 50 -q");
        System.out.println("Compare instrumentation output between runs.");
        System.out.println("For flame graphs, add: -XX:StartFlightRecording=filename=target/profile.jfr,dumponexit=true");
        System.out.println("Then: pushd target && java ../.claude/tools/JfrFlameGraph.java --diff baseline.jfr optimized.jfr && popd");
    }

    private long runAttackDecision(int tokenCount, int blockerCount) {
        Game game = initAndCreateGame();
        Player attacker = game.getPlayers().get(1);
        Player defender = game.getPlayers().get(0);

        defender.setLife(40, null);

        List<Card> tokens = addCards("Grizzly Bears", tokenCount, attacker);
        for (Card c : tokens) c.setSickness(false);

        List<Card> blockers = addCards("Hill Giant", blockerCount, defender);
        for (Card c : blockers) c.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, attacker);
        game.getAction().checkStateEffects(true);

        long start = System.nanoTime();
        ((PlayerControllerAi) attacker.getController()).getAi().getPredictedCombat();
        return System.nanoTime() - start;
    }

    private long runBlockDecision(int attackerCount, int blockerTokenCount) {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(1);
        Player p2 = game.getPlayers().get(0);

        p1.setLife(20, null);
        p2.setLife(20, null);

        List<Card> blockerTokens = addCards("Grizzly Bears", blockerTokenCount, p1);
        for (Card c : blockerTokens) c.setSickness(false);

        List<Card> attackers = addCards("Hill Giant", attackerCount, p2);
        for (Card c : attackers) c.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, p2);
        game.getAction().checkStateEffects(true);

        Combat combat = new Combat(p2);
        for (Card a : attackers) {
            combat.addAttacker(a, p1);
        }

        long start = System.nanoTime();
        AiBlockController block = new AiBlockController(p1, false);
        block.assignBlockersForCombat(combat);
        return System.nanoTime() - start;
    }
}
