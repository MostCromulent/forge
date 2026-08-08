package forge.net;

import forge.deck.Deck;
import forge.gamemodes.net.NetworkLogConfig;
import forge.util.IHasForgeLog;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Plays a network game in which the remote client dies part-way through and comes back.
 *
 * <p>Nothing else covers this. The server treats a returning client as a reconnection purely
 * because it logs in under a name it is holding a slot for, so exercising it needs a client
 * process that can be killed and replaced — which is why this leans on the separate-process
 * path rather than the in-process one.
 *
 * <p>What it is for is the reseed: a reconnecting client holds nothing, and everything it is
 * sent to catch up is produced on a Netty thread, because the engine is parked waiting for
 * that same client. A regression there is invisible to every other test in this tree — the
 * game simply carries on for everyone else.
 *
 * <p>The mismatch count comes from the replacement process, so it only means anything because
 * that client reports at all — which it did not until it stopped waiting to be assigned a
 * lobby slot it was never going to be given again.
 */
public class ReconnectNetworkTest implements IHasForgeLog {

    @BeforeClass
    public void setUp() {
        TestUtils.ensureFModelInitialized();
    }

    private static void skipUnlessStressTestsEnabled() {
        if (!"true".equalsIgnoreCase(System.getProperty("run.stress.tests"))) {
            throw new SkipException("Stress tests skipped. Use -Drun.stress.tests=true to run.");
        }
        NetworkLogConfig.setTestMode(true);
        NetworkLogConfig.generateBatchId();
    }

    @Test(timeOut = 420000, description = "Remote client disconnects mid-game and reconnects")
    public void survivesAClientReconnecting() {
        skipUnlessStressTestsEnabled();

        Deck deck1 = TestDeckLoader.createMinimalDeck("Mountain", 20);
        Deck deck2 = TestDeckLoader.createMinimalDeck("Forest", 20);

        UnifiedNetworkHarness.GameResult result = new UnifiedNetworkHarness()
                .playerCount(2)
                .remoteClients(1)
                .decks(deck1, deck2)
                .useAiForRemotePlayers(false)
                .separateClientProcesses(true)
                // Long enough that the game is under way and the client holds real state, so
                // the reseed has something to restore rather than arriving before anything
                // has happened.
                .restartClientAfterMs(45000)
                .connectionTimeout(180000)
                .gameTimeout(300000)
                .execute();

        netLog.info("Reconnect result: {}", result.toSummary());

        Assert.assertTrue(result.gameStarted, "Game should have started: " + result.toSummary());
        Assert.assertTrue(result.gameCompleted,
                "Game should have run to completion after the reconnect: " + result.toSummary());
        Assert.assertTrue(result.turnCount > 0,
                "Game should have advanced past turn zero: " + result.toSummary());
        Assert.assertTrue(result.deltaPacketsReceived > 0,
                "Reconnected client should have reported its traffic: " + result.toSummary());
        Assert.assertEquals(result.eventStateMismatches, 0,
                "Reconnected client should agree with the server: " + result.toSummary());
    }
}
