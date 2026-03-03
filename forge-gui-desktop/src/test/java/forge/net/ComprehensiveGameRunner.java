package forge.net;

/**
 * Standalone runner for comprehensive network game testing.
 * Designed to be invoked as a separate JVM process for parallel execution.
 *
 * Usage: java -cp <classpath> forge.net.ComprehensiveGameRunner <port> <gameIndex> <playerCount>
 *
 * Exit codes:
 *   0 = Success (game completed)
 *   1 = Failure (game failed)
 *   2 = Error (exception during execution)
 *
 * Output format (for parent process parsing):
 *   RESULT:SUCCESS:playerCount:turnCount:sendErrors:durationMs:winner:failureReason
 */
public class ComprehensiveGameRunner {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: ComprehensiveGameRunner <port> <gameIndex> <playerCount>");
            System.exit(2);
        }

        int port;
        int gameIndex;
        int playerCount;
        try {
            port = Integer.parseInt(args[0]);
            gameIndex = Integer.parseInt(args[1]);
            playerCount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid arguments: port, gameIndex, playerCount must be integers");
            System.exit(2);
            return;
        }

        if (playerCount < 2 || playerCount > 4) {
            System.err.println("Player count must be 2, 3, or 4");
            System.exit(2);
            return;
        }

        System.exit(runGame(port, gameIndex, playerCount));
    }

    /**
     * Run a single game and return exit code.
     */
    public static int runGame(int port, int gameIndex, int playerCount) {
        try {
            TestUtils.ensureFModelInitialized();

            System.out.printf("[ComprehensiveGameRunner] Starting game %d with %d players on port %d%n",
                    gameIndex, playerCount, port);

            UnifiedNetworkHarness.GameResult result = UnifiedNetworkHarness.builder()
                    .playerCount(playerCount)
                    .port(port)
                    .gameTimeoutMs(300_000)  // 5 minute timeout
                    .build()
                    .execute();

            System.out.printf("[ComprehensiveGameRunner] Game %d result: %s%n", gameIndex, result);

            // Output structured result line for parent process
            System.out.println(result.toResultLine());

            return result.success ? 0 : 1;

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            return 2;
        }
    }
}
