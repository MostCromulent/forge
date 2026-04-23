package forge.perf.tokenheavy.variants;

import forge.game.perf.CostCache;
import forge.game.perf.OptimizationContext;
import forge.game.spellability.SpellAbility;

import java.util.concurrent.atomic.AtomicLong;

public final class H007_CostCache_Verify extends OptimizationContext {
    private final CostCache cache = new CostCache();
    private static final AtomicLong DIVERGENCES = new AtomicLong();

    @Override
    public CostCache costCache() { return cache; }

    @Override
    public boolean verifyCostCache() { return true; }

    @Override
    public void reportCostCacheDivergence(SpellAbility sa, boolean cachedResult, boolean freshResult) {
        long n = DIVERGENCES.incrementAndGet();
        if (n <= 10) {
            System.err.printf("[H007 verify] canPayCost divergence #%d: sa=%s host=%s cached=%s fresh=%s%n",
                n,
                sa == null ? "null" : sa.getDescription(),
                sa == null || sa.getHostCard() == null ? "null" : sa.getHostCard().getName(),
                cachedResult, freshResult);
        } else if (n == 11) {
            System.err.println("[H007 verify] further divergences suppressed");
        }
    }

    public static long divergenceCount() { return DIVERGENCES.get(); }

    public CostCache getCacheForReport() { return cache; }
}
