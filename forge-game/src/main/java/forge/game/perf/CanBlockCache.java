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

    // --- Pure-form cache for canBlock(attacker, blocker, boolean nextTurn) ---
    // No combat state — genuinely pure over card properties. Lives for the
    // lifetime of the cache instance (not per-combat scoped) because card
    // properties don't change mid-decision.

    private final Map<Long, Boolean> pureEntries = new HashMap<>();
    private long pureHits;
    private long pureMisses;

    private static long pureKey(int attackerId, int blockerId, boolean nextTurn) {
        long base = ((long) attackerId << 33) | ((long)(blockerId & 0xFFFFFFFFL) << 1);
        return nextTurn ? base | 1L : base;
    }

    public Boolean getPure(Card attacker, Card blocker, boolean nextTurn) {
        if (attacker == null || blocker == null) return null;
        Boolean v = pureEntries.get(pureKey(attacker.getId(), blocker.getId(), nextTurn));
        if (v == null) pureMisses++; else pureHits++;
        return v;
    }

    public void putPure(Card attacker, Card blocker, boolean nextTurn, boolean result) {
        if (attacker == null || blocker == null) return;
        pureEntries.put(pureKey(attacker.getId(), blocker.getId(), nextTurn), result);
    }

    public long pureHits() { return pureHits; }
    public long pureMisses() { return pureMisses; }
    public int pureSize() { return pureEntries.size(); }
}
