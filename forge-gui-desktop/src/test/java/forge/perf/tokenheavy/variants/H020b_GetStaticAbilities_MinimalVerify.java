package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal-gate re-verify of H020b (getStaticAbilities fast path). See
 * H020_GetReplacementEffects_MinimalVerify for rationale.
 */
public final class H020b_GetStaticAbilities_MinimalVerify extends OptimizationContext {
    private static final AtomicLong DIVERGENCES = new AtomicLong();

    @Override public boolean verifyGetStaticAbilitiesFastPath() { return true; }

    @Override
    public void reportGetStaticAbilitiesDivergence(forge.game.card.Card card, String reason) {
        long n = DIVERGENCES.incrementAndGet();
        if (n <= 10) {
            System.err.printf("[H020b minimal-verify] divergence #%d: card=%s reason=%s%n",
                n, card == null ? "null" : card.getName(), reason);
        } else if (n == 11) {
            System.err.println("[H020b minimal-verify] further divergences suppressed");
        }
    }

    public static long divergenceCount() { return DIVERGENCES.get(); }
}
