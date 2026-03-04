package forge.net.analysis;

import forge.net.MultiProcessGameExecutor;
import forge.net.UnifiedNetworkHarness;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses game process output (stdout/stderr) to extract metrics and errors.
 * Adapted for master's protocol (no delta sync — analyzes stderr traces and structured output).
 */
public class NetworkLogAnalyzer {

    private static final int CONTEXT_LINES_BEFORE = 20;
    private static final int CONTEXT_LINES_AFTER = 5;

    // Patterns for game output parsing
    private static final Pattern GAME_OUTCOME_PATTERN = Pattern.compile(
            "Game completed in (\\d+) turns");

    private static final Pattern TURN_PATTERN = Pattern.compile(
            "Turn (\\d+)");

    private static final Pattern WINNER_PATTERN = Pattern.compile(
            "winner=([^,\\s]+)");

    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "Exception|\\bERROR\\b");

    private static final Pattern TIMEOUT_PATTERN = Pattern.compile(
            "timeout|timed out|did not complete within", Pattern.CASE_INSENSITIVE);

    private static final Pattern WARN_PATTERN = Pattern.compile(
            "WARN|WARNING|Unknown protocol method|Non-serializable", Pattern.CASE_INSENSITIVE);

    // JVM/Netty warnings unrelated to network infrastructure — suppress from analysis
    private static final Pattern SUPPRESSED_WARN_PATTERN = Pattern.compile(
            "sun\\.misc\\.Unsafe|terminally deprecated method|restricted method in java\\.lang\\.System"
            + "|reporting this to the maintainers of.*PlatformDepend"
            + "|enable-native-access|Restricted methods will be blocked"
            + "|System::loadLibrary has been called by",
            Pattern.CASE_INSENSITIVE);

    // Structured result line from ComprehensiveGameRunner: RESULT:SUCCESS:playerCount:turnCount:sendErrors:...
    private static final Pattern RESULT_LINE_PATTERN = Pattern.compile(
            "RESULT:(SUCCESS|FAILURE):(\\d+):(\\d+):(\\d+):");

    private static final Pattern SEND_ERROR_PATTERN = Pattern.compile(
            "send error|Client send error|sendErrors=(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern PLAYER_COUNT_PATTERN = Pattern.compile(
            "(\\d+)-player|(\\d+)p game|playerCount=(\\d+)", Pattern.CASE_INSENSITIVE);

    // Error context records

    public record PlayerState(
        int playerId,
        String playerName,
        int life,
        int handSize,
        int graveyardSize,
        int battlefieldSize
    ) {
        @Override
        public String toString() {
            return String.format("Player %d (%s): Life=%d, Hand=%d, GY=%d, BF=%d",
                    playerId, playerName, life, handSize, graveyardSize, battlefieldSize);
        }
    }

    public record ErrorContext(
        String logFileName,
        int errorLineNumber,
        int turnAtError,
        String phaseAtError,
        List<PlayerState> playerStates,
        List<String> linesBefore,
        List<String> linesAfter,
        List<String> warningsBefore,
        String errorMessage
    ) {
        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("**%s** (error at line %d):\n", logFileName, errorLineNumber));
            if (turnAtError > 0) {
                sb.append(String.format("- Turn: %d\n", turnAtError));
            }

            if (!warningsBefore.isEmpty()) {
                sb.append(String.format("\n- Warnings before error (%d):\n", warningsBefore.size()));
                for (String warning : warningsBefore.subList(0, Math.min(5, warningsBefore.size()))) {
                    sb.append(String.format("  - `%s`\n", truncate(warning, 100)));
                }
            }

            sb.append("\n- Lines around error:\n```\n");
            for (String line : linesBefore) {
                sb.append("  ").append(truncate(line, 120)).append("\n");
            }
            sb.append(">>> ").append(truncate(errorMessage, 120)).append("\n");
            for (String line : linesAfter) {
                sb.append("  ").append(truncate(line, 120)).append("\n");
            }
            sb.append("```\n");

            return sb.toString();
        }

        private static String truncate(String s, int maxLen) {
            if (s == null) return "";
            return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
        }
    }

    /**
     * Analyze a single log/output file.
     */
    public GameLogMetrics analyzeLogFile(File logFile) {
        GameLogMetrics metrics = new GameLogMetrics();
        // Include parent dir name for batch identification (e.g., "20260304-072630/game-0-2p.log")
        String parentName = logFile.getParentFile() != null ? logFile.getParentFile().getName() : "";
        metrics.setLogFileName(parentName.isEmpty() ? logFile.getName() : parentName + "/" + logFile.getName());

        int maxTurn = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;

            while ((line = reader.readLine()) != null) {
                // Turn tracking
                Matcher turnMatcher = TURN_PATTERN.matcher(line);
                if (turnMatcher.find()) {
                    try {
                        int turn = Integer.parseInt(turnMatcher.group(1));
                        if (turn > maxTurn) maxTurn = turn;
                    } catch (NumberFormatException ignored) { }
                }

                // Game completion (also extract turn count from toString output)
                Matcher outcomeMatcher = GAME_OUTCOME_PATTERN.matcher(line);
                if (outcomeMatcher.find()) {
                    metrics.setGameCompleted(true);
                    try {
                        int turns = Integer.parseInt(outcomeMatcher.group(1));
                        if (turns > maxTurn) maxTurn = turns;
                    } catch (NumberFormatException ignored) { }
                }

                // Structured RESULT line from ComprehensiveGameRunner
                Matcher resultMatcher = RESULT_LINE_PATTERN.matcher(line);
                if (resultMatcher.find()) {
                    metrics.setGameCompleted("SUCCESS".equals(resultMatcher.group(1)));
                    try {
                        int pc = Integer.parseInt(resultMatcher.group(2));
                        if (pc >= 2 && pc <= 4) metrics.setPlayerCount(pc);
                    } catch (NumberFormatException ignored) { }
                    try {
                        int turns = Integer.parseInt(resultMatcher.group(3));
                        if (turns > maxTurn) maxTurn = turns;
                    } catch (NumberFormatException ignored) { }
                    try {
                        int se = Integer.parseInt(resultMatcher.group(4));
                        metrics.setSendErrors(se);
                    } catch (NumberFormatException ignored) { }
                }

                // Winner
                Matcher winnerMatcher = WINNER_PATTERN.matcher(line);
                if (winnerMatcher.find()) {
                    metrics.setWinner(winnerMatcher.group(1));
                    metrics.setGameCompleted(true);
                }

                // Player count
                Matcher playerMatcher = PLAYER_COUNT_PATTERN.matcher(line);
                if (playerMatcher.find()) {
                    for (int i = 1; i <= playerMatcher.groupCount(); i++) {
                        String match = playerMatcher.group(i);
                        if (match != null) {
                            try {
                                int count = Integer.parseInt(match);
                                if (count >= 2 && count <= 4) {
                                    metrics.setPlayerCount(count);
                                    break;
                                }
                            } catch (NumberFormatException ignored) { }
                        }
                    }
                }

                // Send errors
                Matcher sendErrMatcher = SEND_ERROR_PATTERN.matcher(line);
                if (sendErrMatcher.find()) {
                    if (sendErrMatcher.group(1) != null) {
                        try {
                            metrics.setSendErrors(Integer.parseInt(sendErrMatcher.group(1)));
                        } catch (NumberFormatException ignored) { }
                    } else {
                        metrics.setSendErrors(metrics.getSendErrors() + 1);
                    }
                }

                // Errors
                if (ERROR_PATTERN.matcher(line).find()) {
                    String truncated = truncateLine(line);
                    metrics.addError(truncated);
                    metrics.incrementErrorCount(normalizeError(truncated));
                    if (metrics.getFirstErrorTurn() < 0 && maxTurn > 0) {
                        metrics.setFirstErrorTurn(maxTurn);
                    }
                    continue;
                }

                // Warnings (skip known JVM/Netty noise)
                if (WARN_PATTERN.matcher(line).find()
                        && !SUPPRESSED_WARN_PATTERN.matcher(line).find()) {
                    metrics.addWarning(truncateLine(line));
                }
            }

            metrics.setTurnCount(maxTurn);
            metrics.setFailureMode(determineFailureMode(metrics));

            if (!metrics.getErrors().isEmpty()) {
                Map<String, ErrorContext> contexts = extractErrorContexts(logFile);
                metrics.setErrorContexts(contexts);
            }

        } catch (IOException e) {
            metrics.addError("Failed to read log file: " + e.getMessage());
            metrics.setFailureMode(GameLogMetrics.FailureMode.EXCEPTION);
        }

        return metrics;
    }

    private GameLogMetrics.FailureMode determineFailureMode(GameLogMetrics metrics) {
        for (String error : metrics.getErrors()) {
            if (TIMEOUT_PATTERN.matcher(error).find()) {
                return GameLogMetrics.FailureMode.TIMEOUT;
            }
        }
        if (!metrics.getErrors().isEmpty()) {
            return GameLogMetrics.FailureMode.EXCEPTION;
        }
        if (!metrics.isGameCompleted()) {
            return GameLogMetrics.FailureMode.INCOMPLETE;
        }
        return GameLogMetrics.FailureMode.NONE;
    }

    /**
     * Build an AnalysisResult from a list of GameLogMetrics.
     */
    public AnalysisResult buildAnalysisResult(List<GameLogMetrics> metrics) {
        return new AnalysisResult(metrics);
    }

    /**
     * Build an AnalysisResult from execution results, enriched with log file analysis.
     */
    public AnalysisResult buildFromExecutionResults(
            MultiProcessGameExecutor.ExecutionResult execResult) {
        // Parse log files from all batch directories.
        // Each batch dir has game-0 through game-9, so use a running offset
        // to map to global game indices (0-99).
        Map<Integer, GameLogMetrics> logMetricsByGame = new HashMap<>();
        int batchOffset = 0;
        for (File logDir : execResult.getLogDirs()) {
            if (logDir != null && logDir.isDirectory()) {
                File[] logFiles = logDir.listFiles((dir, name) -> name.endsWith(".log"));
                if (logFiles != null) {
                    int batchSize = logFiles.length;
                    for (File logFile : logFiles) {
                        GameLogMetrics logMetrics = analyzeLogFile(logFile);
                        // Extract local game index from filename (e.g., "game-0-2p.log" → 0)
                        String name = logFile.getName();
                        if (name.startsWith("game-")) {
                            try {
                                int dashIdx = name.indexOf('-', 5);
                                if (dashIdx > 0) {
                                    int localIdx = Integer.parseInt(name.substring(5, dashIdx));
                                    logMetricsByGame.put(batchOffset + localIdx, logMetrics);
                                }
                            } catch (NumberFormatException ignored) { }
                        }
                    }
                    batchOffset += batchSize;
                }
            }
        }

        List<GameLogMetrics> metricsList = new ArrayList<>();

        for (var entry : execResult.getResults().entrySet()) {
            int idx = entry.getKey();
            UnifiedNetworkHarness.GameResult gr = entry.getValue();

            GameLogMetrics logMetrics = logMetricsByGame.get(idx);

            GameLogMetrics m = new GameLogMetrics();
            // Use the log file name from analysis (includes batch dir) if available
            m.setLogFileName(logMetrics != null ? logMetrics.getLogFileName() : "game" + idx);
            m.setGameIndex(idx);
            m.setPlayerCount(gr.playerCount);
            m.setGameCompleted(gr.success);
            m.setTurnCount(gr.turnCount);
            m.setWinner(gr.winner);
            m.setSendErrors(gr.sendErrors);

            if (!gr.errors.isEmpty()) {
                for (String e : gr.errors) {
                    m.addError(e);
                }
            }
            if (gr.failureReason != null && !gr.success) {
                m.addError(gr.failureReason);
            }

            // Merge errors, error counts, warnings, and error contexts from log file analysis
            if (logMetrics != null) {
                for (String logError : logMetrics.getErrors()) {
                    if (!m.getErrors().contains(logError)) {
                        m.addError(logError);
                    }
                }
                for (Map.Entry<String, Integer> ec : logMetrics.getErrorCounts().entrySet()) {
                    m.getErrorCounts().merge(ec.getKey(), ec.getValue(), Integer::sum);
                }
                for (String logWarning : logMetrics.getWarnings()) {
                    m.addWarning(logWarning);
                }
                for (Map.Entry<String, NetworkLogAnalyzer.ErrorContext> ctx : logMetrics.getErrorContexts().entrySet()) {
                    m.getErrorContexts().putIfAbsent(ctx.getKey(), ctx.getValue());
                }
            }

            m.setFailureMode(gr.success ? GameLogMetrics.FailureMode.NONE :
                    (gr.failureReason != null && gr.failureReason.contains("timeout") ?
                            GameLogMetrics.FailureMode.TIMEOUT : GameLogMetrics.FailureMode.EXCEPTION));

            metricsList.add(m);
        }

        return new AnalysisResult(metricsList);
    }

    /**
     * Extract error context for the first occurrence of each distinct error type in the log file.
     * Returns a map from normalized error pattern to its ErrorContext.
     */
    private Map<String, ErrorContext> extractErrorContexts(File logFile) {
        List<String> allLines = new ArrayList<>();
        // Track the first occurrence of each distinct normalized error
        Map<String, Integer> firstOccurrenceByType = new LinkedHashMap<>();
        Map<String, String> rawMessageByType = new LinkedHashMap<>();
        int currentTurn = 0;
        int turnAtFirstError = 0;
        List<String> warningsBefore = new ArrayList<>();
        boolean seenAnyError = false;

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                allLines.add(line);

                Matcher turnMatcher = TURN_PATTERN.matcher(line);
                if (turnMatcher.find()) {
                    try { currentTurn = Integer.parseInt(turnMatcher.group(1)); }
                    catch (NumberFormatException ignored) { }
                }

                if (!seenAnyError && WARN_PATTERN.matcher(line).find()
                        && !SUPPRESSED_WARN_PATTERN.matcher(line).find()) {
                    warningsBefore.add(line);
                    if (warningsBefore.size() > 50) warningsBefore.remove(0);
                }

                if (ERROR_PATTERN.matcher(line).find()) {
                    if (!seenAnyError) {
                        turnAtFirstError = currentTurn;
                        seenAnyError = true;
                    }
                    String normalized = normalizeError(line);
                    if (!firstOccurrenceByType.containsKey(normalized)) {
                        firstOccurrenceByType.put(normalized, lineNumber - 1);
                        rawMessageByType.put(normalized, line);
                    }
                }
            }
        } catch (IOException e) {
            return Map.of();
        }

        if (firstOccurrenceByType.isEmpty()) return Map.of();

        // Use batch-qualified filename (e.g., "20260304-072630/game-0-2p.log")
        String parentName = logFile.getParentFile() != null ? logFile.getParentFile().getName() : "";
        String qualifiedName = parentName.isEmpty() ? logFile.getName() : parentName + "/" + logFile.getName();

        Map<String, ErrorContext> contexts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : firstOccurrenceByType.entrySet()) {
            int errorLineIndex = entry.getValue();
            int startIndex = Math.max(0, errorLineIndex - CONTEXT_LINES_BEFORE);
            int endIndex = Math.min(allLines.size() - 1, errorLineIndex + CONTEXT_LINES_AFTER);

            List<String> linesBefore = new ArrayList<>(allLines.subList(startIndex, errorLineIndex));
            List<String> linesAfter = errorLineIndex + 1 <= endIndex
                    ? new ArrayList<>(allLines.subList(errorLineIndex + 1, endIndex + 1))
                    : new ArrayList<>();

            contexts.put(entry.getKey(), new ErrorContext(
                    qualifiedName, errorLineIndex + 1, turnAtFirstError, null,
                    new ArrayList<>(), linesBefore, linesAfter,
                    new ArrayList<>(warningsBefore), rawMessageByType.get(entry.getKey())));
        }
        return contexts;
    }

    private String truncateLine(String line) {
        return line.length() > 200 ? line.substring(0, 197) + "..." : line;
    }

    static String normalizeError(String error) {
        String normalized = error.replaceAll("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\]", "");
        normalized = normalized.replaceAll("id=\\d+", "id=X");
        normalized = normalized.replaceAll("\\d{5,}", "NNNN");
        return normalized.trim();
    }
}
