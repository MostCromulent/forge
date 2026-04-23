package forge.perf.tokenheavy;

import forge.perf.tokenheavy.InstrumentedController.VariantSlot;

import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Stdout human-readable table + JSON emission for one hypothesis run against
 * one fixture. Emits both per-counter call counts AND per-counter nanos so the
 * actual wall-time contribution of each engine hot path is visible.
 */
public final class ReportGenerator {

    public static String renderText(String hypothesisId, String fixture,
                                    VariantSlot baseline, VariantSlot variant,
                                    long gameWallNanos,
                                    Map<String, Long> baselineCalls,
                                    Map<String, Long> variantCalls,
                                    Map<String, Long> baselineNanos,
                                    Map<String, Long> variantNanos,
                                    int divergences,
                                    VerdictEvaluator.Verdict verdict) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(hypothesisId).append(" ===\n");
        sb.append("Fixture: ").append(fixture).append("\n\n");
        sb.append(String.format(Locale.ROOT, "%-28s %10s %10s %10s%n",
            "", "baseline", "variant", "delta"));
        sb.append(row("decisions", baseline.decisions.size(), variant.decisions.size()));
        sb.append(row("divergences", 0, divergences));
        sb.append(String.format(Locale.ROOT, " %-27s %10d%n",
            "game wall ms (baseline run)", gameWallNanos / 1_000_000));
        sb.append(row("AI slot wall ms",
            baseline.totalNanos / 1_000_000, variant.totalNanos / 1_000_000));

        TreeSet<String> counters = new TreeSet<>();
        counters.addAll(baselineCalls.keySet());
        counters.addAll(variantCalls.keySet());
        for (String k : counters) {
            long bCalls = baselineCalls.getOrDefault(k, 0L);
            long vCalls = variantCalls.getOrDefault(k, 0L);
            long bMs = baselineNanos.getOrDefault(k, 0L) / 1_000_000;
            long vMs = variantNanos.getOrDefault(k, 0L) / 1_000_000;
            sb.append(row(k + " calls", bCalls, vCalls));
            sb.append(row(k + " ms", bMs, vMs));
        }
        sb.append("\nVerdict: ").append(verdict).append("\n");
        return sb.toString();
    }

    private static String row(String label, long baseline, long variant) {
        String delta;
        if (baseline == variant) delta = "=";
        else if (baseline == 0) delta = "+inf";
        else delta = String.format(Locale.ROOT, "%+.1f%%", 100.0 * (variant - baseline) / baseline);
        return String.format(Locale.ROOT, " %-27s %10d %10d %10s%n", label, baseline, variant, delta);
    }

    public static String renderJson(String hypothesisId, String fixture,
                                    VariantSlot baseline, VariantSlot variant,
                                    long gameWallNanos,
                                    Map<String, Long> baselineCalls,
                                    Map<String, Long> variantCalls,
                                    Map<String, Long> baselineNanos,
                                    Map<String, Long> variantNanos,
                                    int divergences,
                                    VerdictEvaluator.Verdict verdict) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        kv(sb, "id", hypothesisId);
        kv(sb, "fixture", fixture);
        kv(sb, "verdict", verdict.name());
        sb.append("\"game_wall_nanos\":").append(gameWallNanos).append(",");
        sb.append("\"baseline_slot_nanos\":").append(baseline.totalNanos).append(",");
        sb.append("\"variant_slot_nanos\":").append(variant.totalNanos).append(",");
        sb.append("\"baseline_decisions\":").append(baseline.decisions.size()).append(",");
        sb.append("\"variant_decisions\":").append(variant.decisions.size()).append(",");
        sb.append("\"divergences\":").append(divergences).append(",");
        sb.append("\"baseline_counters\":").append(mapToJson(baselineCalls)).append(",");
        sb.append("\"variant_counters\":").append(mapToJson(variantCalls)).append(",");
        sb.append("\"baseline_counter_nanos\":").append(mapToJson(baselineNanos)).append(",");
        sb.append("\"variant_counter_nanos\":").append(mapToJson(variantNanos));
        sb.append("}");
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String k, String v) {
        sb.append("\"").append(k).append("\":\"").append(v).append("\",");
    }
    private static String mapToJson(Map<String, Long> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> e : m.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        return sb.append("}").toString();
    }

    private ReportGenerator() {}
}
