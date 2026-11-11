package forge.adventure.stage;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.utils.Array;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.AdventureDeckEditor;
import forge.adventure.scene.DeckEditScene;
import forge.adventure.scene.GameScene;
import forge.adventure.scene.InventoryScene;
import forge.adventure.scene.MapViewScene;
import forge.adventure.scene.RewardScene;
import forge.adventure.scene.SaveLoadScene;
import forge.adventure.scene.TileMapScene;
import forge.adventure.util.AdventureHost;
import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.Controls;
import forge.adventure.util.Reward;
import forge.adventure.util.SaveFileData;

import java.util.List;

/** The mobile client's stages and scenes, as the adventure model sees them. */
public class StageAdventureHost implements AdventureHost {
    @Override
    public boolean inMap() {
        return MapStage.getInstance().isInMap();
    }

    @Override
    public PointOfInterest mapPoint() {
        return TileMapScene.instance().rootPoint;
    }

    @Override
    public String biomeAt(Vector2 position) {
        return GameScene.instance().getBiomeByPosition(position);
    }

    @Override
    public String playerLocation(boolean forHeader, boolean skipRoads) {
        return GameScene.instance().getAdventurePlayerLocation(forHeader, skipRoads);
    }

    @Override
    public void notification(String text) {
        GameHUD.getInstance().addNotification(text);
    }

    @Override
    public void announcement(String text) {
        AdventurePlayer.current().getCurrentGameStage().setExtraAnnouncement(text);
    }

    @Override
    public void statusMessage(String iconName, String message, Integer itemCount, float x, float y) {
        String symbol = itemCount == null || itemCount < 0 ? "" : " +";
        String icon = iconName == null ? "" : "[+" + iconName + "]";
        String count = itemCount == null ? "" : String.valueOf(itemCount);
        TextraLabel actor = Controls.newTextraLabel("[%95]" + icon + "[WHITE]" + symbol + count + " " + message);
        actor.setPosition(x, y);
        actor.addAction(Actions.sequence(
                Actions.parallel(Actions.moveBy(0f, 5f, 3f), Actions.fadeIn(2f)),
                Actions.hide(),
                Actions.removeActor())
        );
        AdventurePlayer.current().getCurrentGameStage().addActor(actor);
    }

    @Override
    public void showQuestDialogs() {
        AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
    }

    @Override
    public void refreshDeckEditor() {
        AdventureDeckEditor editor = ((AdventureDeckEditor) DeckEditScene.getInstance(null).getScreen());
        if (editor != null)
            editor.refresh();
    }

    @Override
    public void clearItemDescription() {
        InventoryScene.instance().clearItemDescription();
    }

    @Override
    public void showEventRewards(Array<Reward> rewards) {
        RewardScene.instance().loadRewards(rewards, RewardScene.Type.EventReward, null);
        Forge.switchScene(RewardScene.instance());
    }

    @Override
    public void clearWorldCache() {
        WorldStage.getInstance().clearCache();
    }

    @Override
    public void loadWorldStage(SaveFileData data) {
        WorldStage.getInstance().load(data);
    }

    @Override
    public SaveFileData saveWorldStage() {
        return WorldStage.getInstance().save();
    }

    @Override
    public String saveFileSuffix() {
        return SaveLoadScene.instance().getSaveFileSuffix();
    }

    @Override
    public void clearBookmarks(List<PointOfInterest> pois) {
        for (PointOfInterest poi : pois) {
            if (poi == null)
                continue;
            PointOfInterestMapSprite mapSprite = WorldStage.getInstance().getMapSprite(poi);
            if (mapSprite != null)
                mapSprite.setBookmarked(false, poi);
        }
        MapViewScene.instance().clearBookMarks();
    }
}
