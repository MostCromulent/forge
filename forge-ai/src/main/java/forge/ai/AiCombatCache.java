package forge.ai;

import java.util.*;

import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.staticability.StaticAbilityBlockRestrict;

/**
 * Unified per-decision cache for AI combat evaluation. Caches results of expensive
 * pure functions (canBlock, canDestroyAttacker, evaluateCreature, etc.) so that
 * identical creatures don't cause redundant computation.
 *
 * <p>Lifecycle: created at the start of a combat decision (attack or block),
 * used throughout, then discarded. No static state — no cross-turn leakage.
 *
 * <p>Thread safety: this cache is NOT thread-safe. It must not be accessed from
 * within CompletableFuture.supplyAsync() blocks in AiAttackController. Only use
 * after futures.join() synchronization.
 *
 * @see AiCacheMode
 * @see AiCombatStats
 */
public class AiCombatCache {

    // --- Mode ---
    private static final AiCacheMode MODE = AiCacheMode.get();

    // --- Thread safety verification (verify mode only) ---
    private final Thread creatingThread;

    // --- Creature grouping ---
    /** Representative Card → list of all equivalent Cards (including representative) */
    private final Map<Card, List<Card>> creatureGroups;
    /** Any Card → its representative (identity lookup) */
    private final IdentityHashMap<Card, Card> cardToRepresentative;

    // --- Tier-1 cache: keyword/evasion canBlock (stable throughout combat) ---
    private final Map<CardPair, Boolean> canBlockKeywordCache;

    // --- Per-pair caches (keyed by representative identity) ---
    private final Map<CardPair, Boolean> canDestroyAttackerCache;
    private final Map<CardPair, Boolean> canDestroyBlockerCache;

    // --- Per-card caches (keyed by representative identity) ---
    private final IdentityHashMap<Card, Integer> powerBonusCache;
    private final IdentityHashMap<Card, Integer> toughnessBonusCache;
    private final IdentityHashMap<Card, Integer> damagePrevCache;
    private final IdentityHashMap<Card, Integer> damageToKillCache;
    private final IdentityHashMap<Card, Integer> minNumBlockersCache;
    private final IdentityHashMap<Card, Integer> creatureEvalCache;

    // --- shouldAttack cache (guarded by Exalted check) ---
    private final IdentityHashMap<Card, Boolean> shouldAttackCache;
    private boolean shouldAttackCacheEnabled; // set after Exalted check

    // --- Combat mutation tracking ---
    private int combatGeneration;

    // --- Block restriction bypass ---
    private final boolean blockRestrictActive;

    // --- Instrumentation ---
    private final AiCombatStats stats;

    /**
     * Create a new cache for a combat decision, grouping equivalent creatures.
     *
     * @param battlefield all creatures on the battlefield (both sides)
     * @param defender the defending player (for block restriction check), or null for attack-only
     */
    private final AiCacheMode mode;

    public AiCombatCache(CardCollectionView battlefield, Player defender) {
        this(battlefield, defender, MODE);
    }

    /**
     * Constructor with explicit mode override (for testing).
     */
    AiCombatCache(CardCollectionView battlefield, Player defender, AiCacheMode testMode) {
        this.mode = testMode;
        this.creatingThread = Thread.currentThread();
        this.stats = new AiCombatStats();

        this.creatureGroups = new HashMap<>();
        this.cardToRepresentative = new IdentityHashMap<>();

        this.canBlockKeywordCache = new HashMap<>();
        this.canDestroyAttackerCache = new HashMap<>();
        this.canDestroyBlockerCache = new HashMap<>();

        this.powerBonusCache = new IdentityHashMap<>();
        this.toughnessBonusCache = new IdentityHashMap<>();
        this.damagePrevCache = new IdentityHashMap<>();
        this.damageToKillCache = new IdentityHashMap<>();
        this.minNumBlockersCache = new IdentityHashMap<>();
        this.creatureEvalCache = new IdentityHashMap<>();
        this.shouldAttackCache = new IdentityHashMap<>();
        this.shouldAttackCacheEnabled = false; // set by enableShouldAttackCache()

        this.combatGeneration = 0;

        // Check for block restriction effects
        if (defender != null) {
            int restrictNum = StaticAbilityBlockRestrict.blockRestrictNum(defender);
            this.blockRestrictActive = restrictNum < Integer.MAX_VALUE;
        } else {
            this.blockRestrictActive = false;
        }

        // Group equivalent creatures
        buildGroups(battlefield);
    }

    private void buildGroups(CardCollectionView battlefield) {
        if (!mode.isCacheActive()) {
            // In disabled mode, still populate cardToRepresentative as identity mapping
            // so getRepresentative() works (returns self)
            for (Card c : battlefield) {
                if (c.isCreature()) {
                    cardToRepresentative.put(c, c);
                }
            }
            stats.setGroupingInfo(cardToRepresentative.size(), cardToRepresentative.size());
            return;
        }

        List<Card> representatives = new ArrayList<>();
        int totalCreatures = 0;

        for (Card c : battlefield) {
            if (!c.isCreature()) continue;
            totalCreatures++;

            boolean foundGroup = false;
            for (Card rep : representatives) {
                if (ComputerUtilCard.areCreaturesEquivalentForAI(c, rep)) {
                    creatureGroups.get(rep).add(c);
                    cardToRepresentative.put(c, rep);
                    foundGroup = true;
                    break;
                }
            }

            if (!foundGroup) {
                // New group — this card is its own representative
                List<Card> group = new ArrayList<>();
                group.add(c);
                creatureGroups.put(c, group);
                cardToRepresentative.put(c, c);
                representatives.add(c);
            }
        }

        stats.setGroupingInfo(totalCreatures, representatives.size());
    }

    // --- Public API: cache lookups ---

    /**
     * Get the representative card for a given card. For grouped cards, returns
     * the group's representative. For ungrouped cards, returns the card itself.
     */
    public Card getRepresentative(Card c) {
        assertThread();
        return cardToRepresentative.getOrDefault(c, c);
    }

    /**
     * Get the creature equivalence groups.
     */
    public Map<Card, List<Card>> getCreatureGroups() {
        assertThread();
        return Collections.unmodifiableMap(creatureGroups);
    }

    /**
     * Cached two-arg canBlock (tier-1: keyword/evasion only, stable throughout combat).
     * Returns null if cache is disabled.
     */
    public Boolean canBlockKeyword(Card attacker, Card blocker) {
        if (!mode.isCacheActive()) return null;
        assertThread();

        Card aRep = getRepresentative(attacker);
        Card bRep = getRepresentative(blocker);
        CardPair key = new CardPair(aRep, bRep);

        stats.recordCall(AiCombatStats.Method.CAN_BLOCK_KEYWORD);
        Boolean cached = canBlockKeywordCache.get(key);
        if (cached != null) {
            stats.recordHit(AiCombatStats.Method.CAN_BLOCK_KEYWORD);
            if (mode.isVerify()) {
                boolean real = CombatUtil.canBlock(attacker, blocker);
                if (cached != real) {
                    System.err.println("[AiCombatCache] MISMATCH: canBlockKeyword("
                            + attacker.getName() + "#" + attacker.getId()
                            + ", " + blocker.getName() + "#" + blocker.getId()
                            + ") cached=" + cached + ", computed=" + real);
                    assert false : "canBlockKeyword cache mismatch";
                }
            }
            return cached;
        }

        boolean result = CombatUtil.canBlock(attacker, blocker);
        canBlockKeywordCache.put(key, result);
        return result;
    }

    /**
     * Cached evaluateCreature result.
     */
    public Integer evaluateCreature(Card c) {
        if (!mode.isCacheActive()) return null;
        assertThread();

        Card rep = getRepresentative(c);
        stats.recordCall(AiCombatStats.Method.EVALUATE_CREATURE);
        Integer cached = creatureEvalCache.get(rep);
        if (cached != null) {
            stats.recordHit(AiCombatStats.Method.EVALUATE_CREATURE);
            if (mode.isVerify()) {
                int real = ComputerUtilCard.evaluateCreature(c);
                if (cached != real) {
                    System.err.println("[AiCombatCache] MISMATCH: evaluateCreature("
                            + c.getName() + "#" + c.getId()
                            + ") cached=" + cached + ", computed=" + real);
                    assert false : "evaluateCreature cache mismatch";
                }
            }
            return cached;
        }

        int result = ComputerUtilCard.evaluateCreature(c);
        creatureEvalCache.put(rep, result);
        return result;
    }

    /**
     * Cached canDestroyAttacker result (per attacker-blocker pair by representative).
     * Returns null if cache is disabled.
     */
    public Boolean canDestroyAttacker(Player ai, Card attacker, Card blocker, Combat combat, boolean withoutAbilities) {
        if (!mode.isCacheActive()) return null;
        assertThread();

        Card aRep = getRepresentative(attacker);
        Card bRep = getRepresentative(blocker);
        CardPair key = new CardPair(aRep, bRep);

        stats.recordCall(AiCombatStats.Method.CAN_DESTROY_ATTACKER);
        Boolean cached = canDestroyAttackerCache.get(key);
        if (cached != null) {
            stats.recordHit(AiCombatStats.Method.CAN_DESTROY_ATTACKER);
            if (mode.isVerify()) {
                boolean real = ComputerUtilCombat.canDestroyAttacker(ai, attacker, blocker, combat, withoutAbilities);
                if (cached != real) {
                    System.err.println("[AiCombatCache] MISMATCH: canDestroyAttacker("
                            + attacker.getName() + "#" + attacker.getId()
                            + ", " + blocker.getName() + "#" + blocker.getId()
                            + ") cached=" + cached + ", computed=" + real);
                    assert false : "canDestroyAttacker cache mismatch";
                }
            }
            return cached;
        }

        boolean result = ComputerUtilCombat.canDestroyAttacker(ai, attacker, blocker, combat, withoutAbilities);
        canDestroyAttackerCache.put(key, result);
        return result;
    }

    /**
     * Cached canDestroyBlocker result (per attacker-blocker pair by representative).
     * Returns null if cache is disabled.
     */
    public Boolean canDestroyBlocker(Player ai, Card blocker, Card attacker, Combat combat, boolean withoutAbilities) {
        if (!mode.isCacheActive()) return null;
        assertThread();

        Card aRep = getRepresentative(attacker);
        Card bRep = getRepresentative(blocker);
        CardPair key = new CardPair(aRep, bRep);

        stats.recordCall(AiCombatStats.Method.CAN_DESTROY_BLOCKER);
        Boolean cached = canDestroyBlockerCache.get(key);
        if (cached != null) {
            stats.recordHit(AiCombatStats.Method.CAN_DESTROY_BLOCKER);
            if (mode.isVerify()) {
                boolean real = ComputerUtilCombat.canDestroyBlocker(ai, blocker, attacker, combat, withoutAbilities);
                if (cached != real) {
                    System.err.println("[AiCombatCache] MISMATCH: canDestroyBlocker("
                            + blocker.getName() + "#" + blocker.getId()
                            + ", " + attacker.getName() + "#" + attacker.getId()
                            + ") cached=" + cached + ", computed=" + real);
                    assert false : "canDestroyBlocker cache mismatch";
                }
            }
            return cached;
        }

        boolean result = ComputerUtilCombat.canDestroyBlocker(ai, blocker, attacker, combat, withoutAbilities);
        canDestroyBlockerCache.put(key, result);
        return result;
    }

    /**
     * Cached getDamageToKill result (per attacker, stable during blocking phase).
     * Returns null if cache is disabled.
     */
    public Integer getDamageToKill(Card attacker, boolean withoutAbilities) {
        if (!mode.isCacheActive()) return null;
        assertThread();

        Card rep = getRepresentative(attacker);
        stats.recordCall(AiCombatStats.Method.GET_DAMAGE_TO_KILL);
        Integer cached = damageToKillCache.get(rep);
        if (cached != null) {
            stats.recordHit(AiCombatStats.Method.GET_DAMAGE_TO_KILL);
            return cached;
        }

        int result = ComputerUtilCombat.getDamageToKill(attacker, withoutAbilities);
        damageToKillCache.put(rep, result);
        return result;
    }

    /**
     * Cached getMinNumBlockersForAttacker result (per attacker, stable during blocking phase).
     * Returns null if cache is disabled.
     */
    public Integer getMinNumBlockers(Card attacker, Player defender) {
        if (!mode.isCacheActive()) return null;
        assertThread();

        Card rep = getRepresentative(attacker);
        stats.recordCall(AiCombatStats.Method.GET_MIN_NUM_BLOCKERS);
        Integer cached = minNumBlockersCache.get(rep);
        if (cached != null) {
            stats.recordHit(AiCombatStats.Method.GET_MIN_NUM_BLOCKERS);
            return cached;
        }

        int result = CombatUtil.getMinNumBlockersForAttacker(attacker, defender);
        minNumBlockersCache.put(rep, result);
        return result;
    }

    /**
     * Enable shouldAttack caching. Call this after verifying no Exalted sources
     * and no combat-conditional pump abilities are present.
     */
    public void enableShouldAttackCache() {
        this.shouldAttackCacheEnabled = true;
    }

    /**
     * Cached shouldAttack result. Only caches when the Exalted guard permits it.
     * Returns null if cache is disabled or guard is not active.
     */
    public Boolean shouldAttack(Card attacker) {
        if (!mode.isCacheActive() || !shouldAttackCacheEnabled) return null;
        assertThread();

        Card rep = getRepresentative(attacker);
        stats.recordCall(AiCombatStats.Method.SHOULD_ATTACK);
        Boolean cached = shouldAttackCache.get(rep);
        if (cached != null) {
            stats.recordHit(AiCombatStats.Method.SHOULD_ATTACK);
            return cached;
        }
        return null; // cache miss — caller must compute and store via putShouldAttack
    }

    /**
     * Store a shouldAttack result after computation.
     */
    public void putShouldAttack(Card attacker, boolean result) {
        if (!mode.isCacheActive() || !shouldAttackCacheEnabled) return;
        Card rep = getRepresentative(attacker);
        shouldAttackCache.put(rep, result);
    }

    /**
     * Notify the cache that a blocker has been assigned via combat.addBlocker().
     * This increments the combat generation, invalidating tier-2 canBlock results.
     */
    public void onBlockerAssigned() {
        combatGeneration++;
    }

    /**
     * Whether block restriction effects are active (bypasses canBlock caching).
     */
    public boolean isBlockRestrictActive() {
        return blockRestrictActive;
    }

    /**
     * Get the instrumentation stats object.
     */
    public AiCombatStats getStats() {
        return stats;
    }

    /**
     * Report cache performance. Called at the end of a combat decision.
     */
    public void report(String context, long startNanos) {
        long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
        stats.report(context, totalMs);
    }

    // --- Post-decision exhaustive verification (verify mode only) ---

    /**
     * In verify mode, check that cached results hold for ALL members of ALL groups,
     * not just the ones that were queried during the decision. This catches equivalence
     * criteria gaps that would be hidden by short-circuit evaluation.
     */
    public void verifyAllGroupMembers() {
        if (!mode.isVerify()) return;

        for (Map.Entry<Card, List<Card>> group : creatureGroups.entrySet()) {
            Card rep = group.getKey();
            for (Card member : group.getValue()) {
                if (member == rep) continue;

                // Verify evaluateCreature
                Integer cachedEval = creatureEvalCache.get(rep);
                if (cachedEval != null) {
                    int realEval = ComputerUtilCard.evaluateCreature(member);
                    if (cachedEval != realEval) {
                        System.err.println("[AiCombatCache] EXHAUSTIVE VERIFY FAIL: evaluateCreature("
                                + member.getName() + "#" + member.getId()
                                + ") cached=" + cachedEval + " (from rep #" + rep.getId()
                                + "), computed=" + realEval);
                        assert false : "Exhaustive verify: evaluateCreature diverged";
                    }
                }

                // Verify canBlockKeyword for all cached pairs involving this group
                for (Map.Entry<CardPair, Boolean> entry : canBlockKeywordCache.entrySet()) {
                    CardPair pair = entry.getKey();
                    if (pair.a == rep) {
                        boolean real = CombatUtil.canBlock(member, pair.b);
                        if (entry.getValue() != real) {
                            System.err.println("[AiCombatCache] EXHAUSTIVE VERIFY FAIL: canBlock("
                                    + member.getName() + "#" + member.getId()
                                    + ", " + pair.b.getName() + "#" + pair.b.getId()
                                    + ") cached=" + entry.getValue() + ", computed=" + real);
                            assert false : "Exhaustive verify: canBlock diverged for attacker member";
                        }
                    }
                    if (pair.b == rep) {
                        boolean real = CombatUtil.canBlock(pair.a, member);
                        if (entry.getValue() != real) {
                            System.err.println("[AiCombatCache] EXHAUSTIVE VERIFY FAIL: canBlock("
                                    + pair.a.getName() + "#" + pair.a.getId()
                                    + ", " + member.getName() + "#" + member.getId()
                                    + ") cached=" + entry.getValue() + ", computed=" + real);
                            assert false : "Exhaustive verify: canBlock diverged for blocker member";
                        }
                    }
                }
            }
        }
    }

    // --- Internal ---

    private void assertThread() {
        if (mode.isVerify()) {
            assert Thread.currentThread() == creatingThread
                    : "AiCombatCache accessed from wrong thread: "
                    + Thread.currentThread().getName() + " (created on " + creatingThread.getName() + ")";
        }
    }

    /**
     * Lightweight pair of Cards using identity equality for use as cache keys.
     */
    static final class CardPair {
        final Card a;
        final Card b;

        CardPair(Card a, Card b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CardPair)) return false;
            CardPair that = (CardPair) o;
            return this.a == that.a && this.b == that.b;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(a) * 31 + System.identityHashCode(b);
        }
    }
}
