package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * H020d: eagerly compute the KeywordCollection trait bitmask at the
 * end of Card.updateKeywordsCache. Moves the O(N) walk from first-query
 * time (paid many times across getReplacementEffects / getStaticAbilities
 * / getTriggers calls) to cache-build time (paid once per rebuild).
 *
 * Attacks the ~12% self-time attributed to hasReplacementEffectKeyword +
 * hasStaticAbilityKeyword in the post-H020bc flame graph.
 */
public final class H020d_EagerKeywordTraitFlags extends OptimizationContext {
    @Override public boolean useBinarySearchNotNeeded() { return true; }
    @Override public boolean useEmptyOverlayFastPath() { return true; }
    @Override public boolean useHasRemoveIntrinsicFastPath() { return true; }
    @Override public boolean useParsedRestrictionCache() { return true; }
    @Override public boolean useKeywordTraitBitmask() { return true; }
    @Override public boolean useGetReplacementEffectsFastPath() { return true; }
    @Override public boolean useGetStaticAbilitiesFastPath() { return true; }
    @Override public boolean useGetTriggersFastPath() { return true; }
    @Override public boolean useEagerKeywordTraitFlags() { return true; }
}
