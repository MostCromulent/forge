package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H020 verify: check the fast-path condition on CardState.getReplacementEffects
 * but always run the slow path and record divergences. Used to empirically
 * validate the condition captures every mechanism by which a card can gain
 * a replacement effect (intrinsic, keyword-granted, overlay-granted,
 * type-synthetic, counter-synthetic, split-side).
 *
 * Stacks on top of the validated H003+H013+H014+H019 stack + H010 cache so
 * we measure in realistic post-optimization conditions.
 */
public final class H020_GetReplacementEffectsFastPath_Verify extends OptimizationContext {
    private static final AtomicLong DIVERGENCES = new AtomicLong();
    private static final ConcurrentHashMap<String, Long> REASONS = new ConcurrentHashMap<>();

    @Override public boolean useBinarySearchNotNeeded() { return true; }
    @Override public boolean useEmptyOverlayFastPath() { return true; }
    @Override public boolean useHasRemoveIntrinsicFastPath() { return true; }
    @Override public boolean useParsedRestrictionCache() { return true; }
    @Override public boolean useKeywordTraitBitmask() { return true; }

    @Override public boolean verifyGetReplacementEffectsFastPath() { return true; }

    @Override
    public void reportGetReplacementEffectsDivergence(forge.game.card.Card card, String reason) {
        long n = DIVERGENCES.incrementAndGet();
        if (n <= 10) {
            System.err.printf("[H020 verify] divergence #%d: card=%s reason=%s%n",
                n,
                card == null ? "null" : card.getName(),
                reason);
        } else if (n == 11) {
            System.err.println("[H020 verify] further divergences suppressed (still counted)");
        }
        REASONS.merge(reason, 1L, Long::sum);
    }

    public static long divergenceCount() { return DIVERGENCES.get(); }
    public static ConcurrentHashMap<String, Long> reasons() { return REASONS; }
}
