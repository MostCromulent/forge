package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-decision empirical verify for H014. On every hasRemoveIntrinsic
 * call, runs both the isEmpty-check path and the full IterableUtil.any
 * path and reports any case where the fast path would have returned
 * false while the slow path returned true.
 */
public final class H014_HasRemoveIntrinsic_PerDecisionVerify extends OptimizationContext {
    private static final AtomicLong DIVERGENCES = new AtomicLong();

    @Override public boolean verifyHasRemoveIntrinsicFastPath() { return true; }

    @Override
    public void reportHasRemoveIntrinsicDivergence(forge.game.card.Card card) {
        long n = DIVERGENCES.incrementAndGet();
        if (n <= 10) {
            System.err.printf("[H014 verify] divergence #%d: card=%s%n",
                n, card == null ? "null" : card.getName());
        } else if (n == 11) {
            System.err.println("[H014 verify] further divergences suppressed");
        }
    }

    public static long divergenceCount() { return DIVERGENCES.get(); }
}
