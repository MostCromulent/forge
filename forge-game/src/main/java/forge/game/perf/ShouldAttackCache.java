package forge.game.perf;

import java.util.HashMap;
import java.util.Map;

/**
 * Cache for AiAttackController.shouldAttack results within a single
 * declareAttackers call. Key is a string built by the caller capturing
 * attacker equivalence class + defender identity. Values are Booleans.
 *
 * Scope: enterDecision/exitDecision with a depth counter so nested calls
 * share the outermost scope. clears on enter-from-depth-0.
 *
 * The cache's correctness depends on an external guard at the read-site
 * (countExaltedBonus == 0, no combat-conditional pumps) — this class does
 * not enforce the guard itself.
 */
public final class ShouldAttackCache {
    private final Map<String, Boolean> entries = new HashMap<>();
    private int depth;
    private long hits;
    private long misses;

    public Boolean get(String key) {
        Boolean v = entries.get(key);
        if (v == null) misses++; else hits++;
        return v;
    }

    public void put(String key, boolean result) {
        entries.put(key, result);
    }

    public void enterDecision() {
        if (depth == 0) entries.clear();
        depth++;
    }

    public void exitDecision() {
        depth--;
        if (depth < 0) depth = 0;
    }

    public long hits() { return hits; }
    public long misses() { return misses; }
    public int size() { return entries.size(); }
}
