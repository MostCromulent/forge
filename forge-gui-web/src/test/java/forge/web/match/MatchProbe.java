package forge.web.match;

import com.google.gson.JsonObject;

import java.util.Map;

/** What tests outside this package read of a match, which the classes keep out of their public API. */
public final class MatchProbe {
    private MatchProbe() {
    }

    /** Properties the match dropped for want of a JSON form, which tests check stays at none. */
    public static int skippedProperties(final WebGuiGame gui) {
        return gui.skippedProperties();
    }

    /** The model a browser would hold if it were sent the whole game now. Call on the match's dispatch thread. */
    public static BrowserModel freshSnapshot(final WebGuiGame gui) {
        return gui.freshSnapshot();
    }

    public static Map<Integer, JsonObject> objects(final BrowserModel model) {
        return model.objectsCopy();
    }
}
