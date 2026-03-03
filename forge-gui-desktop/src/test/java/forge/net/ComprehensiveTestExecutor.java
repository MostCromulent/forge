package forge.net;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Orchestrates comprehensive network testing with multiple player counts.
 *
 * Default configuration runs 100 games:
 * - 50 x 2-player games (50%)
 * - 30 x 3-player games (30%)
 * - 20 x 4-player games (20%)
 *
 * Supports both execution modes:
 * - Parallel: Games run in separate JVM processes (default)
 * - Sequential: Games run one-by-one in the same JVM (useful for debugging)
 */
public class ComprehensiveTestExecutor {

    private static final String LOG_PREFIX = "[ComprehensiveTestExecutor]";
    private static final int BASE_PORT = 58000;

    // Default game distribution
    private int twoPlayerGames = 50;
    private int threePlayerGames = 30;
    private int fourPlayerGames = 20;

    // Execution settings
    private int parallelBatchSize = 10;
    private long gameTimeoutMs = 300_000;
    private boolean sequential = false;
    private File logBaseDir;

    public ComprehensiveTestExecutor twoPlayerGames(int count) {
        this.twoPlayerGames = count;
        return this;
    }

    public ComprehensiveTestExecutor threePlayerGames(int count) {
        this.threePlayerGames = count;
        return this;
    }

    public ComprehensiveTestExecutor fourPlayerGames(int count) {
        this.fourPlayerGames = count;
        return this;
    }

    public ComprehensiveTestExecutor parallelBatchSize(int size) {
        this.parallelBatchSize = size;
        return this;
    }

    public ComprehensiveTestExecutor gameTimeout(long timeoutMs) {
        this.gameTimeoutMs = timeoutMs;
        return this;
    }

    public ComprehensiveTestExecutor sequential(boolean seq) {
        this.sequential = seq;
        return this;
    }

    public ComprehensiveTestExecutor logBaseDir(File dir) {
        this.logBaseDir = dir;
        return this;
    }

    public int getTotalGames() {
        return twoPlayerGames + threePlayerGames + fourPlayerGames;
    }

    /**
     * Execute the comprehensive test suite.
     */
    public MultiProcessGameExecutor.ExecutionResult execute() {
        int totalGames = getTotalGames();
        String mode = sequential ? "sequential" : "parallel";
        System.out.printf("%s Starting comprehensive test: %d total games (%s)%n", LOG_PREFIX, totalGames, mode);
        System.out.printf("%s Distribution: %d x 2-player, %d x 3-player, %d x 4-player%n",
                LOG_PREFIX, twoPlayerGames, threePlayerGames, fourPlayerGames);

        long startTime = System.currentTimeMillis();

        int[] playerCounts = buildShuffledPlayerCounts();

        MultiProcessGameExecutor.ExecutionResult result;

        if (sequential) {
            result = executeSequentially(playerCounts);
        } else {
            MultiProcessGameExecutor executor = new MultiProcessGameExecutor(gameTimeoutMs);
            if (logBaseDir != null) {
                executor.setLogBaseDir(logBaseDir);
            }
            result = executor.runGamesInBatches(playerCounts, parallelBatchSize);
        }

        long duration = System.currentTimeMillis() - startTime;
        System.out.printf("%s Comprehensive test completed in %d ms (%.1f minutes)%n",
                LOG_PREFIX, duration, duration / 60000.0);

        return result;
    }

    /**
     * Execute games sequentially in the same JVM.
     */
    private MultiProcessGameExecutor.ExecutionResult executeSequentially(int[] playerCounts) {
        TestUtils.ensureFModelInitialized();

        System.out.printf("%s Starting %d sequential games%n", LOG_PREFIX, playerCounts.length);

        MultiProcessGameExecutor.ExecutionResult result =
                new MultiProcessGameExecutor.ExecutionResult(playerCounts.length);
        int port = BASE_PORT;

        for (int i = 0; i < playerCounts.length; i++) {
            int players = playerCounts[i];

            UnifiedNetworkHarness.GameResult gameResult = UnifiedNetworkHarness.builder()
                    .playerCount(players)
                    .port(port++)
                    .gameTimeoutMs(gameTimeoutMs)
                    .build()
                    .execute();

            result.addResult(i, gameResult);

            System.out.printf("%s Game %d (%dp): %s%n", LOG_PREFIX, i, players, gameResult);
        }

        System.out.printf("%s Sequential execution complete: %s%n", LOG_PREFIX, result.toSummary());
        return result;
    }

    /**
     * Build a shuffled array of player counts based on configuration.
     */
    private int[] buildShuffledPlayerCounts() {
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < twoPlayerGames; i++) counts.add(2);
        for (int i = 0; i < threePlayerGames; i++) counts.add(3);
        for (int i = 0; i < fourPlayerGames; i++) counts.add(4);
        Collections.shuffle(counts);
        return counts.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Generate a summary report of the test configuration.
     */
    public String getConfigurationSummary() {
        int total = getTotalGames();
        return String.format(
                "ComprehensiveTestExecutor Configuration:%n" +
                "  Total Games: %d%n" +
                "  Distribution:%n" +
                "    - 2-player: %d (%.0f%%)%n" +
                "    - 3-player: %d (%.0f%%)%n" +
                "    - 4-player: %d (%.0f%%)%n" +
                "  Execution Mode: %s%n" +
                "  Batch Size: %d%n" +
                "  Game Timeout: %d ms",
                total,
                twoPlayerGames, total > 0 ? (100.0 * twoPlayerGames / total) : 0,
                threePlayerGames, total > 0 ? (100.0 * threePlayerGames / total) : 0,
                fourPlayerGames, total > 0 ? (100.0 * fourPlayerGames / total) : 0,
                sequential ? "Sequential (same JVM)" : "Parallel (multi-process)",
                parallelBatchSize,
                gameTimeoutMs);
    }

    /**
     * Run N sequential 2-player games (convenience method).
     */
    public static MultiProcessGameExecutor.ExecutionResult runSequentialGames(int gameCount, long timeoutMs) {
        return new ComprehensiveTestExecutor()
                .twoPlayerGames(gameCount)
                .threePlayerGames(0)
                .fourPlayerGames(0)
                .gameTimeout(timeoutMs)
                .sequential(true)
                .execute();
    }

    /**
     * Create an executor from system properties.
     * -Dtest.2pGames=50 -Dtest.3pGames=30 -Dtest.4pGames=20
     * -Dtest.batchSize=10 -Dtest.timeoutMs=300000
     */
    public static ComprehensiveTestExecutor fromSystemProperties() {
        ComprehensiveTestExecutor executor = new ComprehensiveTestExecutor();

        String val;

        val = System.getProperty("test.2pGames");
        if (val != null) { try { executor.twoPlayerGames(Integer.parseInt(val)); } catch (NumberFormatException ignored) { } }

        val = System.getProperty("test.3pGames");
        if (val != null) { try { executor.threePlayerGames(Integer.parseInt(val)); } catch (NumberFormatException ignored) { } }

        val = System.getProperty("test.4pGames");
        if (val != null) { try { executor.fourPlayerGames(Integer.parseInt(val)); } catch (NumberFormatException ignored) { } }

        val = System.getProperty("test.batchSize");
        if (val != null) { try { executor.parallelBatchSize(Integer.parseInt(val)); } catch (NumberFormatException ignored) { } }

        val = System.getProperty("test.timeoutMs");
        if (val != null) { try { executor.gameTimeout(Long.parseLong(val)); } catch (NumberFormatException ignored) { } }

        return executor;
    }
}
