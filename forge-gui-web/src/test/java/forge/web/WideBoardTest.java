package forge.web;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameState;
import forge.gui.GuiBase;
import forge.player.PlayerControllerHuman;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Forwarding at scale: fails if the browser's incremental model diverges from a fresh snapshot once a player controls
 * more than 200 permanents, or if any property there has no JSON form. Small games cannot reach this many objects.
 */
public class WideBoardTest {
    private static final int WIDE = 200;

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static String repeat(final String card, final int count) {
        return IntStream.range(0, count).mapToObj(i -> card).collect(Collectors.joining(";"));
    }

    /** Places the wide board itself: the shuffle and the AI cannot be relied on to build one. */
    private static void giveWideBoard(final Game game) {
        final boolean webFirst = game.getPlayers().get(0).getController() instanceof PlayerControllerHuman;
        final String web = webFirst ? "human" : "ai";
        final String ai = webFirst ? "ai" : "human";
        final GameState state = new GameState();
        state.parse(List.of(
                "activeplayer=" + web,
                "activephase=MAIN1",
                web + "life=100000",
                ai + "life=100000",
                web + "battlefield=" + repeat("Grizzly Bears", 120) + ";" + repeat("Llanowar Elves", 60) + ";" + repeat("Forest", 40),
                ai + "battlefield=" + repeat("Memnite", 30) + ";" + repeat("Island", 10)));
        state.applyToGame(game);
    }

    @Test(timeOut = 600000)
    public void incrementalMatchesFullOnAWideBoard() throws Exception {
        WebTestSupport.skipUnlessStress();
        final LocalGame local = new LocalGame();
        final Deck bears = TestDecks.of("Bears", "Grizzly Bears", 20, "Forest", 40);
        final Deck islands = TestDecks.of("Islands", "Island", 60);
        try {
            final WebGuiGame gui = new WebGuiGame();
            // Holds at its own first main phase, so the board is placed into a game that is not moving
            final ScriptedBrowser browser = new ScriptedBrowser(gui, 50);
            gui.attach(browser);
            GuiBase.getInterface().invokeInEdtAndWait(() -> local.startMatch("Web Player", bears, "AI", islands, gui));
            Assert.assertTrue(browser.atOwnMain.await(120, TimeUnit.SECONDS), "the web seat never reached its main phase");
            final Game game = local.hostedMatch().getGame();
            GuiBase.getInterface().invokeInEdtAndWait(() -> giveWideBoard(game));
            // Placing cards fires no game event; the host's own resync sends them while the engine waits on this seat
            WebTestSupport.remoteGui(local.hostedMatch()).updateGameView();

            for (int i = 0; i < 600 && browser.widestBattlefield() <= WIDE; i++) {
                Thread.sleep(100);
            }
            Assert.assertTrue(browser.widestBattlefield() > WIDE, "the board never passed " + WIDE + " permanents");
            // The host keeps sending while the board fills, so compare once the browser knows the same objects
            // rather than at a fixed moment; both maps are read in one task on the thread that applies deltas
            Map.Entry<Map<Integer, JsonObject>, Map<Integer, JsonObject>> both = null;
            for (int i = 0; i < 100 && (both == null || !both.getKey().keySet().equals(both.getValue().keySet())); i++) {
                both = CompletableFuture.supplyAsync(() -> Map.entry(browser.model.objectsCopy(), gui.freshSnapshot().objectsCopy()),
                        gui.dispatchExecutor()).get(60, TimeUnit.SECONDS);
                Thread.sleep(100);
            }
            LoopbackGameTest.assertSameIgnoringZone(both.getKey(), both.getValue());
            Assert.assertEquals(gui.skippedProperties(), 0, "properties with no JSON form");
        } finally {
            GuiBase.getInterface().invokeInEdtAndWait(local::shutdown);
        }
    }
}
