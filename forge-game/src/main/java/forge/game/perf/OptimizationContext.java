package forge.game.perf;

/**
 * Thread-local optimization context read by hot paths. Production code uses
 * {@link #BASELINE} which preserves current behaviour. Variants subclass and
 * override individual strategy methods. See the testbed spec at
 * .claude/superpowers/specs/2026-04-23-perf-hypothesis-testbed-design.md
 */
public class OptimizationContext {
    public static final OptimizationContext BASELINE = new OptimizationContext();
    private static final ThreadLocal<OptimizationContext> CURRENT =
        ThreadLocal.withInitial(() -> BASELINE);

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
}
