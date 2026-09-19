package forge.web;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.game.player.Player;
import forge.gui.GuiBase;
import forge.player.PlayerControllerHuman;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    private static int turn(final FakeBrowser browser) {
        final JsonObject last = browser.last("state");
        if (last == null) {
            return 0;
        }
        final JsonObject game = browser.model.objectsCopy().get(last.get("root").getAsInt());
        return game == null || !game.has("Turn") ? 0 : game.get("Turn").getAsInt();
    }

    private static int widestBattlefield(final FakeBrowser browser) {
        int widest = 0;
        for (final JsonObject o : browser.model.objectsCopy().values()) {
            if (o.has("Battlefield")) {
                widest = Math.max(widest, o.getAsJsonArray("Battlefield").size());
            }
        }
        return widest;
    }

    @Test(timeOut = 900000)
    public void incrementalMatchesFullOnAWideBoard() throws Exception {
        WebTestSupport.skipUnlessStress();
        final LocalGame local = new LocalGame();
        // Two token doublers and no sacrifice outlets, so the board only grows
        final Deck tokens = TestDecks.of("Tokens", "Anointed Procession", 4, "Parallel Lives", 4, "Spectral Procession", 4,
                "Raise the Alarm", 4, "Captain's Call", 4, "Gather the Townsfolk", 4, "Secure the Wastes", 4, "Plains", 16, "Forest", 12);
        final Deck islands = TestDecks.of("Islands", "Island", 60);
        try {
            final WebGuiGame gui = new WebGuiGame();
            final FakeBrowser browser = new FakeBrowser(gui, true);
            gui.attach(browser);
            GuiBase.getInterface().invokeInEdtAndWait(() -> local.startMatch("Web Player", islands, "AI", tokens, gui));
            // Starting life is assigned as the game begins, so raise it once play is under way
            for (int i = 0; i < 600 && turn(browser) < 2; i++) {
                Thread.sleep(100);
            }
            GuiBase.getInterface().invokeInEdtAndWait(() -> {
                for (final Player p : local.hostedMatch().getGame().getPlayers()) {
                    if (p.getController() instanceof PlayerControllerHuman) {
                        p.setLife(100000, null);
                    }
                }
            });
            for (int i = 0; i < 1500 && widestBattlefield(browser) <= WIDE && browser.gameOver.getCount() > 0; i++) {
                Thread.sleep(500);
            }
            Assert.assertTrue(widestBattlefield(browser) > WIDE, "the board never passed " + WIDE + " permanents");

            // The dispatch thread also feeds the fake browser, so a snapshot taken there sees the same point in the game
            final BrowserModel fresh = CompletableFuture.supplyAsync(gui::freshSnapshot, gui.dispatchExecutor()).get(30, TimeUnit.SECONDS);
            LoopbackGameTest.assertSameIgnoringZone(browser.model.objectsCopy(), fresh.objectsCopy());
            Assert.assertEquals(gui.skippedProperties(), 0, "properties with no JSON form");
        } finally {
            GuiBase.getInterface().invokeInEdtAndWait(local::shutdown);
        }
    }
}
