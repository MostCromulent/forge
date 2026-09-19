package forge.web;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** Spectating two AI players: the web seat is handed to an AI, so the game runs with nothing answering for it. */
public class SpectateGameTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @Test(timeOut = 360000)
    public void spectatedGameRunsWithNoBrowserAnswers() throws Exception {
        WebTestSupport.skipUnlessStress();
        final LocalGame local = new LocalGame();
        final Deck bears = TestDecks.of("Bears", "Grizzly Bears", 20, "Forest", 20);
        final Deck islands = TestDecks.of("Islands", "Island", 40);
        try {
            final WebGuiGame gui = new WebGuiGame();
            // Neither passes priority nor answers a request: only the AI takeover can move the game on
            final FakeBrowser browser = new FakeBrowser(gui, false);
            gui.attach(browser);
            GuiBase.getInterface().invokeInEdtAndWait(() -> local.startMatch("Web Player", islands, "AI", bears, gui));
            local.spectate();

            for (int i = 0; i < 1200 && turn(browser) < 4; i++) {
                Thread.sleep(100);
            }
            Assert.assertTrue(turn(browser) >= 4, "the spectated game stalled on turn " + turn(browser));
            final HostedMatch match = local.hostedMatch();
            Assert.assertNotNull(match);
            Assert.assertTrue(match.getGame().getPlayers().stream().allMatch(p -> p.getController().isAI()),
                    "a seat is still played by a person");
        } finally {
            GuiBase.getInterface().invokeInEdtAndWait(local::shutdown);
        }
    }

    private static int turn(final FakeBrowser browser) {
        final JsonObject state = browser.last("state");
        if (state == null) {
            return 0;
        }
        final JsonObject game = browser.model.objectsCopy().get(state.get("root").getAsInt());
        return game == null || !game.has("Turn") ? 0 : game.get("Turn").getAsInt();
    }
}
