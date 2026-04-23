package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * H020 production: when a card has no replacement-effect source (no
 * intrinsic REs, no RE-keywords, no trait overlays, no split sides, no
 * RE-producing type/subtype, no RE-producing counters), return a shared
 * empty FCollectionView from CardState.getReplacementEffects instead of
 * allocating a fresh FCollection and running the full method body.
 *
 * Empirically validated via H020_verify: zero divergences across 6 real
 * games (batch seed 0xF0D6L). The condition captures every known way a
 * card can gain a replacement effect.
 */
public final class H020_GetReplacementEffectsFastPath extends OptimizationContext {
    @Override public boolean useBinarySearchNotNeeded() { return true; }
    @Override public boolean useEmptyOverlayFastPath() { return true; }
    @Override public boolean useHasRemoveIntrinsicFastPath() { return true; }
    @Override public boolean useParsedRestrictionCache() { return true; }
    @Override public boolean useKeywordTraitBitmask() { return true; }
    @Override public boolean useGetReplacementEffectsFastPath() { return true; }
}
