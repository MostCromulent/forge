package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * H003: algorithmic change in AiAttackController.notNeededAsBlockers — group
 * consecutive identical-equivalence-class blockers in the power-sorted list and
 * process each group with a single batch predict call (plus binary search on
 * MIN_VALUE). Reduces predictNextCombatsRemainingLife call count from O(N) per
 * group to O(1) best case, O(log N) bisect case.
 *
 * Oracle: EQUIVALENCE (hypothesis proposal explicitly declared). The specific
 * token that ends up released may differ from baseline but equivalence class
 * and count must match. Validated by comparing baseline vs variant real-games
 * batches at the game-outcome level.
 *
 * Mode: RUN (engine-level algorithmic change; baseline and variant run in
 * separate games).
 */
public final class H003_NotNeededBinarySearch extends OptimizationContext {
    @Override
    public boolean useBinarySearchNotNeeded() { return true; }
}
