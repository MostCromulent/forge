package forge.game.perf;

/**
 * Thread-local optimization context read by hot paths. Production code uses
 * {@link #BASELINE} which preserves current behaviour. Variants subclass and
 * override individual strategy methods. See the testbed spec at
 * .claude/superpowers/specs/2026-04-23-perf-hypothesis-testbed-design.md
 */
public class OptimizationContext {
    public static final OptimizationContext BASELINE = new OptimizationContext();
    // InheritableThreadLocal so child threads (e.g. game threads spawned via
    // TimeLimitedCodeBlock's single-thread executor) inherit the context that
    // was set on the parent test thread before the child was created.
    private static final ThreadLocal<OptimizationContext> CURRENT =
        new InheritableThreadLocal<OptimizationContext>() {
            @Override protected OptimizationContext initialValue() { return BASELINE; }
        };

    public static OptimizationContext current() { return CURRENT.get(); }
    public static void set(OptimizationContext ctx) { CURRENT.set(ctx == null ? BASELINE : ctx); }
    public static void reset() { CURRENT.set(BASELINE); }

    // Strategy read points are added incrementally by subsequent hypotheses.
    // Baseline implementations return input unchanged / absent / null.

    /**
     * Extension point for hypothesis H001+: filter duplicate battlefield tokens
     * out of the dedupeCards result. Baseline returns the input unchanged.
     * Variants override to return a smaller collection.
     */
    public forge.game.card.CardCollection filterDuplicateBattlefieldTokens(
            forge.game.card.CardCollection cards) {
        return cards;
    }

    // H002: CombatUtil.canBlock cache. Null = no cache (baseline behaviour).
    public CanBlockCache canBlockCache() { return null; }

    // H002 verify mode: if true, canBlock computes fresh every call and
    // compares against cache; divergences logged via reportCanBlockDivergence.
    // Game proceeds under baseline semantics (fresh result governs).
    public boolean verifyCanBlock() { return false; }

    public void reportCanBlockDivergence(forge.game.card.Card attacker,
                                         forge.game.card.Card blocker,
                                         boolean cachedResult,
                                         boolean freshResult) {
        // Baseline: no-op. Verify variants override to log.
    }
}
