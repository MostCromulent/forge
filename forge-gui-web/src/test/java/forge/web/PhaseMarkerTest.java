package forge.web;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Right-clicking a phase in the stops grid is desktop's "pass priority until here". It travels as a
 * toggleMarker message and comes back in the controls message, which is the only way the browser learns
 * the marker was set.
 */
public class PhaseMarkerTest {
    private static final long WAIT_MILLIS = 15_000;

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static void onUi(final Runnable r) {
        GuiBase.getInterface().invokeInEdtAndWait(r);
    }

    private static Deck plains() {
        return TestDecks.of("Plains", "Plains", 40);
    }

    private static Deck forests() {
        return TestDecks.of("Forests", "Forest", 40);
    }

    private static void awaitStarted(final LocalGame local) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            final HostedMatch m = local.hostedMatch();
            if (m != null && m.getGame() != null) {
                return;
            }
            Thread.sleep(100);
        }
        Assert.fail("game did not start");
    }

    /** The marker the browser is told about, or null if none arrives in time. */
    private static JsonObject awaitMarker(final FakeBrowser browser) throws InterruptedException {
        for (int i = 0; i < WAIT_MILLIS / 50; i++) {
            final JsonObject controls = browser.last("controls");
            if (controls != null && controls.has("marker")) {
                return controls.getAsJsonObject("marker");
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static JsonObject marker(final String phase, final boolean mine) {
        final JsonObject m = JsonCodec.message("toggleMarker");
        m.addProperty("phase", phase);
        m.addProperty("mine", mine);
        return m;
    }

    /**
     * Fails if asking to pass priority until a phase does nothing. The engine drops the request silently
     * when it cannot find the local player or a controller for them, so a no-op looks exactly like success
     * from the browser's side.
     */
    @Test(timeOut = 180_000)
    public void passingUntilAPhaseSetsTheMarker() throws Exception {
        WebTestSupport.skipUnlessStress();
        final LocalGame local = new LocalGame();
        try {
            final WebGuiGame gui = new WebGuiGame();
            final FakeBrowser browser = new FakeBrowser(gui, true);
            gui.attach(browser);
            onUi(() -> local.startMatch("Web Player", plains(), "AI", forests(), gui));
            awaitStarted(local);
            // The client learns which seat is its own a moment after the game object exists
            Assert.assertNotNull(browser.awaitLast("state", WAIT_MILLIS), "the board never reached the browser");
            for (int i = 0; i < WAIT_MILLIS / 50 && gui.getCurrentPlayer() == null; i++) {
                Thread.sleep(50);
            }
            Assert.assertNotNull(gui.getCurrentPlayer(), "the client was never told which seat is its own");
            Assert.assertNull(awaitMarkerOnce(browser), "a marker was set before anything asked for one");

            gui.onBrowserMessage(marker("MAIN2", true));
            final JsonObject set = awaitMarker(browser);
            Assert.assertNotNull(set, "asking to pass until Main 2 set no marker");
            Assert.assertEquals(set.get("phase").getAsString(), "MAIN2", "the marker landed on the wrong phase");
            Assert.assertTrue(set.get("mine").getAsBoolean(), "the marker was set on the wrong player's turns");

            // Asking again on the same phase is how desktop clears it
            gui.onBrowserMessage(marker("MAIN2", true));
            boolean cleared = false;
            for (int i = 0; i < WAIT_MILLIS / 50 && !cleared; i++) {
                final JsonObject controls = browser.last("controls");
                cleared = controls != null && !controls.has("marker");
                Thread.sleep(50);
            }
            Assert.assertTrue(cleared, "asking a second time did not clear the marker");
        } finally {
            onUi(local::shutdown);
        }
    }

    /** One look, with no waiting: used to show nothing is set before the test asks for anything. */
    private static JsonObject awaitMarkerOnce(final FakeBrowser browser) {
        final JsonObject controls = browser.last("controls");
        return controls != null && controls.has("marker") ? controls.getAsJsonObject("marker") : null;
    }
}
