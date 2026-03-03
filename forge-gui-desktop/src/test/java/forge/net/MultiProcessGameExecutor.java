package forge.net;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes multiple network games in parallel using separate JVM processes.
 *
 * Each game runs in its own JVM process, avoiding the FServerManager singleton
 * limitation that prevents true parallelism within a single process.
 */
public class MultiProcessGameExecutor {

    private static final String LOG_PREFIX = "[MultiProcessGameExecutor]";
    private static final int BASE_PORT = 58000;
    private static final long DEFAULT_TIMEOUT_MS = 300_000;

    private final long timeoutMs;
    private final int basePort;
    private File logBaseDir;

    public MultiProcessGameExecutor() {
        this(DEFAULT_TIMEOUT_MS);
    }

    public MultiProcessGameExecutor(long timeoutMs) {
        this(timeoutMs, BASE_PORT);
    }

    public MultiProcessGameExecutor(long timeoutMs, int basePort) {
        this.timeoutMs = timeoutMs;
        this.basePort = basePort;
    }

    public void setLogBaseDir(File logBaseDir) {
        this.logBaseDir = logBaseDir;
    }

    /**
     * Run multiple games with specified player counts in parallel.
     */
    public ExecutionResult runGamesWithPlayerCounts(int[] playerCounts) {
        int gameCount = playerCounts.length;

        System.out.printf("%s Starting %d games in PARALLEL%n", LOG_PREFIX, gameCount);

        List<ProcessInfo> processes = new ArrayList<>();
        List<ProcessMonitor> monitors = new ArrayList<>();
        ExecutionResult result = new ExecutionResult(gameCount);

        // Create per-run log directory
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File baseDir = logBaseDir != null ? logBaseDir : new File("target/network-test-logs");
        File logDir = new File(baseDir, timestamp);
        logDir.mkdirs();
        result.setLogDir(logDir);
        System.out.printf("%s Game logs: %s%n", LOG_PREFIX, logDir.getAbsolutePath());

        try {
            String classpath = System.getProperty("java.class.path");
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + File.separator + "bin" + File.separator + "java";

            for (int i = 0; i < gameCount; i++) {
                int port = basePort + i;
                int playerCount = playerCounts[i];
                File logFile = new File(logDir, "game-" + i + "-" + playerCount + "p.log");
                ProcessInfo info = startGameProcess(javaBin, classpath, port, i, playerCount, logFile);
                processes.add(info);

                ProcessMonitor monitor = new ProcessMonitor(info);
                monitor.startReading();
                monitors.add(monitor);

                System.out.printf("%s Started process for game%d (%d players, port %d, pid %d)%n",
                        LOG_PREFIX, i, playerCount, port, info.process.pid());
            }

            // Wait for all processes in parallel
            CountDownLatch allDone = new CountDownLatch(gameCount);

            for (int i = 0; i < gameCount; i++) {
                final int index = i;
                final ProcessMonitor monitor = monitors.get(i);
                new Thread(() -> {
                    try {
                        monitor.waitForCompletion(timeoutMs, result);
                    } finally {
                        allDone.countDown();
                    }
                }, "wait-game" + index).start();
            }

            allDone.await();

        } catch (Exception e) {
            System.err.printf("%s Error: %s%n", LOG_PREFIX, e.getMessage());
            e.printStackTrace();
        }

        System.out.printf("%s Execution complete: %s%n", LOG_PREFIX, result.toSummary());
        return result;
    }

    /**
     * Run games in sequential batches to avoid overwhelming the system.
     */
    public ExecutionResult runGamesInBatches(int[] playerCounts, int batchSize) {
        int totalGames = playerCounts.length;
        int totalBatches = (totalGames + batchSize - 1) / batchSize;

        System.out.printf("%s Running %d games in %d batches of %d%n",
                LOG_PREFIX, totalGames, totalBatches, batchSize);

        ExecutionResult aggregatedResult = new ExecutionResult(totalGames);
        int currentPortOffset = 0;

        for (int batchStart = 0; batchStart < totalGames; batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, totalGames);
            int batchLength = batchEnd - batchStart;
            int batchNumber = batchStart / batchSize;

            int[] batchPlayerCounts = new int[batchLength];
            System.arraycopy(playerCounts, batchStart, batchPlayerCounts, 0, batchLength);

            System.out.printf("%s Starting batch %d (games %d-%d of %d)%n",
                    LOG_PREFIX, batchNumber, batchStart, batchEnd - 1, totalGames);

            MultiProcessGameExecutor batchExecutor = new MultiProcessGameExecutor(
                    this.timeoutMs, this.basePort + currentPortOffset);
            if (this.logBaseDir != null) {
                batchExecutor.setLogBaseDir(this.logBaseDir);
            }

            ExecutionResult batchResult = batchExecutor.runGamesWithPlayerCounts(batchPlayerCounts);

            // Use the first batch's log directory for the aggregated result
            if (aggregatedResult.getLogDir() == null && batchResult.getLogDir() != null) {
                aggregatedResult.setLogDir(batchResult.getLogDir().getParentFile());
            }

            // Merge batch results
            for (Map.Entry<Integer, UnifiedNetworkHarness.GameResult> entry : batchResult.getResults().entrySet()) {
                int originalIndex = batchStart + entry.getKey();
                aggregatedResult.addResult(originalIndex, entry.getValue());
            }

            currentPortOffset += batchLength;

            // Brief delay between batches for port cleanup
            if (batchNumber < totalBatches - 1) {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        System.out.printf("%s All batches complete: %s%n", LOG_PREFIX, aggregatedResult.toSummary());
        return aggregatedResult;
    }

    private ProcessInfo startGameProcess(String javaBin, String classpath, int port, int gameIndex, int playerCount,
                                         File logFile) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-cp");
        command.add(classpath);
        command.add("-Xmx1g");
        command.add("forge.net.ComprehensiveGameRunner");
        command.add(String.valueOf(port));
        command.add(String.valueOf(gameIndex));
        command.add(String.valueOf(playerCount));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        return new ProcessInfo(gameIndex, port, playerCount, process, logFile);
    }

    /**
     * Monitors a single game process.
     */
    private class ProcessMonitor {
        private final ProcessInfo info;
        private final AtomicReference<String> resultLine = new AtomicReference<>();
        private final CountDownLatch readerDone = new CountDownLatch(1);

        ProcessMonitor(ProcessInfo info) {
            this.info = info;
        }

        void startReading() {
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(info.process.getInputStream()));
                     BufferedWriter logWriter = new BufferedWriter(
                        new FileWriter(info.logFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.printf("[Game %d] %s%n", info.gameIndex, line);
                        logWriter.write(line);
                        logWriter.newLine();
                        if (line.startsWith("RESULT:")) {
                            resultLine.set(line);
                        }
                    }
                    logWriter.flush();
                } catch (Exception e) {
                    System.err.printf("%s Error reading output for game %d: %s%n",
                            LOG_PREFIX, info.gameIndex, e.getMessage());
                } finally {
                    readerDone.countDown();
                }
            }, "process-reader-" + info.gameIndex);
            readerThread.setDaemon(true);
            readerThread.start();
        }

        void waitForCompletion(long timeoutMs, ExecutionResult result) {
            try {
                boolean completed = info.process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

                if (!completed) {
                    info.process.destroyForcibly();
                    result.addResult(info.gameIndex, UnifiedNetworkHarness.GameResult.timeout(
                            0, timeoutMs, info.playerCount));
                    return;
                }

                readerDone.await(5, TimeUnit.SECONDS);

                if (resultLine.get() != null) {
                    UnifiedNetworkHarness.GameResult gameResult =
                            UnifiedNetworkHarness.GameResult.fromResultLine(resultLine.get());
                    result.addResult(info.gameIndex, gameResult);
                } else {
                    int exitCode = info.process.exitValue();
                    result.addResult(info.gameIndex, UnifiedNetworkHarness.GameResult.failure(
                            "Process exited without result (exit code " + exitCode + ")",
                            0, info.playerCount));
                }
            } catch (Exception e) {
                result.addError(info.gameIndex, "Exception: " + e.getMessage());
            }
        }
    }

    private static class ProcessInfo {
        final int gameIndex;
        final int port;
        final int playerCount;
        final Process process;
        final File logFile;

        ProcessInfo(int gameIndex, int port, int playerCount, Process process, File logFile) {
            this.gameIndex = gameIndex;
            this.port = port;
            this.playerCount = playerCount;
            this.process = process;
            this.logFile = logFile;
        }
    }

    /**
     * Aggregated results from parallel execution.
     */
    public static class ExecutionResult {
        private final int totalGames;
        private final Map<Integer, UnifiedNetworkHarness.GameResult> results = new ConcurrentHashMap<>();
        private final Map<Integer, String> errors = new ConcurrentHashMap<>();
        private final Map<Integer, Boolean> timeouts = new ConcurrentHashMap<>();
        private File logDir;

        public ExecutionResult(int totalGames) {
            this.totalGames = totalGames;
        }

        public void setLogDir(File logDir) { this.logDir = logDir; }
        public File getLogDir() { return logDir; }

        public void addResult(int idx, UnifiedNetworkHarness.GameResult r) {
            results.put(idx, r);
        }

        public void addError(int idx, String msg) {
            errors.put(idx, msg);
        }

        public void addTimeout(int idx) {
            timeouts.put(idx, true);
        }

        public int getTotalGames() { return totalGames; }

        public int getSuccessCount() {
            return (int) results.values().stream().filter(r -> r.success).count();
        }

        public int getFailureCount() {
            return (int) results.values().stream().filter(r -> !r.success).count();
        }

        public int getErrorCount() { return errors.size(); }
        public int getTimeoutCount() { return timeouts.size(); }

        public int getTotalSendErrors() {
            return results.values().stream().mapToInt(r -> r.sendErrors).sum();
        }

        public int getTotalTurns() {
            return results.values().stream()
                    .filter(r -> r.success)
                    .mapToInt(r -> r.turnCount)
                    .sum();
        }

        public Map<Integer, UnifiedNetworkHarness.GameResult> getResults() {
            return results;
        }

        public double getSuccessRate() {
            if (totalGames == 0) return 0.0;
            return (double) getSuccessCount() / totalGames;
        }

        // Per-player-count aggregation

        public int getGameCountByPlayers(int playerCount) {
            return (int) results.values().stream()
                    .filter(r -> r.playerCount == playerCount).count();
        }

        public int getSuccessCountByPlayers(int playerCount) {
            return (int) results.values().stream()
                    .filter(r -> r.playerCount == playerCount && r.success).count();
        }

        public double getSuccessRateByPlayers(int playerCount) {
            long total = results.values().stream()
                    .filter(r -> r.playerCount == playerCount).count();
            if (total == 0) return 0.0;
            long success = results.values().stream()
                    .filter(r -> r.playerCount == playerCount && r.success).count();
            return (double) success / total;
        }

        public double getAverageTurnsByPlayers(int playerCount) {
            return results.values().stream()
                    .filter(r -> r.playerCount == playerCount && r.success)
                    .mapToInt(r -> r.turnCount)
                    .average()
                    .orElse(0.0);
        }

        public String toSummary() {
            return String.format(
                    "MultiProcess[games=%d, success=%d, failed=%d, errors=%d, timeouts=%d, " +
                    "sendErrors=%d, totalTurns=%d, successRate=%.0f%%]",
                    totalGames, getSuccessCount(), getFailureCount(),
                    getErrorCount(), getTimeoutCount(),
                    getTotalSendErrors(), getTotalTurns(), getSuccessRate() * 100);
        }

        public String toDetailedReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("=".repeat(80)).append("\n");
            sb.append("Multi-Process Game Execution Report\n");
            sb.append("=".repeat(80)).append("\n\n");

            sb.append("Overall Metrics:\n");
            sb.append("-".repeat(40)).append("\n");
            sb.append(String.format("  Total Games:     %d%n", totalGames));
            sb.append(String.format("  Successful:      %d (%.0f%%)%n", getSuccessCount(), getSuccessRate() * 100));
            sb.append(String.format("  Failed:          %d%n", getFailureCount()));
            sb.append(String.format("  Errors:          %d%n", getErrorCount()));
            sb.append(String.format("  Timeouts:        %d%n", getTimeoutCount()));
            sb.append(String.format("  Total Turns:     %d%n", getTotalTurns()));
            sb.append(String.format("  Send Errors:     %d%n", getTotalSendErrors()));
            sb.append("\n");

            // Breakdown by player count
            sb.append("Breakdown by Player Count:\n");
            sb.append("-".repeat(40)).append("\n");
            for (int p = 2; p <= 4; p++) {
                int count = getGameCountByPlayers(p);
                if (count > 0) {
                    sb.append(String.format("  %d-player: %d games, %d success (%.0f%%), avg turns %.1f%n",
                            p, count, getSuccessCountByPlayers(p),
                            getSuccessRateByPlayers(p) * 100, getAverageTurnsByPlayers(p)));
                }
            }
            sb.append("\n");

            // Individual results
            sb.append("Individual Game Results:\n");
            sb.append("-".repeat(40)).append("\n");
            for (int i = 0; i < totalGames; i++) {
                sb.append(String.format("Game %d: ", i));
                if (timeouts.containsKey(i)) {
                    sb.append("TIMEOUT\n");
                } else if (errors.containsKey(i)) {
                    sb.append("ERROR - ").append(errors.get(i)).append("\n");
                } else if (results.containsKey(i)) {
                    sb.append(results.get(i)).append("\n");
                } else {
                    sb.append("NO RESULT\n");
                }
            }

            return sb.toString();
        }
    }
}
