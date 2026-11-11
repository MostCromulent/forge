package forge.adventure.util;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.StageAdventureHost;

import java.util.List;

/**
 * What the adventure model needs from the screens that show it. The mobile client answers with its stages and scenes.
 * Another client can set its own host, so the model runs without them.
 */
public interface AdventureHost {
    static AdventureHost get() {
        return Holder.host == null ? Holder.host = new StageAdventureHost() : Holder.host;
    }

    /** Null goes back to the stages. */
    static void set(AdventureHost host) {
        Holder.host = host;
    }

    boolean inMap();

    /** The point of interest whose map is open, or null. */
    PointOfInterest mapPoint();

    String biomeAt(Vector2 position);

    String playerLocation(boolean forHeader, boolean skipRoads);

    void notification(String text);

    void announcement(String text);

    void statusMessage(String icon, String message, Integer count, float x, float y);

    void showQuestDialogs();

    void refreshDeckEditor();

    void clearItemDescription();

    void showEventRewards(Array<Reward> rewards);

    void clearWorldCache();

    void loadWorldStage(SaveFileData data);

    SaveFileData saveWorldStage();

    String saveFileSuffix();

    void clearBookmarks(List<PointOfInterest> pois);

    final class Holder {
        private static AdventureHost host;

        private Holder() {
        }
    }
}
