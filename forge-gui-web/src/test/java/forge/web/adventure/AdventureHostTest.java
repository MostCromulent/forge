package forge.web.adventure;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.AdventureHost;
import forge.adventure.util.Reward;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.WorldSave;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class AdventureHostTest {
    private Recording host;

    @BeforeMethod
    public void install() {
        AdventureFixture.boot();
        host = new Recording();
        AdventureHost.set(host);
    }

    @AfterMethod
    public void uninstall() {
        AdventureHost.set(new WebAdventureHost());
    }

    @Test
    public void worldGenerationClearsCacheThroughHost() throws Exception {
        AdventureFixture.newWorld(42);
        assertTrue(host.calls.contains("clearWorldCache"));
        assertNull(worldStageInstance());
    }

    @Test
    public void saveRoundTripsWorldStage() throws Exception {
        AdventureFixture.newWorld(42);
        assertTrue(AdventureRuntime.onUi(() -> WorldSave.getCurrentSave().save("host test", 3)));
        assertEquals(host.calls.stream().filter("saveWorldStage"::equals).count(), 1);
        assertTrue(AdventureRuntime.onUi(() -> WorldSave.load(3)));
        assertEquals(host.loaded.readInt("marker"), 7);
        assertNull(worldStageInstance());
    }

    @Test
    public void addedQuestShowsDialogsThroughHost() {
        AdventureFixture.newWorld(42);
        host.calls.clear();
        AdventureRuntime.onUi(() -> {
            AdventurePlayer.current().addQuest("28", false);
            return null;
        });
        assertTrue(host.calls.contains("showQuestDialogs"));
    }

    private static Object worldStageInstance() throws Exception {
        final Field instance = WorldStage.class.getDeclaredField("instance");
        instance.setAccessible(true);
        return instance.get(null);
    }

    private static final class Recording implements AdventureHost {
        final List<String> calls = new ArrayList<>();
        SaveFileData loaded;

        @Override public boolean inMap() { return false; }
        @Override public PointOfInterest mapPoint() { return null; }
        @Override public String biomeAt(final Vector2 position) { return ""; }
        @Override public String playerLocation(final boolean forHeader, final boolean skipRoads) { return ""; }
        @Override public void notification(final String text) { calls.add("notification"); }
        @Override public void announcement(final String text) { calls.add("announcement"); }
        @Override public void statusMessage(final String icon, final String message, final Integer count, final float x, final float y) { calls.add("statusMessage"); }
        @Override public void showQuestDialogs() { calls.add("showQuestDialogs"); }
        @Override public void refreshDeckEditor() { calls.add("refreshDeckEditor"); }
        @Override public void clearItemDescription() { }
        @Override public void showEventRewards(final Array<Reward> rewards) { calls.add("showEventRewards"); }
        @Override public void clearWorldCache() { calls.add("clearWorldCache"); }
        @Override public void loadWorldStage(final SaveFileData data) { loaded = data; }
        @Override public SaveFileData saveWorldStage() {
            calls.add("saveWorldStage");
            final SaveFileData data = new SaveFileData();
            data.store("marker", 7);
            return data;
        }
        @Override public String saveFileSuffix() { return ""; }
        @Override public void clearBookmarks(final List<PointOfInterest> pois) { }
    }
}
