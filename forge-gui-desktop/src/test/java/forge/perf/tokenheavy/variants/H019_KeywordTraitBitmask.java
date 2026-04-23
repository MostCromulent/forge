package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * H019 on top of H003+H013+H014+H010: pre-compute a trait bitmask on
 * KeywordCollection so Card.update{Static,Trigger,Replacement}Effects
 * can skip the keyword iteration when no keyword contributes that
 * trait type. Attacks the 9.33% Multimap.Itr.hasNext self-time
 * (69% driven by this loop across all three update methods).
 *
 * Same shape as H013/H014: O(1) short-circuit on a hot method,
 * fires for the majority of cards (vanilla keywords like Flying,
 * Trample, First Strike don't contribute REs/triggers/statics via
 * this path).
 */
public final class H019_KeywordTraitBitmask extends OptimizationContext {
    @Override public boolean useBinarySearchNotNeeded() { return true; }
    @Override public boolean useEmptyOverlayFastPath() { return true; }
    @Override public boolean useHasRemoveIntrinsicFastPath() { return true; }
    @Override public boolean useParsedRestrictionCache() { return true; }
    @Override public boolean useKeywordTraitBitmask() { return true; }
}
