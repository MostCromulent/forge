package forge.perf.tokenheavy.variants;

import forge.game.card.Card;
import forge.game.perf.OptimizationContext;
import forge.game.perf.ShouldAttackCache;

import java.util.concurrent.atomic.AtomicLong;

/**
 * H005 verification variant: populates the cache and also recomputes fresh
 * every call, comparing the cached value against the fresh result. Game
 * proceeds under baseline semantics (fresh result governs). Used for Phase 1
 * correctness validation before Phase 2 perf measurement with the production
 * variant.
 */
public final class H005_ShouldAttackCache_Verify extends OptimizationContext {
    private final ShouldAttackCache cache = new ShouldAttackCache();
    private static final AtomicLong DIVERGENCES = new AtomicLong();

    @Override
    public ShouldAttackCache shouldAttackCache() { return cache; }

    @Override
    public boolean verifyShouldAttack() { return true; }

    @Override
    public void reportShouldAttackDivergence(Card attacker, boolean cachedResult, boolean freshResult) {
        long n = DIVERGENCES.incrementAndGet();
        if (n <= 10) {
            System.err.printf("[H005 verify] shouldAttack divergence #%d: attacker=%s(%d) cached=%s fresh=%s%n",
                n,
                attacker == null ? "null" : attacker.getName(),
                attacker == null ? -1 : attacker.getId(),
                cachedResult, freshResult);
        } else if (n == 11) {
            System.err.println("[H005 verify] further divergences suppressed; see DIVERGENCES total at end");
        }
    }

    public static long divergenceCount() { return DIVERGENCES.get(); }
    public static void resetCounter() { DIVERGENCES.set(0); }

    public ShouldAttackCache getCacheForReport() { return cache; }
}
