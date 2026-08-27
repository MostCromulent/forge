package forge.net;

import forge.gamemodes.net.NetworkLogConfig;
import forge.util.IHasForgeLog;

/**
 * Runs a single {@link HeadlessNetworkClient} in its own JVM, connecting to a host
 * over TCP.
 *
 * <p>The in-process harness puts host and clients in one JVM, so they share the AWT
 * event thread. A human-controlled remote player makes the server block that thread in
 * {@code syncAndSendAndWait} while the client's reply needs the same thread to be
 * dispatched — a deadlock that only exists in the test harness, since a real host and
 * client are separate processes. Running clients out-of-process removes it, and matches
 * production more closely.
 *
 * <p>Mirrors the sequence in {@code UnifiedNetworkHarness.runRemoteClientThread}.
 * Prints a single {@code RESULT:} line for the parent to parse, as
 * {@code ComprehensiveGameRunner} does.
 *
 * <p>Args: username hostname port staggerMs connectTimeoutMs gameTimeoutMs [batchId]
 */
public final class HeadlessClientRunner implements IHasForgeLog {

    private HeadlessClientRunner() {}

    public static void main(String[] args) {
        int exit = 1;
        HeadlessNetworkClient client = null;
        try {
            if (args.length < 6) {
                System.out.println("RESULT:FAIL:usage");
                System.exit(2);
            }
            final String username = args[0];
            final String hostname = args[1];
            final int port = Integer.parseInt(args[2]);
            final long staggerMs = Long.parseLong(args[3]);
            final long connectTimeoutMs = Long.parseLong(args[4]);
            final long gameTimeoutMs = Long.parseLong(args[5]);

            // Must precede any NetworkLogConfig call: log setup reads preferences, which
            // reach ForgeConstants' static init, which needs GuiBase's interface set.
            TestUtils.ensureFModelInitialized();

            NetworkLogConfig.setTestMode(true);
            if (args.length > 6 && args[6] != null && !args[6].isEmpty()) {
                NetworkLogConfig.setBatchId(args[6]);
            }
            NetworkLogConfig.setInstanceSuffix("client-" + username.replaceAll("\\W+", ""));

            // Same stagger the in-process path uses: each client's LoginEvent must be
            // fully processed before the next connects.
            Thread.sleep(staggerMs);

            client = new HeadlessNetworkClient(username, hostname, port);
            if (!client.connect(connectTimeoutMs)) {
                System.out.println("RESULT:FAIL:connect");
                System.exit(1);
            }

            Thread.sleep(500);
            client.setReady();
            Thread.sleep(200);

            // Parent waits on this before starting the game.
            System.out.println("CONNECTED:slot=" + client.getAssignedSlot());
            System.out.flush();

            client.waitForGameStart(gameTimeoutMs);
            client.waitForGameFinish(gameTimeoutMs);

            // What this client ended up holding, for the parent to compare against what the
            // server ended up holding. Reported separately from RESULT so a mismatch can be
            // told apart from a client that never got that far.
            System.out.println("STATE:" + UnifiedNetworkHarness.stateDigest(client.getGameView()));

            System.out.println("RESULT:OK"
                    + ":slot=" + client.getAssignedSlot()
                    + ":deltas=" + client.getDeltaPacketsReceived()
                    + ":bytes=" + client.getTotalDeltaBytes()
                    + ":mismatches=" + client.getEventStateMismatches());
            exit = 0;
        } catch (Throwable t) {
            System.out.println("RESULT:FAIL:" + t.getClass().getSimpleName() + ":" + t.getMessage());
            t.printStackTrace();
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                    // shutting down anyway
                }
            }
            NetworkLogConfig.closeThreadLogger();
        }
        System.exit(exit);
    }
}
