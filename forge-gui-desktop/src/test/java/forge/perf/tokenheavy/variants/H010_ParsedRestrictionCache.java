package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * H010 on top of H003+H013+H014: cache the parsed structure of isValid
 * restriction strings and the comma-split in getValidCards. Restriction
 * strings come from card scripts (immutable), so parse-once is a pure-
 * function memoization with no correctness risk.
 *
 * Targets ~2.6% self-time in String.split seen in the baseline flame
 * graph (still ~1.3-1.5% post-H014). getValidCards -> CardLists.filter
 * cascade means the savings propagate through every predicate-filter
 * hot path.
 */
public final class H010_ParsedRestrictionCache extends OptimizationContext {
    @Override public boolean useBinarySearchNotNeeded() { return true; }
    @Override public boolean useEmptyOverlayFastPath() { return true; }
    @Override public boolean useHasRemoveIntrinsicFastPath() { return true; }
    @Override public boolean useParsedRestrictionCache() { return true; }
}
