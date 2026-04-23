package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * H020b + H020c production: fast-path empty-return on
 * CardState.getStaticAbilities and getTriggers when the card has no
 * possible source (intrinsic empty, no split sides, no trait overlays,
 * no trait-contributing keywords). Empirically validated via
 * H020bc_verify: zero divergences across 10 real games.
 *
 * Stacks on H003 + H013 + H014 + H010 + H019 + H020.
 */
public final class H020bc_StaticsAndTriggers extends OptimizationContext {
    @Override public boolean useBinarySearchNotNeeded() { return true; }
    @Override public boolean useEmptyOverlayFastPath() { return true; }
    @Override public boolean useHasRemoveIntrinsicFastPath() { return true; }
    @Override public boolean useParsedRestrictionCache() { return true; }
    @Override public boolean useKeywordTraitBitmask() { return true; }
    @Override public boolean useGetReplacementEffectsFastPath() { return true; }
    @Override public boolean useGetStaticAbilitiesFastPath() { return true; }
    @Override public boolean useGetTriggersFastPath() { return true; }
}
