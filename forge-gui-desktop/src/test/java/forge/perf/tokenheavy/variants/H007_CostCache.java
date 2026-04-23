package forge.perf.tokenheavy.variants;

import forge.game.perf.CostCache;
import forge.game.perf.OptimizationContext;

/**
 * H007 production variant: cache ComputerUtilCost.canPayCost results
 * within a single chooseSpellAbilityToPlay call. Key includes host-card
 * name, ability description, cost string, effect flag, and a mana-pool
 * fingerprint (life + mana pool + untapped mana sources).
 *
 * Two SAs with identical cost and identical host-card type collapse to the
 * same cache entry, so 40 Faerie Dragons with the same activated ability
 * do 1 solve instead of 40.
 */
public final class H007_CostCache extends OptimizationContext {
    private final CostCache cache = new CostCache();

    @Override
    public CostCache costCache() { return cache; }
}
