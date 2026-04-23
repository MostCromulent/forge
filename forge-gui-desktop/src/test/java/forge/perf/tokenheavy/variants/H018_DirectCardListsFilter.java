package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * H018 on top of H003+H013+H014: replace CardLists.filter Stream
 * wrapper with a direct for-loop. Every CardLists.filter call
 * currently allocates a Stream via IterableUtil.filter before
 * materializing into a CardCollection — samples show this path at
 * 9.21% of in-game self-time (attributed to FCollection.addAll but
 * actually dominated by the Stream pipeline setup and traversal).
 *
 * The plain loop variant iterates cardList directly, applying the
 * predicate per element and adding matches to a fresh CardCollection.
 * Semantically identical, no allocation overhead.
 */
public final class H018_DirectCardListsFilter extends OptimizationContext {
    @Override public boolean useBinarySearchNotNeeded() { return true; }
    @Override public boolean useEmptyOverlayFastPath() { return true; }
    @Override public boolean useHasRemoveIntrinsicFastPath() { return true; }
    @Override public boolean useDirectCardListsFilter() { return true; }
}
