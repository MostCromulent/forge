package forge.ai;

/**
 * Instrumentation for AI combat cache. Tracks call counts, cache hit counts,
 * and wall-clock time per cached method. Enabled via {@code -Dai.instrument=true}.
 *
 * All methods are no-ops when instrumentation is disabled, and the static final
 * boolean allows JIT to eliminate the branches entirely.
 */
public class AiCombatStats {

    public static final boolean INSTRUMENT = Boolean.getBoolean("ai.instrument");

    public enum Method {
        CAN_BLOCK_KEYWORD,       // Tier-1: keyword/evasion check (stable)
        CAN_BLOCK_FULL,          // Tier-2: full canBlock with combat state
        CAN_DESTROY_ATTACKER,
        CAN_DESTROY_BLOCKER,
        PREDICT_POWER_BONUS,
        TOUGHNESS_BONUS,
        DAMAGE_PREVENTION,
        GET_DAMAGE_TO_KILL,
        GET_MIN_NUM_BLOCKERS,
        EVALUATE_CREATURE,
        SHOULD_ATTACK
    }

    private final long[] callCounts;
    private final long[] hitCounts;
    private final long[] totalNanos;
    private final long[] timerStarts; // per-method start timestamp for timing

    private int totalCreatures;
    private int totalGroups;

    public AiCombatStats() {
        int n = Method.values().length;
        callCounts = new long[n];
        hitCounts = new long[n];
        totalNanos = new long[n];
        timerStarts = new long[n];
    }

    public void recordCall(Method method) {
        if (!INSTRUMENT) return;
        callCounts[method.ordinal()]++;
    }

    public void recordHit(Method method) {
        if (!INSTRUMENT) return;
        hitCounts[method.ordinal()]++;
    }

    public void startTimer(Method method) {
        if (!INSTRUMENT) return;
        timerStarts[method.ordinal()] = System.nanoTime();
    }

    public void stopTimer(Method method) {
        if (!INSTRUMENT) return;
        totalNanos[method.ordinal()] += System.nanoTime() - timerStarts[method.ordinal()];
    }

    public void setGroupingInfo(int creatures, int groups) {
        this.totalCreatures = creatures;
        this.totalGroups = groups;
    }

    /**
     * Logs a summary of cache performance to System.out.
     * @param context description of the decision (e.g., "Attack declaration", "Block assignment")
     * @param totalMs total wall-clock time for the decision in milliseconds
     */
    public void report(String context, long totalMs) {
        if (!INSTRUMENT) return;

        StringBuilder sb = new StringBuilder();
        sb.append("[AiCombatCache] ").append(context).append(" completed in ").append(totalMs).append("ms\n");

        for (Method m : Method.values()) {
            int i = m.ordinal();
            if (callCounts[i] == 0) continue;
            long hits = hitCounts[i];
            long calls = callCounts[i];
            int pct = calls > 0 ? (int) (100 * hits / calls) : 0;
            long ms = totalNanos[i] / 1_000_000;
            sb.append(String.format("  %-25s %5d calls, %5d hits (%2d%%), %4dms total%n",
                    m.name().toLowerCase() + ":", calls, hits, pct, ms));
        }

        sb.append(String.format("  Creature groups: %d groups from %d creatures%n", totalGroups, totalCreatures));
        System.out.println(sb);
    }
}
