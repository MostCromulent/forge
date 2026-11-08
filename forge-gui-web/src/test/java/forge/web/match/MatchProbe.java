package forge.web.match;

import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.localinstance.properties.ForgePreferences.FPref;

import java.util.List;
import java.util.Map;

/** Gives tests outside this package access to match internals that the classes keep out of their public API. */
public final class MatchProbe {
    private MatchProbe() {
    }

    /** The number of properties the match dropped because they have no JSON form. Tests check that it stays at zero. */
    public static int skippedProperties(final WebGuiGame gui) {
        return gui.skippedProperties();
    }

    /** The model a browser would have if it were sent the whole game now. Call it on the match's dispatch thread. */
    public static BrowserModel freshSnapshot(final WebGuiGame gui) {
        final BrowserModel fresh = new BrowserModel();
        fresh.applyStateMessage(gui.fullState());
        return fresh;
    }

    /** Opens a table for the web seat and one computer seat, and starts the match at once. Call it on the host UI thread. */
    public static void startMatch(final LocalGame local, final Deck webDeck, final Deck aiDeck, final WebGuiGame gui) {
        local.openHost("Web Player", gui, () -> { }, (from, text) -> { });
        local.seatAndStart(List.of(new LocalGame.Seat("Web Player", false, 0, 0, webDeck), new LocalGame.Seat("AI", true,
                LocalGame.storedIndex(FPref.UI_AVATARS, 1), LocalGame.storedIndex(FPref.UI_SLEEVES, 1), aiDeck)));
    }

    public static Map<Integer, JsonObject> objects(final BrowserModel model) {
        return model.objectsCopy();
    }
}
