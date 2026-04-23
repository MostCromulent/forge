package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;
import forge.game.perf.ShouldAttackCache;

/**
 * H005 production variant: cache shouldAttack results within a single
 * declareAttackers call, keyed by (attacker-equivalence, defender-id).
 * Engaged only when countExaltedBonus(ai) == 0 at the read-site (the guard
 * lives in AiAttackController.shouldAttack itself, not here).
 *
 * Shared instance — game runs single-threaded. Per-decision scope via
 * enter/exitDecision pair in AiAttackController.declareAttackers.
 */
public final class H005_ShouldAttackCache extends OptimizationContext {
    private final ShouldAttackCache cache = new ShouldAttackCache();

    @Override
    public ShouldAttackCache shouldAttackCache() { return cache; }
}
