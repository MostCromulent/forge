package forge.web;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** The cards the engine says you can act on reach the browser, so it can outline them. */
public class PlayableHighlightTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @Test(timeOut = 300000)
    public void playableCardsReachTheBrowser() throws Exception {
        WebTestSupport.skipUnlessStress();
        final String saved = FModel.getPreferences().getPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS);
        // In memory only: the highlight preference is off by default and the player's file must not change
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);
        final Deck bears = TestDecks.of("Bears", "Grizzly Bears", 20, "Forest", 20);
        final Deck forests = TestDecks.of("Forests", "Forest", 40);
        try {
            TestMatch.play(bears, forests, gui -> new FakeBrowser(gui, true), (local, gui, browser) -> {
                JsonObject withCards = null;
                for (int i = 0; i < 1200 && withCards == null; i++) {
                    final JsonObject m = browser.last("playable");
                    withCards = m != null && !m.getAsJsonArray("cards").isEmpty() ? m : null;
                    Thread.sleep(100);
                }
                Assert.assertNotNull(withCards, "no playable cards reached the browser");
                Assert.assertTrue(withCards.getAsJsonArray("cards").get(0).getAsJsonObject().has("ref"),
                        "a playable card came through without a card reference: " + withCards);
            });
        } finally {
            FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, saved);
        }
    }
}
