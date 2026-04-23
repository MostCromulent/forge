package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;
import forge.util.IterableUtil;

/**
 * H003 + H013 + H014 + H016 combined. H016 rewrites IterableUtil.any/all
 * as plain for-loops (set via the static USE_FOR_LOOP flag on
 * IterableUtil since that class lives in forge-core and can't depend on
 * OptimizationContext directly).
 */
public final class H016_All_Combined extends OptimizationContext {
    public H016_All_Combined() {
        IterableUtil.USE_FOR_LOOP = true;
    }

    @Override public boolean useBinarySearchNotNeeded() { return true; }
    @Override public boolean useEmptyOverlayFastPath() { return true; }
    @Override public boolean useHasRemoveIntrinsicFastPath() { return true; }
}
