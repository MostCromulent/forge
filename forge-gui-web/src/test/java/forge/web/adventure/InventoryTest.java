package forge.web.adventure;

import forge.adventure.data.WorldData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.character.EnemySprite;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.web.deck.DeckEditor;
import forge.web.protocol.CampaignProtocol.AdventureInventory;
import forge.web.protocol.CampaignProtocol.AdventureItem;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class InventoryTest {
    private Overworld overworld;
    private Inventory inventory;
    private final List<Record> sent = new ArrayList<>();

    @BeforeMethod
    public void setUp() {
        AdventureFixture.newWorld(42);
        sent.clear();
        overworld = AdventureRuntime.onUi(() -> new Overworld(WorldSave.getCurrentSave().getWorld(), new Random(3), null));
        inventory = new Inventory(overworld, new Dialogs(sent::add));
    }

    private AdventureItem item(final AdventureInventory page, final String name) {
        return page.items().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    // Fails if equipping an item with a speed effect does not change the walking speed, as AdventurePlayer.equipmentSpeed counts it
    @Test
    public void equipChangesSpeed() {
        AdventureRuntime.onUi(() -> AdventurePlayer.current().addItem("Bronze Blessing of Speed"));
        final float before = AdventureRuntime.onUi(overworld::playerSpeed);
        final AdventureInventory page = AdventureRuntime.onUi(inventory::page);
        final AdventureItem blessing = item(page, "Bronze Blessing of Speed");
        assertFalse(blessing.equipped());
        AdventureRuntime.onUi(() -> {
            inventory.equip(blessing.id());
            return null;
        });
        assertTrue(item(AdventureRuntime.onUi(inventory::page), "Bronze Blessing of Speed").equipped());
        assertNotEquals(AdventureRuntime.onUi(overworld::playerSpeed), before);
    }

    // Fails if an item's atlas region is missing, which the inventory page draws its picture from
    @Test
    public void itemsCarryTheirPicture() {
        AdventureRuntime.onUi(() -> AdventurePlayer.current().addItem("Bronze Blessing of Speed"));
        final AdventureItem blessing = item(AdventureRuntime.onUi(inventory::page), "Bronze Blessing of Speed");
        assertTrue(blessing.atlas().endsWith(".atlas"));
        assertTrue(blessing.w() > 0);
    }

    // Fails if using a teleport rune does not move the player to its point of interest, as its console command does
    @Test
    public void runeTeleports() {
        AdventureRuntime.onUi(() -> AdventurePlayer.current().addItem("Colorless rune"));
        AdventureRuntime.onUi(() -> {
            AdventurePlayer.current().addShards(100);
            return null;
        });
        final AdventureItem rune = item(AdventureRuntime.onUi(inventory::page), "Colorless rune");
        assertTrue(rune.usable());
        final String problem = AdventureRuntime.onUi(() -> inventory.use(rune.id()));
        assertEquals(problem, null);
        final float[] spawn = AdventureRuntime.onUi(() -> {
            final var poi = WorldSave.getCurrentSave().getWorld().findPointsOfInterest("Spawn");
            return new float[] {poi.getPosition().x, poi.getPosition().y};
        });
        final float[] at = AdventureRuntime.onUi(() -> new float[] {AdventurePlayer.current().getWorldPosX(), AdventurePlayer.current().getWorldPosY()});
        assertEquals(Math.hypot(at[0] - spawn[0], at[1] - spawn[1]), 0, 64);
    }

    // Fails if a deck saved in the editor does not land in the player's deck slot and the save file
    @Test
    public void editDeckSavesSlot() {
        final PaperCard owned = AdventureRuntime.onUi(() -> AdventurePlayer.current().getCards().toFlatList().get(0));
        final File auto = new File(WorldSave.getSaveFile(WorldSave.AUTO_SAVE_SLOT));
        auto.delete();
        AdventureRuntime.onUi(() -> {
            final Deck edited = new Deck(AdventurePlayer.current().getDeck(1), "Edited");
            edited.getMain().add(owned);
            AdventureDecks.storage(1).add(edited);
            AdventureDecks.collection(1).saved().run();
            return null;
        });
        final Deck slot = AdventureRuntime.onUi(() -> AdventurePlayer.current().getDeck(1));
        assertEquals(slot.getName(), "Edited");
        assertTrue(slot.getMain().contains(owned));
        assertTrue(auto.isFile());
    }

    // Fails if cards set to auto-sell can be built into a deck, which the mobile editor leaves out
    @Test
    public void autoSellCardsAreNotInTheCollection() {
        final PaperCard card = AdventureRuntime.onUi(() -> AdventurePlayer.current().getCards().toFlatList().get(0));
        final int owned = AdventureRuntime.onUi(() -> AdventurePlayer.current().getCards().count(card));
        AdventureRuntime.onUi(() -> {
            AdventurePlayer.current().autoSellCards.add(card);
            return null;
        });
        final DeckEditor.Collection collection = AdventureRuntime.onUi(() -> AdventureDecks.collection(0));
        assertEquals(collection.cards().get().count(card), owned - 1);
        assertNotNull(collection.landSets().apply(new Deck()));
        assertFalse(collection.landSets().apply(new Deck()).isEmpty());
    }

    // Fails if picking a deck slot does not change the deck the next battle is played with
    @Test
    public void selectDeckUsedInDuel() {
        // Dev mode without enforced legality skips DuelScene's deck rules, so the battle's deck is the slot's deck unchanged
        FModel.getPreferences().setPref(ForgePreferences.FPref.DEV_MODE_ENABLED, true);
        final PaperCard marker = AdventureRuntime.onUi(() -> AdventurePlayer.current().getCards().toFlatList().get(0));
        AdventureRuntime.onUi(() -> {
            final Deck slot = new Deck(AdventurePlayer.current().getDeck(1), "Second");
            slot.getMain().clear();
            slot.getMain().add(marker, 3);
            AdventureDecks.storage(1).add(slot);
            inventory.selectDeck(1);
            return null;
        });
        assertEquals(AdventureRuntime.onUi(inventory::page).selectedDeck(), 1);
        final Deck played = AdventureRuntime.onUi(() -> new AdventureDuel(new EnemySprite(WorldData.getEnemy("Garth")), e -> { }).prepare().human().getDeck());
        assertEquals(played.getMain().count(marker), 3);
    }

    @Test
    public void unknownItemIsRefused() {
        assertNotNull(AdventureRuntime.onUi(() -> inventory.use("999999999")));
    }
}
