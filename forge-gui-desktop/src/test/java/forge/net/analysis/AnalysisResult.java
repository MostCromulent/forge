package forge.net.analysis;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Aggregates results across all analyzed games and generates reports.
 * Adapted for master's protocol (no delta sync — focuses on error detection).
 */
public class AnalysisResult {

    private final List<GameLogMetrics> allMetrics;
    private final LocalDateTime analysisTime;

    // Cached aggregations
    private int totalGames;
    private int successfulGames;
    private int failedGames;
    private int gamesWithErrors;
    private int gamesWithWarnings;
    private int totalSendErrors;
    private int totalTurns;
    private double averageTurns;

    private Map<Integer, PlayerCountStats> statsByPlayerCount;
    private Map<GameLogMetrics.FailureMode, Integer> failureModeCounts;
    private Map<String, Integer> errorFrequency;
    private Map<String, Integer> errorGameCount;
    private Map<String, List<String>> errorGameNames;
    private Map<String, Integer> winnerFrequency;

    public AnalysisResult(List<GameLogMetrics> metrics) {
        this.allMetrics = metrics;
        this.analysisTime = LocalDateTime.now();
        aggregateMetrics();
    }

    private void aggregateMetrics() {
        totalGames = allMetrics.size();
        successfulGames = (int) allMetrics.stream().filter(GameLogMetrics::isSuccessful).count();
        failedGames = totalGames - successfulGames;
        gamesWithErrors = (int) allMetrics.stream().filter(m -> !m.getErrors().isEmpty()).count();
        gamesWithWarnings = (int) allMetrics.stream().filter(m -> !m.getWarnings().isEmpty()).count();
        totalSendErrors = allMetrics.stream().mapToInt(GameLogMetrics::getSendErrors).sum();

        totalTurns = allMetrics.stream()
                .filter(GameLogMetrics::isGameCompleted)
                .mapToInt(GameLogMetrics::getTurnCount)
                .sum();

        averageTurns = allMetrics.stream()
                .filter(GameLogMetrics::isGameCompleted)
                .mapToInt(GameLogMetrics::getTurnCount)
                .average()
                .orElse(0.0);

        // Per-player-count stats
        statsByPlayerCount = new HashMap<>();
        for (int p = 2; p <= 4; p++) {
            final int pc = p;
            List<GameLogMetrics> filtered = allMetrics.stream()
                    .filter(m -> m.getPlayerCount() == pc)
                    .collect(Collectors.toList());
            if (!filtered.isEmpty()) {
                statsByPlayerCount.put(p, new PlayerCountStats(p, filtered));
            }
        }

        // Failure modes
        failureModeCounts = new EnumMap<>(GameLogMetrics.FailureMode.class);
        for (GameLogMetrics m : allMetrics) {
            failureModeCounts.merge(m.getFailureMode(), 1, Integer::sum);
        }

        // Error frequency (total count), games affected, and which games
        Map<String, Integer> tempErrorFreq = new HashMap<>();
        Map<String, Integer> tempErrorGames = new HashMap<>();
        Map<String, List<String>> tempErrorGameNames = new HashMap<>();
        for (GameLogMetrics m : allMetrics) {
            for (Map.Entry<String, Integer> ec : m.getErrorCounts().entrySet()) {
                tempErrorFreq.merge(ec.getKey(), ec.getValue(), Integer::sum);
                tempErrorGames.merge(ec.getKey(), 1, Integer::sum);
                tempErrorGameNames.computeIfAbsent(ec.getKey(), k -> new ArrayList<>())
                        .add(m.getLogFileName());
            }
        }
        // Sort by total count, keep top 20
        List<String> topErrors = tempErrorFreq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(20)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        errorFrequency = new LinkedHashMap<>();
        errorGameCount = new LinkedHashMap<>();
        errorGameNames = new LinkedHashMap<>();
        for (String key : topErrors) {
            errorFrequency.put(key, tempErrorFreq.get(key));
            errorGameCount.put(key, tempErrorGames.get(key));
            errorGameNames.put(key, tempErrorGameNames.get(key));
        }

        // Winner frequency
        winnerFrequency = new LinkedHashMap<>();
        Map<String, Integer> tempWinnerFreq = new HashMap<>();
        for (GameLogMetrics m : allMetrics) {
            String winner = m.getWinner();
            if (winner != null && !winner.isEmpty()) {
                tempWinnerFreq.merge(winner, 1, Integer::sum);
            }
        }
        winnerFrequency = tempWinnerFreq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));
    }

    private String normalizeError(String error) {
        return NetworkLogAnalyzer.normalizeError(error);
    }

    // Getters

    public int getTotalGames() { return totalGames; }
    public int getSuccessfulGames() { return successfulGames; }
    public int getFailedGames() { return failedGames; }
    public int getTotalSendErrors() { return totalSendErrors; }
    public List<GameLogMetrics> getAllMetrics() { return allMetrics; }

    public double getSuccessRate() {
        if (totalGames == 0) return 0.0;
        return 100.0 * successfulGames / totalGames;
    }

    public PlayerCountStats getStatsByPlayerCount(int playerCount) {
        return statsByPlayerCount.get(playerCount);
    }

    /**
     * Generate a markdown report.
     */
    public String generateReport() {
        StringBuilder sb = new StringBuilder();

        sb.append("## Network Protocol Validation Results\n\n");
        sb.append(String.format("**Analysis Date:** %s\n\n",
                analysisTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));

        // Summary Table
        sb.append("### Summary\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append(String.format("| Total Games | %d |\n", totalGames));
        sb.append(String.format("| Successful | %d |\n", successfulGames));
        sb.append(String.format("| Failed | %d |\n", failedGames));
        sb.append(String.format("| Success Rate | %.1f%% |\n", getSuccessRate()));
        sb.append(String.format("| Total Turns | %d |\n", totalTurns));
        sb.append(String.format("| Average Turns | %.1f |\n", averageTurns));
        sb.append(String.format("| Total Send Errors | %d |\n", totalSendErrors));
        sb.append(String.format("| Games with Errors | %d |\n", gamesWithErrors));
        sb.append(String.format("| Games with Warnings | %d |\n", gamesWithWarnings));
        sb.append("\n");

        // Per-player-count
        if (!statsByPlayerCount.isEmpty()) {
            sb.append("### Results by Player Count\n\n");
            sb.append("| Players | Games | Success Rate | Avg Turns | Send Errors |\n");
            sb.append("|---------|-------|--------------|-----------|-------------|\n");
            for (int p = 2; p <= 4; p++) {
                PlayerCountStats stats = statsByPlayerCount.get(p);
                if (stats != null) {
                    sb.append(String.format("| %d | %d | %.1f%% | %.1f | %d |\n",
                            p, stats.gameCount, stats.successRate,
                            stats.averageTurns, stats.totalSendErrors));
                }
            }
            sb.append("\n");
        }

        // Winner distribution
        if (!winnerFrequency.isEmpty()) {
            sb.append("### Winner Distribution\n\n");
            sb.append("| Player | Wins | % |\n");
            sb.append("|--------|------|---|\n");
            for (Map.Entry<String, Integer> entry : winnerFrequency.entrySet()) {
                double pct = successfulGames > 0 ? 100.0 * entry.getValue() / successfulGames : 0;
                sb.append(String.format("| %s | %d | %.1f%% |\n",
                        entry.getKey(), entry.getValue(), pct));
            }
            sb.append("\n");
        }

        // Failure modes
        if (failedGames > 0) {
            sb.append("### Failure Mode Analysis\n\n");
            sb.append("| Mode | Count | % |\n");
            sb.append("|------|-------|---|\n");
            for (GameLogMetrics.FailureMode mode : GameLogMetrics.FailureMode.values()) {
                if (mode == GameLogMetrics.FailureMode.NONE) continue;
                int count = failureModeCounts.getOrDefault(mode, 0);
                if (count > 0) {
                    double pct = totalGames > 0 ? 100.0 * count / totalGames : 0;
                    sb.append(String.format("| %s | %d | %.1f%% |\n", mode.name(), count, pct));
                }
            }
            sb.append("\n");
        }

        // Top errors
        if (!errorFrequency.isEmpty()) {
            sb.append("### Top Errors (by frequency)\n\n");
            sb.append("| Error Pattern | Games | Count | Log Files |\n");
            sb.append("|---------------|-------|-------|-----------|\n");
            int shown = 0;
            for (Map.Entry<String, Integer> entry : errorFrequency.entrySet()) {
                if (shown++ >= 10) break;
                String errorTruncated = entry.getKey().length() > 80 ?
                        entry.getKey().substring(0, 77) + "..." : entry.getKey();
                int games = errorGameCount.getOrDefault(entry.getKey(), 0);
                List<String> gameNames = errorGameNames.getOrDefault(entry.getKey(), List.of());
                String logFiles = String.join(", ", gameNames);
                sb.append(String.format("| `%s` | %d | %d | %s |\n",
                        errorTruncated, games, entry.getValue(), logFiles));
            }
            sb.append("\n");

            // Error context — one example per distinct error type
            Set<String> seenErrorTypes = new HashSet<>();
            List<NetworkLogAnalyzer.ErrorContext> distinctContexts = new ArrayList<>();
            for (GameLogMetrics m : allMetrics) {
                if (m.getErrorContext() == null) continue;
                String normalized = normalizeError(m.getErrorContext().errorMessage());
                if (seenErrorTypes.add(normalized)) {
                    distinctContexts.add(m.getErrorContext());
                }
            }

            if (!distinctContexts.isEmpty()) {
                sb.append("### Error Context\n\n");
                for (NetworkLogAnalyzer.ErrorContext ctx : distinctContexts) {
                    sb.append(ctx.toMarkdown());
                    sb.append("\n");
                }
            }
        }

        // Validation summary
        sb.append("### Validation Status\n\n");
        boolean passed = passesValidation();

        if (passed) {
            sb.append("**PASSED** - All validation criteria met:\n");
        } else {
            sb.append("**FAILED** - Validation criteria not met:\n");
        }
        sb.append(String.format("- [%s] Success rate >= 90%% (actual: %.1f%%)\n",
                getSuccessRate() >= 90.0 ? "x" : " ", getSuccessRate()));
        sb.append(String.format("- [%s] Zero send errors (actual: %d)\n",
                totalSendErrors == 0 ? "x" : " ", totalSendErrors));

        // Per-player-count success rates
        for (int p = 2; p <= 4; p++) {
            PlayerCountStats stats = statsByPlayerCount.get(p);
            if (stats != null) {
                sb.append(String.format("- [%s] %dp success rate >= 80%% (actual: %.1f%%)\n",
                        stats.successRate >= 80.0 ? "x" : " ", p, stats.successRate));
            }
        }
        sb.append("\n");

        return sb.toString();
    }

    /**
     * Check if validation criteria are met.
     */
    public boolean passesValidation() {
        if (getSuccessRate() < 90.0) return false;
        if (totalSendErrors > 0) return false;
        for (PlayerCountStats stats : statsByPlayerCount.values()) {
            if (stats.successRate < 80.0) return false;
        }
        return true;
    }

    public String toSummary() {
        return String.format(
                "AnalysisResult[total=%d, success=%d (%.1f%%), failed=%d, " +
                "sendErrors=%d, errors=%d, avgTurns=%.1f]",
                totalGames, successfulGames, getSuccessRate(), failedGames,
                totalSendErrors, gamesWithErrors, averageTurns);
    }

    /**
     * Statistics for a specific player count.
     */
    public static class PlayerCountStats {
        public final int playerCount;
        public final int gameCount;
        public final int successCount;
        public final double successRate;
        public final double averageTurns;
        public final int totalSendErrors;

        public PlayerCountStats(int playerCount, List<GameLogMetrics> metrics) {
            this.playerCount = playerCount;
            this.gameCount = metrics.size();
            this.successCount = (int) metrics.stream().filter(GameLogMetrics::isSuccessful).count();
            this.successRate = gameCount > 0 ? 100.0 * successCount / gameCount : 0.0;
            this.averageTurns = metrics.stream()
                    .filter(GameLogMetrics::isGameCompleted)
                    .mapToInt(GameLogMetrics::getTurnCount)
                    .average()
                    .orElse(0.0);
            this.totalSendErrors = metrics.stream().mapToInt(GameLogMetrics::getSendErrors).sum();
        }
    }
}
