package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

import java.util.concurrent.atomic.AtomicLong;

/**
 * H006 verification variant: at each getReplacementList call, runs BOTH
 * paths (full forEachCardInGame and indexed) and compares their result
 * lists. Game runs under baseline semantics (full-iteration result
 * governs). Any divergence means the index missed a card — indicates a
 * dynamic RE source (aura/trait-change) we haven't hooked.
 */
public final class H006_ReplacementIndex_Verify extends OptimizationContext {
    private static final AtomicLong DIVERGENCES = new AtomicLong();

    @Override
    public boolean verifyReplacementIndex() { return true; }

    @Override
    public void reportReplacementIndexDivergence(String description) {
        long n = DIVERGENCES.incrementAndGet();
        if (n <= 10) {
            System.err.printf("[H006 verify] replacement-index divergence #%d: %s%n", n, description);
        } else if (n == 11) {
            System.err.println("[H006 verify] further divergences suppressed; see total at end");
        }
    }

    public static long divergenceCount() { return DIVERGENCES.get(); }
    public static void resetCounter() { DIVERGENCES.set(0); }
}
