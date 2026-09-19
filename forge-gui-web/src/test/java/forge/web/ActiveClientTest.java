package forge.web;

import forge.deck.Deck;
import forge.game.player.Player;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gui.GuiBase;
import forge.player.PlayerControllerHuman;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ActiveClientTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static void onUi(final Runnable r) {
        GuiBase.getInterface().invokeInEdtAndWait(r);
    }

    private static Deck webDeck() {
        return TestDecks.of("Lands", "Temple of Enlightenment", 8, "Evolving Wilds", 8, "Plains", 12, "Island", 12);
    }

    private static Deck aiDeck() {
        return TestDecks.of("Rot", "Grizzly Bears", 12, "Mind Rot", 4, "Duress", 4, "Swamp", 8, "Forest", 12);
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

    @Test(timeOut = 600000)
    public void actingSeatReachesScryAndLibrarySearch() throws Exception {
        WebTestSupport.skipUnlessStress();
        final LocalGame local = new LocalGame();
        try {
            final WebGuiGame gui = new WebGuiGame();
            final ScriptedBrowser browser = new ScriptedBrowser(gui, 300);
            gui.attach(browser);
            onUi(() -> local.startMatch("Web Player", webDeck(), "AI", aiDeck(), gui));
            Assert.assertTrue(browser.gameOver.await(540, TimeUnit.SECONDS), "game did not finish");
            System.out.println("Request kinds: " + browser.requestKinds + ", zones shown: " + browser.zonesShown);
            Assert.assertTrue(browser.reloaded, "no reload happened mid-request");
            Assert.assertTrue(browser.requestKinds.containsKey("manipulate"), "no scry reached the browser");
            Assert.assertTrue(browser.zonesShown.contains("Library"), "no library search reached the browser");
            Assert.assertEquals(gui.skippedProperties(), 0);
        } finally {
            onUi(local::shutdown);
        }
    }

    @Test(timeOut = 180000)
    public void concedeDuringAnOpenRequestEndsTheGame() throws Exception {
        WebTestSupport.skipUnlessStress();
        final LocalGame local = new LocalGame();
        try {
            final WebGuiGame gui = new WebGuiGame();
            final FakeBrowser browser = new FakeBrowser(gui, false);
            gui.attach(browser);
            onUi(() -> local.startMatch("Web Player", webDeck(), "AI", aiDeck(), gui));
            awaitStarted(local);

            RemoteClientGuiGame remote = null;
            for (final Player p : local.hostedMatch().getGame().getPlayers()) {
                if (p.getController() instanceof PlayerControllerHuman pch && pch.getGui() instanceof RemoteClientGuiGame r) {
                    remote = r;
                }
            }
            Assert.assertNotNull(remote);
            final RemoteClientGuiGame seat = remote;
            final CompletableFuture<Boolean> probe = new CompletableFuture<>();
            GuiBase.getInterface().invokeInEdtLater(() -> probe.complete(seat.showConfirmDialog("Hold", "Hold", "Yes", "No", true)));
            Assert.assertNotNull(browser.awaitLast("request", 20000), "request did not reach the browser");

            // A reload while the request is open gets it replayed
            final FakeBrowser reloaded = new FakeBrowser(gui, false);
            gui.attach(reloaded);
            Assert.assertNotNull(reloaded.last("request"));

            gui.onBrowserMessage(FakeBrowser.action("concede"));
            Assert.assertTrue(probe.get(20, TimeUnit.SECONDS), "the open request was not answered with its default");
            Assert.assertTrue(reloaded.gameOver.await(60, TimeUnit.SECONDS), "concede did not end the game");
        } finally {
            onUi(local::shutdown);
        }
    }
}
