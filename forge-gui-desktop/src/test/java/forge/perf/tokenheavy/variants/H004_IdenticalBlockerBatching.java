package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * H004: defender-side analogue of H003. In AiBlockController's filter methods
 * (getPossibleBlockers, getSafeBlockers, getKillingBlockers), collapse
 * redundant per-blocker predicate evaluations using a local equivalence
 * cache. When blockersLeft contains many identical tokens, subsequent blockers
 * in the same equivalence class reuse the first evaluation's result instead
 * of re-running canBlock/canDestroy.
 *
 * Batching engages only when blockersLeft.size() >= 4 (below that, the
 * equivalence-scan overhead exceeds the savings).
 *
 * Oracle: EQUIVALENCE declared (same pattern as H003). Correctness argument:
 * identical-equivalence-class blockers produce identical predicate results
 * under canBlock (card-properties only) and canDestroyBlocker/canDestroyAttacker
 * (power/toughness/keywords dependent, identical for equivalent tokens). Cache
 * preserves original iteration order so downstream "pick first" / "pick last"
 * logic behaves unchanged.
 */
public final class H004_IdenticalBlockerBatching extends OptimizationContext {
    @Override
    public boolean useIdenticalBlockerBatching() { return true; }
}
