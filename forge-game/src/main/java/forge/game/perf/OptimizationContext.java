package forge.game.perf;

/**
 * Thread-local optimization context read by hot paths. Production code uses
 * {@link #BASELINE} which preserves current behaviour. Variants subclass and
 * override individual strategy methods. See the testbed spec at
 * .claude/superpowers/specs/2026-04-23-perf-hypothesis-testbed-design.md
 */
public class OptimizationContext {
    public static final OptimizationContext BASELINE = new OptimizationContext();
    // InheritableThreadLocal so child threads (e.g. game threads spawned via
    // TimeLimitedCodeBlock's single-thread executor) inherit the context that
    // was set on the parent test thread before the child was created.
    private static final ThreadLocal<OptimizationContext> CURRENT =
        new InheritableThreadLocal<OptimizationContext>() {
            @Override protected OptimizationContext initialValue() { return BASELINE; }
        };

    public static OptimizationContext current() { return CURRENT.get(); }
    public static void set(OptimizationContext ctx) { CURRENT.set(ctx == null ? BASELINE : ctx); }
    public static void reset() { CURRENT.set(BASELINE); }

    // Strategy read points are added incrementally by subsequent hypotheses.
    // Baseline implementations return input unchanged / absent / null.

    /**
     * Extension point for hypothesis H001+: filter duplicate battlefield tokens
     * out of the dedupeCards result. Baseline returns the input unchanged.
     * Variants override to return a smaller collection.
     */
    public forge.game.card.CardCollection filterDuplicateBattlefieldTokens(
            forge.game.card.CardCollection cards) {
        return cards;
    }

    // H002: CombatUtil.canBlock cache. Null = no cache (baseline behaviour).
    public CanBlockCache canBlockCache() { return null; }

    // H002 verify mode: if true, canBlock computes fresh every call and
    // compares against cache; divergences logged via reportCanBlockDivergence.
    // Game proceeds under baseline semantics (fresh result governs).
    public boolean verifyCanBlock() { return false; }

    public void reportCanBlockDivergence(forge.game.card.Card attacker,
                                         forge.game.card.Card blocker,
                                         boolean cachedResult,
                                         boolean freshResult) {
        // Baseline: no-op. Verify variants override to log.
    }

    // H003: use binary search over identical-equivalence-class blocker groups
    // in AiAttackController.notNeededAsBlockers. Under EQUIVALENCE oracle —
    // specific card released may differ from baseline but equivalence class
    // + count must match. Default baseline: false (original per-blocker loop).
    public boolean useBinarySearchNotNeeded() { return false; }

    // H004: in AiBlockController's filter methods (getPossibleBlockers,
    // getSafeBlockers, getKillingBlockers), collapse redundant predicate
    // evaluations by maintaining a local equivalence-cache — when the same
    // equivalence class was already evaluated, reuse the result instead of
    // re-running canBlock/canDestroy. Defender-side analogue of H003.
    public boolean useIdenticalBlockerBatching() { return false; }

    // H005: cache AiAttackController.shouldAttack results within a single
    // declareAttackers call. Guarded at the read-site by an Exalted-check.
    public ShouldAttackCache shouldAttackCache() { return null; }

    // H005 verify: compute fresh AND consult cache every call; log
    // divergences. Game runs under baseline semantics (fresh result governs).
    public boolean verifyShouldAttack() { return false; }

    public void reportShouldAttackDivergence(forge.game.card.Card attacker,
                                             boolean cachedResult,
                                             boolean freshResult) {
        // Baseline: no-op. Verify variants override to log.
    }

    // H006: in ReplacementHandler.getReplacementList, iterate
    // game.getCardsWithReplacements() (a precomputed index) instead of
    // forEachCardInGame. Correctness depends on the index capturing every
    // card that could contribute a replacement effect — currently hooks
    // only Card.addReplacementEffect, so auras/static-abilities that grant
    // dynamic REs may be missed. Verify variant validates empirically.
    public boolean useReplacementIndexFastPath() { return false; }

    // H006 verify: run both old (forEachCardInGame) and new (indexed)
    // iterations; compare result multisets; log any replacement effect
    // produced by one but not the other.
    public boolean verifyReplacementIndex() { return false; }

    public void reportReplacementIndexDivergence(String description) {
        // Baseline: no-op. Verify variants override.
    }

    // H007: cache ComputerUtilCost.canPayCost results within a single
    // chooseSpellAbilityToPlay call. Key includes payer mana-pool
    // fingerprint so state changes invalidate naturally.
    public CostCache costCache() { return null; }

    // H007 verify: compute fresh + consult cache every call; log
    // divergences. Game runs under baseline semantics.
    public boolean verifyCostCache() { return false; }

    public void reportCostCacheDivergence(forge.game.spellability.SpellAbility sa,
                                          boolean cachedResult,
                                          boolean freshResult) {
        // Baseline: no-op.
    }

    // H008: in AiController.chooseSpellAbilityToPlayFromList, dedupe the input
    // SA list by equivalence class (host identity + ability identity + cost)
    // before sort + iteration, so N identical tokens with K abilities score
    // and canPlay-check 1×K SAs instead of N×K. Correctness: an arbitrary
    // representative stands in for the whole class — engine plays that specific
    // host, and any group member was interchangeable by construction.
    public boolean useSaEquivalenceDedupe() { return false; }

    // H008 verify: run both the deduped path and the original path, compare
    // the chosen SA by equivalence key (not identity); log divergences.
    public boolean verifySaEquivalenceDedupe() { return false; }

    public void reportSaEquivalenceDivergence(String fullKey,
                                              String dedupedKey) {
        // Baseline: no-op.
    }

    // H013: short-circuit Card.getChangedCardTraitsList when both overlay
    // TreeBasedTables are empty (the common case for most cards in real
    // games). Baseline iterates the empty tables paying the TreeMap
    // getFirstEntry cost that shows as ~5.6% self-time in the flame graph.
    public boolean useEmptyOverlayFastPath() { return false; }

    // H013 verify: run the full concat path AND check the fast-path
    // condition; if fast-path would return singleton-only but slow path
    // produced different elements, report divergence.
    public boolean verifyEmptyOverlayFastPath() { return false; }
    public void reportEmptyOverlayDivergence(forge.game.card.Card card, String reason) { }

    // H014: short-circuit Card.hasRemoveIntrinsic when all three
    // changedCardTypes tables are empty, so we skip the IterableUtil.any
    // call that allocates a Stream even for empty input. This is called
    // from LandTraitChanges.apply{Static,Trigger,Replacement,Keywords}()
    // on every card on every state sweep.
    public boolean useHasRemoveIntrinsicFastPath() { return false; }

    // H014 verify: run both the isEmpty check and the full
    // IterableUtil.any path; if fast-path returns false but slow path
    // returns true, divergence.
    public boolean verifyHasRemoveIntrinsicFastPath() { return false; }
    public void reportHasRemoveIntrinsicDivergence(forge.game.card.Card card) { }

    // H016: rewrite IterableUtil.any/all to plain for-loops instead of
    // StreamSupport.stream(...).anyMatch(test). Avoids Spliterator + Stream
    // allocation in the hot path, benefits all 26 call sites.
    public boolean useIterableUtilForLoop() { return false; }

    // H018: replace CardLists.filter(Iterable, Predicate) Stream wrapper
    // with a direct for-loop build. Eliminates per-call Spliterator +
    // Stream pipeline allocation for every CardLists.filter call (and by
    // extension getValidCards, which is a hot predicate-filter path).
    public boolean useDirectCardListsFilter() { return false; }

    // H010: cache the parsed structure of Card.isValid restriction
    // strings + the comma-split in CardLists.getValidCards. Restriction
    // strings come from card scripts and are immutable, so parse-once
    // is a pure-function cache with no correctness risk.
    public boolean useParsedRestrictionCache() { return false; }

    // H019: pre-compute on KeywordCollection whether any keyword
    // contributes triggers / replacement effects / static abilities.
    // Card.update{Static,Trigger,Replacement}Effects checks the bitmask
    // and skips the keyword-iteration loop when empty. Attacks the
    // ~9.33% Multimap.Itr.hasNext hot path (69% driven by this loop).
    public boolean useKeywordTraitBitmask() { return false; }

    // H019 verify: in each update* method, check the bitmask's claim
    // by running the keyword loop anyway and confirming it added
    // nothing when the bitmask said "empty." Reports divergences.
    public boolean verifyKeywordTraitBitmask() { return false; }
    public void reportKeywordBitmaskDivergence(forge.game.card.Card card, String kind, int added) { }

    // H020: in CardState.getReplacementEffects, short-circuit to a
    // shared empty view when the card has no replacement-effect source
    // (no intrinsic REs, no RE-keywords, no overlays, no split sides,
    // no RE-producing type/subtype, no RE-producing counters). Targets
    // the 27%-of-stack frequency of getReplacementEffects + FCollection
    // allocation cost.
    public boolean useGetReplacementEffectsFastPath() { return false; }

    // H020 verify: check the fast-path condition but always run the
    // slow path; report divergences (condition said empty but slow
    // path found REs). Used to empirically validate the condition
    // captures every mechanism by which a card can gain a RE.
    public boolean verifyGetReplacementEffectsFastPath() { return false; }

    public void reportGetReplacementEffectsDivergence(forge.game.card.Card card, String reason) {
        // Baseline: no-op. Verify variants override to log.
    }

    // H020b: same pattern as H020 but on getStaticAbilities.
    public boolean useGetStaticAbilitiesFastPath() { return false; }
    public boolean verifyGetStaticAbilitiesFastPath() { return false; }
    public void reportGetStaticAbilitiesDivergence(forge.game.card.Card card, String reason) { }

    // H020c: same pattern as H020 but on getTriggers.
    public boolean useGetTriggersFastPath() { return false; }
    public boolean verifyGetTriggersFastPath() { return false; }
    public void reportGetTriggersDivergence(forge.game.card.Card card, String reason) { }

    // H020d: eagerly compute the KeywordCollection trait bitmask at the
    // end of Card.updateKeywordsCache, amortizing the walk to cache-build
    // time instead of paying it on every first-query across many paths.
    // Attacks the ~12% self-time in hasReplacementEffectKeyword +
    // hasStaticAbilityKeyword observed after H020bc.
    public boolean useEagerKeywordTraitFlags() { return false; }
}
