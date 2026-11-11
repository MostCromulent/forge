package forge.web.adventure;

import com.badlogic.gdx.graphics.g2d.Sprite;
import forge.adventure.data.ItemData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.util.AdventureHost;
import forge.adventure.util.Current;
import forge.adventure.util.Paths;
import forge.util.IHasForgeLog;
import forge.util.Localizer;
import forge.web.protocol.CampaignProtocol.AdventureInventory;
import forge.web.protocol.CampaignProtocol.AdventureItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The player's items and deck slots, as the mobile InventoryScene offers them. Every call runs on Forge's GUI thread. */
final class Inventory implements IHasForgeLog {
    /** A word of a console command, or a quoted run of words, as ConsoleCommandInterpreter splits them. */
    private static final Pattern WORD = Pattern.compile("\"([^\"]*)\"|(\\S+)");

    private final Overworld overworld;
    private final Dialogs dialogs;
    private Consumer<PointOfInterest> teleported = poi -> { };

    Inventory(final Overworld overworld, final Dialogs dialogs) {
        this.overworld = overworld;
        this.dialogs = dialogs;
    }

    /** Called after an item moved the player into a point of interest. */
    void onTeleport(final Consumer<PointOfInterest> teleported) {
        this.teleported = teleported;
    }

    AdventureInventory page() {
        final AdventurePlayer player = Current.player();
        final List<AdventureItem> items = new ArrayList<>();
        for (final ItemData item : player.getItems()) {
            if (item == null) {
                continue;
            }
            final Sprite sprite = item.sprite();
            items.add(new AdventureItem(String.valueOf(item.longID), item.getDisplayName(), item.getDescription(), item.equipmentSlot, item.isEquipped,
                    usable(item), item.shardsNeeded, Paths.ITEMS_ATLAS, sprite == null ? 0 : sprite.getRegionX(), sprite == null ? 0 : sprite.getRegionY(),
                    sprite == null ? 0 : sprite.getRegionWidth(), sprite == null ? 0 : sprite.getRegionHeight()));
        }
        final List<String> decks = new ArrayList<>();
        for (int i = 0; i < player.getDeckCount(); i++) {
            decks.add(player.getDeck(i).getName());
        }
        return new AdventureInventory(items, player.getGold(), player.getShards(), player.getLife(), player.getMaxLife(), decks,
                player.getSelectedDeckIndex());
    }

    // As InventoryScene enables its Use button
    private static boolean usable(final ItemData item) {
        final boolean inPoi = AdventureHost.get().inMap();
        return inPoi && item.usableInPoi || !inPoi && item.usableOnWorldMap;
    }

    private static ItemData find(final String id) {
        for (final ItemData item : Current.player().getItems()) {
            if (item != null && String.valueOf(item.longID).equals(id)) {
                return item;
            }
        }
        return null;
    }

    /** Puts the item on, or takes it off when it is on, as AdventurePlayer.equip does. */
    void equip(final String id) {
        final ItemData item = find(id);
        if (item != null && item.equipmentSlot != null && !item.equipmentSlot.isEmpty()) {
            Current.player().equip(item);
        }
    }

    // Ported from InventoryScene.triggerUse. Returns why the item cannot be used, or null when it was
    String use(final String id) {
        final ItemData item = find(id);
        if (item == null || !usable(item)) {
            return Localizer.getInstance().getMessage("lblWebAdventureCannotUse");
        }
        Current.player().addShards(-item.shardsNeeded);
        if (item.commandOnUse != null && !item.commandOnUse.isEmpty()) {
            command(item.commandOnUse);
        }
        if (item.dialogOnUse != null && item.dialogOnUse.text != null && !item.dialogOnUse.text.isEmpty()) {
            dialogs.open(item.dialogOnUse);
        }
        return null;
    }

    // Ported from the ConsoleCommandInterpreter commands that items run
    private void command(final String line) {
        final List<String> words = new ArrayList<>();
        final Matcher m = WORD.matcher(line);
        while (m.find()) {
            words.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        if (words.size() >= 4 && words.get(0).equals("teleport") && words.get(1).equals("to") && words.get(2).equals("poi")) {
            final PointOfInterest poi = Current.world().findPointsOfInterest(words.get(3));
            if (poi != null) {
                Current.player().setWorldPosX(poi.getPosition().x - 16f);
                Current.player().setWorldPosY(poi.getPosition().y + 16f);
                overworld.player(Current.player().getWorldPosX(), Current.player().getWorldPosY(), false);
                teleported.accept(poi);
            }
            return;
        }
        if (words.size() == 2 && (words.get(0).equals("hide") || words.get(0).equals("fly") || words.get(0).equals("sprint"))) {
            try {
                final String kind = words.get(0).substring(0, 1).toUpperCase() + words.get(0).substring(1);
                overworld.modify(kind, Float.parseFloat(words.get(1)));
            } catch (final NumberFormatException e) {
                netLog.warn("An item ran \"{}\", which has no number of seconds", line);
            }
            return;
        }
        netLog.warn("An item ran \"{}\", which the browser cannot do yet", line);
    }

    void selectDeck(final int slot) {
        Current.player().setSelectedDeckSlot(slot);
    }
}
