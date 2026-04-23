package forge.game.perf;

import java.util.HashMap;
import java.util.Map;

/**
 * Cache for ComputerUtilCost.canPayCost results within a single
 * chooseSpellAbilityToPlay call. Keyed by a caller-supplied string
 * capturing (sa cost identity, payer mana-pool fingerprint, effect flag).
 *
 * Scope enforced by enterDecision/exitDecision with a depth counter so
 * nested calls share the outermost scope.
 */
public final class CostCache {
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
