package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-decision empirical verify for H019. In Card.update{Static,Trigger,
 * Replacement}Effects, checks the bitmask's claim by running the keyword
 * loop anyway and reports any case where the bitmask said "empty" but
 * the loop still added to the list. If zero divergences across many
 * calls, the bitmask is proven correct for real workloads.
 */
public final class H019_KeywordBitmask_PerDecisionVerify extends OptimizationContext {
    private static final AtomicLong STATIC_DIV = new AtomicLong();
    private static final AtomicLong TRIGGER_DIV = new AtomicLong();
    private static final AtomicLong REPLACEMENT_DIV = new AtomicLong();

    @Override public boolean verifyKeywordTraitBitmask() { return true; }

    @Override
    public void reportKeywordBitmaskDivergence(forge.game.card.Card card, String kind, int added) {
        long n;
        switch (kind) {
            case "static": n = STATIC_DIV.incrementAndGet(); break;
            case "trigger": n = TRIGGER_DIV.incrementAndGet(); break;
            case "replacement": n = REPLACEMENT_DIV.incrementAndGet(); break;
            default: return;
        }
        if (n <= 10) {
            System.err.printf("[H019 verify] %s divergence #%d: card=%s added=%d (bitmask said empty)%n",
                kind, n, card == null ? "null" : card.getName(), added);
        } else if (n == 11) {
            System.err.printf("[H019 verify] further %s divergences suppressed%n", kind);
        }
    }

    public static long staticDivergenceCount() { return STATIC_DIV.get(); }
    public static long triggerDivergenceCount() { return TRIGGER_DIV.get(); }
    public static long replacementDivergenceCount() { return REPLACEMENT_DIV.get(); }
}
