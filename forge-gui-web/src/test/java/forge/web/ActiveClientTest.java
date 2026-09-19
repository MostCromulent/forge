package forge.web;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameState;
import forge.game.player.Player;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gui.GuiBase;
import forge.player.PlayerControllerHuman;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
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

    // Basics only: the AI does nothing, and the cards a test needs are placed with a GameState, never drawn
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

    private static RemoteClientGuiGame remoteGui(final HostedMatch match) {
        for (final Player p : match.getGame().getPlayers()) {
            if (p.getController() instanceof PlayerControllerHuman pch && pch.getGui() instanceof RemoteClientGuiGame r) {
                return r;
            }
        }
        throw new AssertionError("no remote human seat");
    }

    // GameState names the first player "human" and the second "ai", whoever controls them
    private static void giveWebSeat(final Game game, final String hand, final String battlefield, final String library) {
        final boolean webFirst = game.getPlayers().get(0).getController() instanceof PlayerControllerHuman;
        final String web = webFirst ? "human" : "ai";
        final String ai = webFirst ? "ai" : "human";
        final GameState state = new GameState();
        state.parse(List.of(
                "activeplayer=" + web,
                "activephase=MAIN1",
                "removesummoningsickness=true",
                web + "life=20",
                web + "hand=" + hand,
                web + "battlefield=" + battlefield,
                web + "library=" + library,
                ai + "life=20",
                ai + "hand=",
                ai + "battlefield=",
                ai + "library=Forest;Forest;Forest;Forest;Forest"));
        state.applyToGame(game);
    }

    @Test(timeOut = 180000)
    public void actingSeatReachesScryAndLibrarySearch() throws Exception {
        WebTestSupport.skipUnlessStress();
        final LocalGame local = new LocalGame();
        try {
            final WebGuiGame gui = new WebGuiGame();
            final ScriptedBrowser browser = new ScriptedBrowser(gui, 300);
            gui.attach(browser);
            onUi(() -> local.startMatch("Web Player", plains(), "AI", forests(), gui));
            Assert.assertTrue(browser.atOwnMain.await(120, TimeUnit.SECONDS), "the web seat never reached its main phase");

            // Temple's enter trigger scrys; Evolving Wilds searches the library
            giveWebSeat(local.hostedMatch().getGame(), "Temple of Enlightenment", "Evolving Wilds", "Plains;Plains;Plains;Plains;Plains");
            Thread.sleep(500);
            // Placing cards fires no game event; the host's own resync sends them while the engine waits on this seat
            remoteGui(local.hostedMatch()).updateGameView();
            // One card at a time, so the Wilds activation cannot interleave with the Temple's trigger
            browser.release("Hand:Temple of Enlightenment");
            for (int i = 0; i < 300 && !browser.requestKinds.containsKey("manipulate"); i++) {
                Thread.sleep(100);
            }
            Assert.assertTrue(browser.requestKinds.containsKey("manipulate"), "no scry reached the browser: " + browser.requestKinds);

            browser.release("Battlefield:Evolving Wilds");
            for (int i = 0; i < 300 && !browser.zonesShown.contains("Library"); i++) {
                Thread.sleep(100);
            }
            Assert.assertTrue(browser.zonesShown.contains("Library"), "no library search reached the browser: " + browser.zonesShown);
            Assert.assertTrue(browser.reloaded, "no reload happened mid-request");
            Assert.assertEquals(gui.skippedProperties(), 0);
            gui.onBrowserMessage(FakeBrowser.action("concede"));
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
            onUi(() -> local.startMatch("Web Player", plains(), "AI", forests(), gui));
            awaitStarted(local);

            final RemoteClientGuiGame seat = remoteGui(local.hostedMatch());
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
