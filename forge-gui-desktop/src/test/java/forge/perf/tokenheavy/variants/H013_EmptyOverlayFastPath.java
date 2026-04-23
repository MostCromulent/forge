package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * H013: short-circuit Card.getChangedCardTraitsList when both TreeBasedTable
 * overlays (changedCardTraitsByText, changedCardTraits) are empty — the
 * common case for most cards in real games. Baseline iterates empty tables,
 * paying the TreeMap getFirstEntry cost that appears as ~5.6% of in-game
 * self-time in the flame graph, plus related concat-iterator overhead.
 *
 * Fast path returns ImmutableList.of(landTraitChanges) — a singleton list.
 * Correctness: iterating an empty TreeBasedTable produces zero elements, so
 * the concat path yields exactly the same sequence as the fast path (just
 * the landTraitChanges element) when both tables are empty.
 */
public final class H013_EmptyOverlayFastPath extends OptimizationContext {
    @Override
    public boolean useEmptyOverlayFastPath() { return true; }
}
