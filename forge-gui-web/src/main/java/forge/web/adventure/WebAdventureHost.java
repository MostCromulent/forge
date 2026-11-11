package forge.web.adventure;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import forge.adventure.data.BiomeData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.util.AdventureHost;
import forge.adventure.util.Current;
import forge.adventure.util.Reward;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.World;
import forge.util.IHasForgeLog;

import java.util.ArrayList;
import java.util.List;

/** The adventure model's screens, as the web session plays them. There are no town maps yet, so the player is always on the world map. */
final class WebAdventureHost implements AdventureHost, IHasForgeLog {
    /** What the HUD has not shown yet. */
    private final List<String> notifications = new ArrayList<>();
    private String announcement;
    private SaveFileData worldStage = new SaveFileData();

    @Override
    public boolean inMap() {
        return false;
    }

    @Override
    public PointOfInterest mapPoint() {
        return null;
    }

    // Ported from GameScene.getBiomeByPosition
    @Override
    public String biomeAt(final Vector2 position) {
        final World world = Current.world();
        final int biome = World.highestBiome(world.getBiomeMapXY((int) position.x / world.getTileSize(), (int) position.y / world.getTileSize()));
        final List<BiomeData> biomes = world.getData().GetBiomes();
        return biomes.size() <= biome ? "waste" : biomes.get(biome).name;
    }

    // Ported from GameScene.getAdventurePlayerLocation, for the world map
    @Override
    public String playerLocation(final boolean forHeader, final boolean skipRoads) {
        final World world = Current.world();
        final int tile = world.getTileSize();
        final AdventurePlayer player = AdventurePlayer.current();
        final int biome = World.highestBiome(world.getBiomeMapXY((int) player.getWorldPosX() / tile, (int) player.getWorldPosY() / tile));
        final List<BiomeData> biomes = world.getData().GetBiomes();
        if (biomes.size() <= biome) {
            return skipRoads ? (forHeader ? "Waste Map" : "waste") : "";
        }
        final String name = biomes.get(biome).name != null ? biomes.get(biome).name : "waste";
        if (!forHeader) {
            return name;
        }
        return name.equals("waste") ? "Waste Map" : name.substring(0, 1).toUpperCase() + name.substring(1) + " Map";
    }

    @Override
    public void notification(final String text) {
        notifications.add(text);
    }

    @Override
    public void announcement(final String text) {
        announcement = text;
    }

    @Override
    public void statusMessage(final String icon, final String message, final Integer count, final float x, final float y) {
        notifications.add(count == null ? message : (count >= 0 ? "+" : "") + count + " " + message);
    }

    @Override
    public void showQuestDialogs() {
    }

    @Override
    public void refreshDeckEditor() {
    }

    @Override
    public void clearItemDescription() {
    }

    @Override
    public void showEventRewards(final Array<Reward> rewards) {
        netLog.warn("Adventure events are not played in the browser, so their {} rewards were not shown", rewards.size);
    }

    @Override
    public void clearWorldCache() {
        worldStage = new SaveFileData();
    }

    @Override
    public void loadWorldStage(final SaveFileData data) {
        worldStage = data;
    }

    @Override
    public SaveFileData saveWorldStage() {
        return worldStage;
    }

    // Copied from SaveLoadScene.getSaveFileSuffix
    @Override
    public String saveFileSuffix() {
        final String difficulty = switch (AdventurePlayer.current().getDifficulty().name) {
            case "easy", "Easy" -> "[%99][CYAN]• [WHITE]";
            case "normal", "Normal" -> "[%99][GREEN]• [WHITE]";
            case "hard", "Hard" -> "[%99][GOLD]• [WHITE]";
            case "insane", "Insane" -> "[%99][RED]• [WHITE]";
            default -> "[%99][WHITE]";
        };
        return '│' + difficulty + playerLocation(true, true);
    }

    @Override
    public void clearBookmarks(final List<PointOfInterest> pois) {
    }

    String takeAnnouncement() {
        final String text = announcement;
        announcement = null;
        return text;
    }

    List<String> takeNotifications() {
        final List<String> taken = List.copyOf(notifications);
        notifications.clear();
        return taken;
    }
}
