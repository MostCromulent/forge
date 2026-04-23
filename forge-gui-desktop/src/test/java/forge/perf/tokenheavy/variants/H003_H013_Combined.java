package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * Combined variant: H003 (binary-search release in AiAttackController.
 * notNeededAsBlockers) + H013 (empty-overlay fast path in
 * Card.getChangedCardTraitsList). Both are validated optimizations;
 * running them together produces the post-H003+H013 flame graph used
 * to pick the next hypothesis.
 */
public final class H003_H013_Combined extends OptimizationContext {
    @Override public boolean useBinarySearchNotNeeded() { return true; }
    @Override public boolean useEmptyOverlayFastPath() { return true; }
}
