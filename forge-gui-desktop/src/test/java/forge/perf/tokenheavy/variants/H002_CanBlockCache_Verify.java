package forge.perf.tokenheavy.variants;

import forge.game.card.Card;
import forge.game.perf.CanBlockCache;
import forge.game.perf.OptimizationContext;

import java.util.concurrent.atomic.AtomicLong;

/**
 * H002 verification variant: populates the cache on every canBlock call but
 * ALSO recomputes fresh and compares. Divergences are logged; the game runs
 * under baseline semantics (fresh result governs). Used for Phase 1
 * correctness validation before Phase 2 perf measurement with the production
 * variant.
 */
public final class H002_CanBlockCache_Verify extends OptimizationContext {
    // Shared instance (game runs single-threaded on Game-0; no contention).
    // Shared scope lets the test driver read cache stats post-run.
    private final CanBlockCache cache = new CanBlockCache();
    private static final AtomicLong DIVERGENCES = new AtomicLong();
    private static final AtomicLong CHECKS = new AtomicLong();

    @Override
    public CanBlockCache canBlockCache() { return cache; }

    @Override
    public boolean verifyCanBlock() { return true; }

    @Override
    public void reportCanBlockDivergence(Card attacker, Card blocker,
                                         boolean cachedResult, boolean freshResult) {
        long n = DIVERGENCES.incrementAndGet();
        if (n <= 10) {
            System.err.printf("[H002 verify] canBlock divergence #%d: attacker=%s(%d) blocker=%s(%d) cached=%s fresh=%s%n",
                n,
                attacker == null ? "null" : attacker.getName(),
                attacker == null ? -1 : attacker.getId(),
                blocker == null ? "null" : blocker.getName(),
                blocker == null ? -1 : blocker.getId(),
                cachedResult, freshResult);
        } else if (n == 11) {
            System.err.println("[H002 verify] further divergences suppressed; see DIVERGENCES total at end");
        }
    }

    public static long divergenceCount() { return DIVERGENCES.get(); }
    public static long checkCount() { return CHECKS.get(); }
    public static void resetCounters() { DIVERGENCES.set(0); CHECKS.set(0); }

    public CanBlockCache getCacheForReport() { return cache; }
}
