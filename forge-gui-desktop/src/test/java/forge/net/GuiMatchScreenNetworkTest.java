package forge.net;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.gamemodes.net.NetworkLogConfig;
import forge.util.IHasForgeLog;

/**
 * One four-player Commander game whose remote players draw to the real desktop match screen.
 *
 * <p>Every other network test uses a client that answers prompts and renders nothing, so none
 * of them reaches the code that reads the state a client is sent: opening a match, building a
 * field per player, painting a zone. A client seeded by a snapshot differs from one sent a full
 * state precisely there, and a fault in it is invisible to a headless game - the crash this
 * exists for was reported from a desktop session after the headless batches had passed.
 *
 * <p>Commander with four players because it is the widest ordinary layout: the most fields to
 * lay out, the most zones to paint, and a command zone that no two-player game has.
 *
 * <p>Needs a display, and is slower than the batch by a wide margin, so it is one game rather
 * than ten and is not something to run per commit.
 */
public class GuiMatchScreenNetworkTest implements IHasForgeLog {

    @BeforeClass
    public void setUp() {
        TestUtils.ensureFModelInitialized();
    }

    @Test(timeOut = 900000, description = "Four-player Commander game rendered by the desktop match screen")
    public void rendersAFourPlayerCommanderGame() {
        if (!"true".equalsIgnoreCase(System.getProperty("run.stress.tests"))) {
            throw new SkipException("Stress tests skipped. Use -Drun.stress.tests=true to run.");
        }
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            throw new SkipException("No display: the match screen cannot be drawn.");
        }
        NetworkLogConfig.setTestMode(true);
        NetworkLogConfig.generateBatchId();

        final UnifiedNetworkHarness.GameResult result = new UnifiedNetworkHarness()
                .playerCount(4)
                .remoteClients(3)
                .commander(true)
                .useAiForRemotePlayers(false)
                .guiClients(true)
                // Each client reads the card database and loads the skin before it can connect,
                // and here three of them do it at once.
                .connectionTimeout(300000)
                .gameTimeout(600000)
                .execute();

        netLog.info("GUI match screen result: {}", result.toSummary());

        // Covers the client-versus-server state comparison and a client process that died,
        // neither of which any other assertion here would notice.
        Assert.assertTrue(result.success, "Game did not succeed: " + result.errorMessage
                + " | " + result.toSummary());
        Assert.assertTrue(result.gameStarted, "Game should have started: " + result.toSummary());
        Assert.assertTrue(result.turnCount > 0,
                "Game should have advanced past turn zero: " + result.toSummary());
    }
}
