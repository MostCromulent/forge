package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * H014 on top of H003+H013: short-circuit Card.hasRemoveIntrinsic when
 * all three changedCardTypes TreeBasedTables are empty, skipping the
 * IterableUtil.any Stream allocation for the common case. This method
 * was the new #2 hot path (~9% of in-game self-time, 97% from this
 * caller) in the H003+H013 flame graph.
 */
public final class H014_HasRemoveIntrinsicFastPath extends OptimizationContext {
    @Override public boolean useBinarySearchNotNeeded() { return true; }
    @Override public boolean useEmptyOverlayFastPath() { return true; }
    @Override public boolean useHasRemoveIntrinsicFastPath() { return true; }
}
