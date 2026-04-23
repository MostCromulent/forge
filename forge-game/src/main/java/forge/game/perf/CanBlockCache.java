package forge.game.perf;

import forge.game.card.Card;
import forge.game.combat.Combat;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-combat cache for CombatUtil.canBlock(attacker, blocker, combat) results.
 * Scoped by Combat-object identity — when the combat object changes (new combat
 * phase, new turn), the cache is auto-invalidated on next access. Null combat
 * is treated as uncacheable.
 *
 * Key is packed (attackerId, blockerId) as a long.
 */
public final class CanBlockCache {
    private Combat scope;
    private final Map<Long, Boolean> entries = new HashMap<>();
    private long hits;
    private long misses;

    private static long key(int attackerId, int blockerId) {
        return ((long) attackerId << 32) | (blockerId & 0xFFFFFFFFL);
    }

    public Boolean get(Card attacker, Card blocker, Combat combat) {
        if (combat == null) return null;
        if (combat != scope) {
            entries.clear();
            scope = combat;
            return null;
        }
        Boolean v = entries.get(key(attacker.getId(), blocker.getId()));
        if (v == null) misses++; else hits++;
        return v;
    }

    public void put(Card attacker, Card blocker, Combat combat, boolean result) {
        if (combat == null) return;
        if (combat != scope) {
            entries.clear();
            scope = combat;
        }
        entries.put(key(attacker.getId(), blocker.getId()), result);
    }

    public long hits() { return hits; }
    public long misses() { return misses; }
    public int size() { return entries.size(); }

    // --- State-aware cache for canBlock(attacker, blocker, boolean nextTurn) ---
    // The method is pure over (card state, nextTurn). Card state includes
    // keywords and tap state, which mutate via auras/equipment/triggered
    // pumps even within a single AI decision call. Key includes a state
    // fingerprint on both cards so state-changed re-queries get a fresh
    // compute instead of a stale hit.

    private final Map<StateKey, Boolean> pureEntries = new HashMap<>();
    private long pureHits;
    private long pureMisses;

    private static int stateHash(forge.game.card.Card c) {
        int h = c.isTapped() ? 1 : 0;
        h = h * 31 + (c.hasSickness() ? 1 : 0);
        for (forge.game.keyword.KeywordInterface kw : c.getKeywords()) {
            h = h * 31 + kw.getOriginal().hashCode();
        }
        return h;
    }

    private static final class StateKey {
        final int aid, bid;
        final boolean nextTurn;
        final int aState, bState;
        final int hash;
        StateKey(Card a, Card b, boolean nt) {
            this.aid = a.getId();
            this.bid = b.getId();
            this.nextTurn = nt;
            this.aState = stateHash(a);
            this.bState = stateHash(b);
            // Mix all fields into a stable hash.
            int h = aid;
            h = h * 31 + bid;
            h = h * 31 + (nt ? 1 : 0);
            h = h * 31 + aState;
            h = h * 31 + bState;
            this.hash = h;
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof StateKey)) return false;
            StateKey k = (StateKey) o;
            return aid == k.aid && bid == k.bid && nextTurn == k.nextTurn
                    && aState == k.aState && bState == k.bState;
        }
    }

    public Boolean getPure(Card attacker, Card blocker, boolean nextTurn) {
        if (attacker == null || blocker == null) return null;
        Boolean v = pureEntries.get(new StateKey(attacker, blocker, nextTurn));
        if (v == null) pureMisses++; else pureHits++;
        return v;
    }

    public void putPure(Card attacker, Card blocker, boolean nextTurn, boolean result) {
        if (attacker == null || blocker == null) return;
        pureEntries.put(new StateKey(attacker, blocker, nextTurn), result);
    }

    public long pureHits() { return pureHits; }
    public long pureMisses() { return pureMisses; }
    public int pureSize() { return pureEntries.size(); }

    // --- Decision-boundary scoping ----------------------------------------
    // Card state is stable within a single AI decision call but can change
    // between calls (tap/untap, keyword gain/loss, zone moves). We clear the
    // cache at the outermost decision entry so cached answers never outlive
    // a state-stable window. Depth counter lets nested calls (e.g. declareA
    // -> predictNext -> assignBlockers) share the outermost call's cache.
    private int decisionDepth;

    public void enterDecision() {
        if (decisionDepth == 0) {
            pureEntries.clear();
            entries.clear();
            scope = null;
        }
        decisionDepth++;
    }

    public void exitDecision() {
        decisionDepth--;
        if (decisionDepth < 0) decisionDepth = 0; // defensive
    }
}
