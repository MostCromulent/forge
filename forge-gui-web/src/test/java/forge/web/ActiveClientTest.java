package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.card.CardStateName;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameState;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.DeltaPacket;
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
        giveWebSeat(game, hand, battlefield, library, "");
    }

    private static void giveWebSeat(final Game game, final String hand, final String battlefield, final String library, final String aiBattlefield) {
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
                ai + "battlefield=" + aiBattlefield,
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

    private static int turn(final FakeBrowser browser) {
        final JsonObject state = browser.last("state");
        final JsonObject game = state == null ? null : browser.model.objectsCopy().get(state.get("root").getAsInt());
        return game == null || !game.has("Turn") ? 0 : game.get("Turn").getAsInt();
    }

    private static boolean atPriority(final FakeBrowser browser) {
        return browser.last("prompt").get("message").getAsString().startsWith("Priority");
    }

    private static boolean okEnabled(final FakeBrowser browser) {
        return browser.last("prompt").getAsJsonObject("ok").get("enabled").getAsBoolean();
    }

    // Answers the pre-game prompts with OK (play first if the coin toss asks, keep the hand), then waits at the
    // first priority prompt; returns the turn it holds on
    private static int keepAndHoldPriority(final WebGuiGame gui, final FakeBrowser browser) throws InterruptedException {
        for (int i = 0; i < 600 && !(turn(browser) > 0 && okEnabled(browser) && atPriority(browser)); i++) {
            if (turn(browser) == 0 && okEnabled(browser)) {
                // A press that lands before the host has registered the input is dropped, so wait and press again
                gui.onBrowserMessage(FakeBrowser.action("ok"));
                Thread.sleep(3000);
            } else {
                Thread.sleep(100);
            }
        }
        final int held = turn(browser);
        Assert.assertTrue(held > 0, "the web seat never got priority; last prompt " + browser.last("prompt"));
        return held;
    }

    @Test(timeOut = 180000)
    public void endTurnPassesTheTurnWithoutPressingOk() throws Exception {
        WebTestSupport.skipUnlessStress();
        final LocalGame local = new LocalGame();
        try {
            final WebGuiGame gui = new WebGuiGame();
            // Never passes priority, so only End Turn can move the game on
            final FakeBrowser browser = new FakeBrowser(gui, false, true);
            gui.attach(browser);
            onUi(() -> local.startMatch("Web Player", plains(), "AI", forests(), gui));
            final int held = keepAndHoldPriority(gui, browser);
            Thread.sleep(2000);
            Assert.assertEquals(turn(browser), held, "the game moved on while the seat held priority");

            gui.onBrowserMessage(FakeBrowser.action("endTurn"));
            for (int i = 0; i < 300 && turn(browser) == held; i++) {
                Thread.sleep(100);
            }
            Assert.assertTrue(turn(browser) > held, "End Turn did not pass the turn");
            gui.onBrowserMessage(FakeBrowser.action("concede"));
        } finally {
            onUi(local::shutdown);
        }
    }

    private static int cardKey(final Game game, final ZoneType zone, final boolean webSeat, final String name, final boolean faceDown) {
        for (final Player p : game.getPlayers()) {
            if ((p.getController() instanceof PlayerControllerHuman) != webSeat) {
                continue;
            }
            for (final Card c : p.getCardsIn(zone)) {
                if (c.isFaceDown() == faceDown && name.equals(c.getState(CardStateName.Original).getName())) {
                    return DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, c.getId());
                }
            }
        }
        throw new AssertionError("no " + name + " in " + zone);
    }

    private static JsonArray faces(final WebGuiGame gui, final FakeBrowser browser, final int key) throws InterruptedException {
        final JsonObject request = FakeBrowser.action("detail");
        request.addProperty("key", key);
        gui.onBrowserMessage(request);
        for (int i = 0; i < 100; i++) {
            for (final JsonObject m : browser.all("detail")) {
                if (m.get("key").getAsInt() == key) {
                    return m.getAsJsonArray("faces");
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no detail for card " + key);
    }

    @Test(timeOut = 180000)
    public void cardDetailShowsOtherFacesOnlyToThoseWhoMaySeeThem() throws Exception {
        WebTestSupport.skipUnlessStress();
        final LocalGame local = new LocalGame();
        try {
            final WebGuiGame gui = new WebGuiGame();
            final FakeBrowser browser = new FakeBrowser(gui, false, true);
            gui.attach(browser);
            onUi(() -> local.startMatch("Web Player", plains(), "AI", forests(), gui));
            keepAndHoldPriority(gui, browser);
            final Game game = local.hostedMatch().getGame();
            giveWebSeat(game, "Fire // Ice", "Delver of Secrets;Grizzly Bears|FaceDown", "Plains;Plains;Plains", "Grizzly Bears|FaceDown");
            Thread.sleep(500);
            remoteGui(local.hostedMatch()).updateGameView();
            final int delver = cardKey(game, ZoneType.Battlefield, true, "Delver of Secrets", false);
            final int split = cardKey(game, ZoneType.Hand, true, "Fire // Ice", false);
            final int ownFaceDown = cardKey(game, ZoneType.Battlefield, true, "Grizzly Bears", true);
            final int theirFaceDown = cardKey(game, ZoneType.Battlefield, false, "Grizzly Bears", true);
            for (int i = 0; i < 100 && !browser.model.objectsCopy().keySet().containsAll(List.of(delver, split, ownFaceDown, theirFaceDown)); i++) {
                Thread.sleep(100);
            }

            Assert.assertEquals(faces(gui, browser, delver).size(), 2, "a double-faced card shows both faces");
            Assert.assertEquals(faces(gui, browser, split).size(), 3, "a split card shows the whole card and each half");
            Assert.assertEquals(faces(gui, browser, ownFaceDown).size(), 2, "the owner sees the face of their face-down card");
            final JsonArray hidden = faces(gui, browser, theirFaceDown);
            Assert.assertEquals(hidden.size(), 1, "an opponent's face-down card offers another face");
            Assert.assertFalse(hidden.toString().contains("Grizzly"), "an opponent's face-down card revealed its name: " + hidden);
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
