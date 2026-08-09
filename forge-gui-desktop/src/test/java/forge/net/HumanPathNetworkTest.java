package forge.net;

import forge.deck.Deck;
import forge.gamemodes.net.NetworkLogConfig;
import forge.util.IHasForgeLog;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Plays a network game with remote players left under human control.
 *
 * <p>The standard batch tests swap remote players to AI once the game starts, which is
 * what makes them fast and deterministic — but it also means no prompt is ever rendered
 * and no player action ever arrives over the wire. Everything that only happens on the
 * display and input paths therefore goes untested: the event dispatch thread reaching
 * the sync layer through prompt rendering, and the per-action background threads the
 * server spawns to carry remote input into the engine.
 *
 * <p>The difference is visible in {@code DeltaSyncManager}'s entry reporting, enabled by
 * {@code -Dforge.snapshot.crosscheck=true}: an AI batch game reaches delta collection from
 * game-pool threads only, while a game played through this path reaches it from
 * considerably more.
 *
 * <p>Clients run in their own JVMs. In-process clients share this one's event dispatch
 * thread with the server, so a human-controlled remote player deadlocks the moment the
 * server blocks that thread waiting for a reply that needs the same thread to be
 * delivered — an artifact of the harness rather than of the code under test, since a
 * real host and client are always separate processes.
 *
 * <p>The bandwidth and packet counters accumulate in those client processes, and reach the
 * result only through the {@code RESULT:} line each one prints as it exits.
 */
public class HumanPathNetworkTest implements IHasForgeLog {

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

    @Test(timeOut = 300000, description = "Network game played through the human input path")
    public void playsThroughTheHumanInputPath() {
        skipUnlessStressTestsEnabled();

        Deck deck1 = TestDeckLoader.createMinimalDeck("Mountain", 20);
        Deck deck2 = TestDeckLoader.createMinimalDeck("Forest", 20);

        UnifiedNetworkHarness.GameResult result = new UnifiedNetworkHarness()
                .playerCount(2)
                .remoteClients(1)
                .decks(deck1, deck2)
                .useAiForRemotePlayers(false)
                .separateClientProcesses(true)
                .gameTimeout(240000)
                .execute();

        netLog.info("Human-path result: {}", result.toSummary());

        Assert.assertTrue(result.gameStarted, "Game should have started: " + result.toSummary());
        Assert.assertTrue(result.turnCount > 0,
                "Game should have advanced past turn zero: " + result.toSummary());
    }
}
