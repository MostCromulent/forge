package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * Engine-only optimisations: H013, H014, H020, H020b, H020c (+ H010
 * marginal). H003 DISABLED (because it intentionally picks different
 * specific tokens under EQUIVALENCE oracle, which would change AI
 * decisions and confound per-outcome comparison). H019 DISABLED (stale
 * bitmask edge case found by per-decision verify).
 *
 * For per-outcome equivalence testing: this stack should produce
 * byte-identical game outcomes to H000 because all fast paths are
 * semantic no-ops — they return the same result as the slow path
 * they replace.
 */
public final class EngineOnlyStack extends OptimizationContext {
    @Override public boolean useBinarySearchNotNeeded() { return false; }  // H003 off
    @Override public boolean useEmptyOverlayFastPath() { return true; }
    @Override public boolean useHasRemoveIntrinsicFastPath() { return true; }
    @Override public boolean useParsedRestrictionCache() { return true; }
    @Override public boolean useKeywordTraitBitmask() { return false; }  // H019 off
    @Override public boolean useGetReplacementEffectsFastPath() { return true; }
    @Override public boolean useGetStaticAbilitiesFastPath() { return true; }
    @Override public boolean useGetTriggersFastPath() { return true; }
}
