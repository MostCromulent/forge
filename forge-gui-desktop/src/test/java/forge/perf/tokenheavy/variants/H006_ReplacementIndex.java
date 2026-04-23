package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * H006 production variant: ReplacementHandler.getReplacementList iterates
 * game.getCardsWithReplacements() — a precomputed index populated via
 * Card.addReplacementEffect — instead of forEachCardInGame. Skips ~95% of
 * the iteration in the common case where most cards (lands, tokens,
 * vanilla creatures) contribute no replacement effects.
 *
 * Known limitation: the index only captures cards registered via the direct
 * addReplacementEffect path. Replacement effects derived dynamically from
 * keywords or card-trait changes (e.g. aura-granted "when ~ dies, exile")
 * are not in the index; cards holding only those kinds of effects will be
 * silently missed. Verify variant H006_ReplacementIndex_Verify measures
 * the impact of this gap empirically.
 */
public final class H006_ReplacementIndex extends OptimizationContext {
    @Override
    public boolean useReplacementIndexFastPath() { return true; }
}
