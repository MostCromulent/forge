package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * Full production stack with H019 (keyword-trait bitmask loop-skip)
 * DISABLED, in response to the per-decision verify finding that H019's
 * cached bitmask can go stale relative to keyword instances mutated
 * in-place by CardFactoryUtil.setupKeywordedAbilities during split-card
 * initialization. H020/H020b/H020c still work (they bail on split cards
 * before consulting the bitmask). This is the six-optimisation stack
 * that is empirically safe for PR.
 */
public final class SafeStack_MinusH019 extends OptimizationContext {
    @Override public boolean useBinarySearchNotNeeded() { return true; }
    @Override public boolean useEmptyOverlayFastPath() { return true; }
    @Override public boolean useHasRemoveIntrinsicFastPath() { return true; }
    @Override public boolean useParsedRestrictionCache() { return true; }
    // H019 DISABLED — keyword-trait bitmask not used for loop-skip
    @Override public boolean useKeywordTraitBitmask() { return false; }
    @Override public boolean useGetReplacementEffectsFastPath() { return true; }
    @Override public boolean useGetStaticAbilitiesFastPath() { return true; }
    @Override public boolean useGetTriggersFastPath() { return true; }
}
