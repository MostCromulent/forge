package forge.perf.tokenheavy;

import java.util.Map;

/**
 * Turns (baseline stats, variant stats, divergence count) into a verdict.
 *
 * Perf threshold (tactical decision from the plan):
 *   PASS          : no divergences AND (wall-time delta <= -2% OR any targeted
 *                   engine-counter delta <= -5%)
 *   INCONCLUSIVE  : no divergences AND no measurable improvement above noise
 *   PERF_REGRESSION : no divergences AND wall-time delta >= +2%
 *   CORRECTNESS_DIVERGENCE : divergences > 0 (regardless of perf)
 */
public final class VerdictEvaluator {
    public enum Verdict { PASS, INCONCLUSIVE, PERF_REGRESSION, CORRECTNESS_DIVERGENCE }

    public static final double IMPROVEMENT_PCT = -2.0;
    public static final double REGRESSION_PCT = 2.0;
    public static final double COUNTER_IMPROVEMENT_PCT = -5.0;

    public static Verdict evaluate(long baselineWallNanos, long variantWallNanos,
                                   Map<String, Long> baselineCounters,
                                   Map<String, Long> variantCounters,
                                   int divergenceCount) {
        if (divergenceCount > 0) return Verdict.CORRECTNESS_DIVERGENCE;

        double wallPct = pctDelta(baselineWallNanos, variantWallNanos);
        if (wallPct >= REGRESSION_PCT) return Verdict.PERF_REGRESSION;
        if (wallPct <= IMPROVEMENT_PCT) return Verdict.PASS;

        for (Map.Entry<String, Long> e : baselineCounters.entrySet()) {
            Long variantVal = variantCounters.get(e.getKey());
            if (variantVal == null) continue;
            double pct = pctDelta(e.getValue(), variantVal);
            if (pct <= COUNTER_IMPROVEMENT_PCT) return Verdict.PASS;
        }
        return Verdict.INCONCLUSIVE;
    }

    public static double pctDelta(long baseline, long variant) {
        if (baseline == 0) return variant == 0 ? 0.0 : 100.0;
        return 100.0 * (variant - baseline) / (double) baseline;
    }

    private VerdictEvaluator() {}
}
