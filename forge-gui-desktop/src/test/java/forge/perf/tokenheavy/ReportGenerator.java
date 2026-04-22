package forge.perf.tokenheavy;

import forge.perf.tokenheavy.InstrumentedController.VariantSlot;

import java.util.Locale;
import java.util.Map;

/**
 * Stdout human-readable table + JSON emission for a single variant's run
 * against a fixture. Report format matches the spec's example.
 */
public final class ReportGenerator {

    public static String renderText(String hypothesisId, String fixture,
                                    VariantSlot baseline, VariantSlot variant,
                                    Map<String, Long> baselineCounters,
                                    Map<String, Long> variantCounters,
                                    int divergences,
                                    VerdictEvaluator.Verdict verdict) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(hypothesisId).append(" ===\n");
        sb.append("Fixture: ").append(fixture).append("\n\n");
        sb.append(String.format(Locale.ROOT, "%-20s %10s %10s %10s%n",
            "", "baseline", "variant", "delta"));
        sb.append(row("decisions", baseline.decisions.size(), variant.decisions.size()));
        sb.append(row("divergences", 0, divergences));
        sb.append(row("total wall ms",
            baseline.totalNanos / 1_000_000, variant.totalNanos / 1_000_000));
        for (String k : baselineCounters.keySet()) {
            sb.append(row(k + " calls",
                baselineCounters.get(k), variantCounters.getOrDefault(k, 0L)));
        }
        sb.append("\nVerdict: ").append(verdict).append("\n");
        return sb.toString();
    }

    private static String row(String label, long baseline, long variant) {
        String delta;
        if (baseline == variant) delta = "=";
        else if (baseline == 0) delta = "+inf";
        else delta = String.format(Locale.ROOT, "%+.1f%%", 100.0 * (variant - baseline) / baseline);
        return String.format(Locale.ROOT, " %-19s %10d %10d %10s%n", label, baseline, variant, delta);
    }

    public static String renderJson(String hypothesisId, String fixture,
                                    VariantSlot baseline, VariantSlot variant,
                                    Map<String, Long> baselineCounters,
                                    Map<String, Long> variantCounters,
                                    int divergences,
                                    VerdictEvaluator.Verdict verdict) {
        // Minimal JSON; no library dependency. Keep keys stable — Claude reads this.
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        kv(sb, "id", hypothesisId);
        kv(sb, "fixture", fixture);
        kv(sb, "verdict", verdict.name());
        sb.append("\"baseline_wall_nanos\":").append(baseline.totalNanos).append(",");
        sb.append("\"variant_wall_nanos\":").append(variant.totalNanos).append(",");
        sb.append("\"divergences\":").append(divergences).append(",");
        sb.append("\"baseline_counters\":").append(mapToJson(baselineCounters)).append(",");
        sb.append("\"variant_counters\":").append(mapToJson(variantCounters));
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
