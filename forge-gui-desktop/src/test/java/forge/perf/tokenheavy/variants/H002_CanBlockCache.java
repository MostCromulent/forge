package forge.perf.tokenheavy.variants;

import forge.game.perf.CanBlockCache;
import forge.game.perf.OptimizationContext;

/**
 * H002 production variant: cache CombatUtil.canBlock(attacker, blocker, combat)
 * results within a single Combat object's lifetime. Collapses redundant canBlock
 * evaluations across AiBlockController, AiAttackController, and
 * predictNextCombatsRemainingLife — all of which call this primitive repeatedly
 * with the same (attacker, blocker) pairs.
 *
 * Validated for correctness by H002_CanBlockCache_Verify (Phase 1) before
 * using this variant for perf measurement (Phase 2).
 */
public final class H002_CanBlockCache extends OptimizationContext {
    // Shared instance (game runs single-threaded on Game-0). Per-Combat scope
    // in the cache itself handles invalidation across combat phases / turns.
    private final CanBlockCache cache = new CanBlockCache();

    @Override
    public CanBlockCache canBlockCache() { return cache; }
}
