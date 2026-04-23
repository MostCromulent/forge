package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H020b + H020c verify: check the fast-path condition on
 * CardState.getStaticAbilities and getTriggers, but always run the
 * slow path and record divergences. Same methodology as H020's
 * verify that validated getReplacementEffects cleanly.
 *
 * Structure is simpler than H020 — statics and triggers have no
 * synthetic type/counter/subtype sources; the fast-path condition
 * only needs: intrinsic empty + no split + no RE-keywords + no
 * overlays.
 */
public final class H020bc_StaticsAndTriggers_Verify extends OptimizationContext {
    private static final AtomicLong STATIC_DIVERGENCES = new AtomicLong();
    private static final AtomicLong TRIGGER_DIVERGENCES = new AtomicLong();
    private static final ConcurrentHashMap<String, Long> REASONS = new ConcurrentHashMap<>();

    @Override public boolean useBinarySearchNotNeeded() { return true; }
    @Override public boolean useEmptyOverlayFastPath() { return true; }
    @Override public boolean useHasRemoveIntrinsicFastPath() { return true; }
    @Override public boolean useParsedRestrictionCache() { return true; }
    @Override public boolean useKeywordTraitBitmask() { return true; }
    @Override public boolean useGetReplacementEffectsFastPath() { return true; }

    @Override public boolean verifyGetStaticAbilitiesFastPath() { return true; }
    @Override public boolean verifyGetTriggersFastPath() { return true; }

    @Override
    public void reportGetStaticAbilitiesDivergence(forge.game.card.Card card, String reason) {
        long n = STATIC_DIVERGENCES.incrementAndGet();
        if (n <= 10) {
            System.err.printf("[H020b verify] static divergence #%d: card=%s reason=%s%n",
                n, card == null ? "null" : card.getName(), reason);
        } else if (n == 11) {
            System.err.println("[H020b verify] further static divergences suppressed");
        }
        REASONS.merge("static:" + reason, 1L, Long::sum);
    }

    @Override
    public void reportGetTriggersDivergence(forge.game.card.Card card, String reason) {
        long n = TRIGGER_DIVERGENCES.incrementAndGet();
        if (n <= 10) {
            System.err.printf("[H020c verify] trigger divergence #%d: card=%s reason=%s%n",
                n, card == null ? "null" : card.getName(), reason);
        } else if (n == 11) {
            System.err.println("[H020c verify] further trigger divergences suppressed");
        }
        REASONS.merge("trigger:" + reason, 1L, Long::sum);
    }

    public static long staticDivergenceCount() { return STATIC_DIVERGENCES.get(); }
    public static long triggerDivergenceCount() { return TRIGGER_DIVERGENCES.get(); }
}
