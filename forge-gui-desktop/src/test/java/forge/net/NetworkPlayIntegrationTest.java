package forge.net;

import forge.deck.Deck;
import forge.localinstance.properties.ForgeConstants;
import forge.net.analysis.AnalysisResult;
import forge.net.analysis.NetworkLogAnalyzer;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Integration test suite for network protocol validation on master.
 *
 * Focuses on error detection:
 * - Serialization failures (NotSerializableException)
 * - Protocol dispatch errors (wrong args, missing methods)
 * - Send errors in Netty pipeline
 * - Game hangs / timeouts
 * - Client disconnections
 *
 * Test categories:
 * - Unit tests: Always run in CI (deck loader, server start/stop)
 * - Single-game test: testTrueNetworkTraffic (CI, 10-card land decks)
 * - Batch tests: Gated behind -Drun.stress.tests=true (precon decks)
 *
 * Validation criteria:
 * | Metric                    | Quick (10 games) | Comprehensive (100 games) |
 * |---------------------------|------------------|---------------------------|
 * | Success rate              | >= 80%           | >= 90%                    |
 * | Send errors               | 0 total          | 0 total                   |
 * | Per-player-count success  | >= 70%           | >= 80%                    |
 */
public class NetworkPlayIntegrationTest {

    private static boolean initialized = false;
    private static File networkLogDir;

    @BeforeClass
    public static void setUp() {
        if (!initialized) {
            TestUtils.ensureFModelInitialized();
            networkLogDir = new File(ForgeConstants.USER_DIR, "networklogs");
            networkLogDir.mkdirs();
            initialized = true;
        }
    }

    // ==================== Unit Tests ====================

    @Test
    public void testDeckLoaderHasPrecons() {
        Assert.assertTrue(TestDeckLoader.hasPrecons(), "Should have precon decks available");
        int count = TestDeckLoader.getPreconCount();
        Assert.assertTrue(count > 0, "Should have at least one precon deck, found: " + count);
        System.out.printf("[NetworkPlayIntegrationTest] Found %d precon decks%n", count);
    }

    @Test(dependsOnMethods = "testDeckLoaderHasPrecons")
    public void testDeckLoaderCanLoadDeck() {
        Deck deck = TestDeckLoader.getRandomPrecon();
        Assert.assertNotNull(deck, "Deck should not be null");
        int cardCount = deck.getMain().countAll();
        Assert.assertTrue(cardCount >= 40, "Deck should have at least 40 cards, found: " + cardCount);
        System.out.printf("[NetworkPlayIntegrationTest] Loaded deck with %d cards%n", cardCount);
    }

    @Test
    public void testServerStartAndStop() {
        var server = forge.gamemodes.net.server.FServerManager.getInstance();
        int port = PortAllocator.allocatePort();

        try {
            server.startServer(port);
            Assert.assertTrue(server.isHosting(), "Server should be hosting after start");
        } finally {
            if (server.isHosting()) {
                server.stopServer();
            }
        }
    }

    // ==================== Single Game Integration Test ====================

    /**
     * Fast CI test with 10-card basic land decks.
     * Validates the full network stack without precon deck overhead.
     */
    @Test(timeOut = 60000, description = "True network traffic test with remote client")
    public void testTrueNetworkTraffic() {
        System.out.println("[NetworkPlayIntegrationTest] Starting true network traffic test...");

        Deck deck1 = TestDeckLoader.createMinimalDeck("Mountain", 10);
        Deck deck2 = TestDeckLoader.createMinimalDeck("Forest", 10);

        UnifiedNetworkHarness.GameResult result = UnifiedNetworkHarness.builder()
                .playerCount(2)
                .addDeck(deck1)
                .addDeck(deck2)
                .gameTimeoutMs(45_000)
                .build()
                .execute();

        Assert.assertTrue(result.gameStarted, "Game should have started: " + result);
        Assert.assertTrue(result.success, "Game should have completed successfully: " + result);
        Assert.assertTrue(result.turnCount > 0, "Game should have at least one turn");
        Assert.assertEquals(result.sendErrors, 0,
                "Should have zero send errors, had " + result.sendErrors);

        System.out.printf("[NetworkPlayIntegrationTest] Test PASSED: %s%n", result);
    }

    // ==================== Batch Tests ====================

    /**
     * Configurable sequential batch test.
     * -Dtest.gameCount=3 -Dtest.timeoutMs=300000 -Drun.stress.tests=true
     */
    @Test(timeOut = 3600000, description = "Configurable sequential batch")
    public void testConfigurableSequential() {
        TestUtils.skipUnlessStressTestsEnabled();

        int gameCount = Integer.getInteger("test.gameCount", 3);
        long timeoutMs = Long.getLong("test.timeoutMs", 300_000);

        System.out.printf("[NetworkPlayIntegrationTest] Sequential: %d games, timeout=%dms%n",
                gameCount, timeoutMs);

        MultiProcessGameExecutor.ExecutionResult result =
                ComprehensiveTestExecutor.runSequentialGames(gameCount, timeoutMs);

        System.out.println(result.toDetailedReport());

        Assert.assertEquals(result.getSuccessCount(), gameCount,
                "All games should succeed: " + result.toSummary());
        Assert.assertEquals(result.getTotalSendErrors(), 0,
                "Should have zero send errors: " + result.toSummary());
    }

    // ==================== Comprehensive Tests ====================

    /**
     * Comprehensive 100-game network protocol validation.
     *
     * Configurable via system properties:
     *   -Dtest.2pGames=50 -Dtest.3pGames=30 -Dtest.4pGames=20
     *   -Dtest.batchSize=10 -Dtest.timeoutMs=300000
     */
    @Test(timeOut = 7200000, description = "Comprehensive 100-game network validation")
    public void runComprehensiveDeltaSyncTest() {
        TestUtils.skipUnlessStressTestsEnabled();

        ComprehensiveTestExecutor executor = ComprehensiveTestExecutor.fromSystemProperties()
                .logBaseDir(networkLogDir);
        System.out.println(executor.getConfigurationSummary());

        long startTime = System.currentTimeMillis();
        MultiProcessGameExecutor.ExecutionResult executionResult = executor.execute();
        long duration = System.currentTimeMillis() - startTime;

        System.out.printf("[NetworkPlayIntegrationTest] Completed in %.1f minutes%n", duration / 60000.0);
        System.out.println(executionResult.toDetailedReport());
        printLogLocation(executionResult);

        // Build analysis from execution results
        NetworkLogAnalyzer analyzer = new NetworkLogAnalyzer();
        AnalysisResult analysisResult = analyzer.buildFromExecutionResults(executionResult);

        String report = analysisResult.generateReport();
        System.out.println("\n" + report);
        saveReportToFile(report, "comprehensive");

        // Validate: 90% success, 0 send errors, 80% per-player-count
        validateResults(executionResult, 90.0, 80.0);
    }

    /**
     * Quick 10-game validation (5x2p, 3x3p, 2x4p).
     */
    @Test(timeOut = 1200000, description = "Quick 10-game network validation")
    public void runQuickDeltaSyncTest() {
        TestUtils.skipUnlessStressTestsEnabled();

        ComprehensiveTestExecutor executor = new ComprehensiveTestExecutor()
                .twoPlayerGames(5)
                .threePlayerGames(3)
                .fourPlayerGames(2)
                .parallelBatchSize(10)
                .logBaseDir(networkLogDir);

        MultiProcessGameExecutor.ExecutionResult executionResult = executor.execute();
        System.out.println(executionResult.toDetailedReport());
        printLogLocation(executionResult);

        NetworkLogAnalyzer analyzer = new NetworkLogAnalyzer();
        AnalysisResult analysisResult = analyzer.buildFromExecutionResults(executionResult);

        String report = analysisResult.generateReport();
        System.out.println("\n" + report);
        saveReportToFile(report, "quick");

        // Validate: 80% success, 0 send errors, 70% per-player-count
        validateResults(executionResult, 80.0, 70.0);
    }

    // ==================== Helper Methods ====================

    private void validateResults(MultiProcessGameExecutor.ExecutionResult result,
                                  double requiredSuccessRate,
                                  double requiredPerPlayerRate) {
        // 1. Overall success rate
        double successRate = result.getSuccessRate() * 100;
        System.out.printf("[Validation] Success rate: %.1f%% (target: %.0f%%)%n",
                successRate, requiredSuccessRate);
        Assert.assertTrue(successRate >= requiredSuccessRate,
                String.format("Success rate should be >= %.0f%%, was %.1f%%",
                        requiredSuccessRate, successRate));

        // 2. Zero send errors
        int sendErrors = result.getTotalSendErrors();
        System.out.printf("[Validation] Send errors: %d (target: 0)%n", sendErrors);
        Assert.assertEquals(sendErrors, 0,
                "Should have zero send errors, had " + sendErrors);

        // 3. Per-player-count success rates
        for (int p = 2; p <= 4; p++) {
            int count = result.getGameCountByPlayers(p);
            if (count > 0) {
                double playerRate = result.getSuccessRateByPlayers(p) * 100;
                System.out.printf("[Validation] %d-player: %.1f%% (%d games, target: %.0f%%)%n",
                        p, playerRate, count, requiredPerPlayerRate);
                Assert.assertTrue(playerRate >= requiredPerPlayerRate,
                        String.format("%d-player success rate should be >= %.0f%%, was %.1f%%",
                                p, requiredPerPlayerRate, playerRate));
            }
        }

        System.out.println("[Validation] All criteria PASSED");
    }

    private void printLogLocation(MultiProcessGameExecutor.ExecutionResult result) {
        for (File logDir : result.getLogDirs()) {
            System.out.println("Per-game logs saved to: " + logDir.getAbsolutePath());
        }
    }

    private void saveReportToFile(String report, String prefix) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String filename = "network-test-" + prefix + "-" + timestamp + "-results.md";

        // Save to networklogs directory
        if (networkLogDir != null) {
            try {
                File reportFile = new File(networkLogDir, filename);
                try (FileWriter writer = new FileWriter(reportFile)) {
                    writer.write(report);
                }
                System.out.println("Report saved to: " + reportFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("WARNING: Failed to save report to networklogs: " + e.getMessage());
            }
        }
    }
}
