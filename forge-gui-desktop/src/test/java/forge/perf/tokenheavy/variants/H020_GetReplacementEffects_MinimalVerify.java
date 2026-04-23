package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal-gate re-verify of H020. Unlike the original H020_Verify variant
 * (which ran all prior production gates), this variant ONLY enables the
 * verify flag for getReplacementEffects — so the "slow path" reference is
 * pure baseline, not a fast-path-adjusted baseline. Addresses the MED-5
 * confound from the adversarial review: if any of the prior H-stack flags
 * had a silent divergence masking REs, the original verify would fail to
 * detect it. This minimal-gate re-verify eliminates that confound.
 */
public final class H020_GetReplacementEffects_MinimalVerify extends OptimizationContext {
    private static final AtomicLong DIVERGENCES = new AtomicLong();

    @Override public boolean verifyGetReplacementEffectsFastPath() { return true; }

    @Override
    public void reportGetReplacementEffectsDivergence(forge.game.card.Card card, String reason) {
        long n = DIVERGENCES.incrementAndGet();
        if (n <= 10) {
            System.err.printf("[H020 minimal-verify] divergence #%d: card=%s reason=%s%n",
                n, card == null ? "null" : card.getName(), reason);
        } else if (n == 11) {
            System.err.println("[H020 minimal-verify] further divergences suppressed");
        }
    }

    public static long divergenceCount() { return DIVERGENCES.get(); }
}
