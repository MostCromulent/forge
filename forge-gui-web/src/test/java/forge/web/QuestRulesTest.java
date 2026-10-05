package forge.web;

import forge.StaticData;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.PreparedMatch;
import forge.gamemodes.quest.QuestController;
import forge.gamemodes.quest.QuestEventDuel;
import forge.gamemodes.quest.QuestSpellShop;
import forge.gamemodes.quest.QuestUtil;
import forge.gamemodes.quest.data.QuestPreferences.QPref;
import forge.item.InventoryItem;
import forge.item.PaperCard;
import forge.item.IPaperCard;
import forge.model.FModel;
import forge.util.ItemPool;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/** Quest's rules as the shared package states them, with no screen involved. */
public class QuestRulesTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @AfterMethod(alwaysRun = true)
    public void cleanUp() throws IOException {
        QuestUtil.setEvent(null);
        QuestFixture.cleanUp();
    }

    private static QuestEventDuel firstDuel() throws IOException {
        final QuestController quest = FModel.getQuest();
        quest.load(QuestFixture.install());
        final QuestEventDuel duel = quest.getDuelsManager().generateDuels().get(0);
        QuestUtil.setEvent(duel);
        return duel;
    }

    // Fails if a prepared duel does not seat the quest's own deck at the quest's life against the event's deck under the event's name
    @Test
    public void aDuelIsPreparedWithTheQuestsDeckAndLife() throws IOException {
        final QuestEventDuel duel = firstDuel();
        final QuestController quest = FModel.getQuest();
        final PreparedMatch match = QuestUtil.prepareGame();
        assertEquals(match.players().size(), 2);
        assertSame(match.human(), match.players().get(0));
        assertEquals(match.human().getDeck().getName(), "Forest deck");
        assertEquals(match.human().getDeck().getMain().toFlatList(), QuestUtil.getCurrentDeck().getMain().toFlatList());
        assertEquals(match.human().getStartingLife(), quest.getAssets().getLife(quest.getMode()));
        assertEquals(match.players().get(1).getDeck().getName(), duel.getEventDeck().getName());
        assertEquals(match.players().get(1).getPlayer().getName(), duel.getOpponentName() == null ? duel.getTitle() : duel.getOpponentName());
        assertEquals(match.rules().getGamesPerMatch(), quest.getMatchLength());
    }

    // Fails if the plant chosen in the save is not put on the battlefield at the start
    @Test
    public void theChosenPlantStartsOnTheBattlefield() throws IOException {
        firstDuel();
        final QuestController quest = FModel.getQuest();
        quest.selectPet(0, "Plant");
        final IPaperCard plant = quest.getPetsStorage().getPet("Plant").getPetCard(quest.getAssets());
        final RegisteredPlayer human = QuestUtil.prepareGame().human();
        final List<String> names = new ArrayList<>();
        human.getCardsOnBattlefield().forEach(c -> names.add(c.getName()));
        assertTrue(names.contains(plant.getName()), "the battlefield holds " + names);
    }

    private static ItemPool<InventoryItem> poolOf(final InventoryItem item, final int count) {
        final ItemPool<InventoryItem> pool = new ItemPool<>(InventoryItem.class);
        pool.add(item, count);
        return pool;
    }

    // Fails if buying a card does not charge its price and add it to the pool, or touches the shop's stock
    @Test
    public void buyingChargesAndLeavesTheStock() throws IOException {
        final QuestController quest = FModel.getQuest();
        quest.load(QuestFixture.install());
        quest.getAssets().setCredits(100_000);
        final PaperCard card = (PaperCard) quest.getCards().getShopList().toFlatList().stream().filter(i -> i instanceof PaperCard).findFirst().orElseThrow();
        final int stock = quest.getCards().getShopList().count(card);
        final int had = quest.getCards().getCardpool().count(card);
        final ItemPool<InventoryItem> added = QuestSpellShop.buyItems(poolOf(card, 1));
        assertEquals(quest.getAssets().getCredits(), 100_000 - QuestSpellShop.getCardValue(card));
        assertEquals(quest.getCards().getCardpool().count(card), had + 1);
        assertEquals(added.count(card), 1);
        assertEquals(quest.getCards().getShopList().count(card), stock);
    }

    // Fails if selling a card does not pay the sale price and take it from the pool
    @Test
    public void sellingPaysTheSalePrice() throws IOException {
        final QuestController quest = FModel.getQuest();
        quest.load(QuestFixture.install());
        final PaperCard card = quest.getCards().getCardpool().toFlatList().stream().filter(c -> !c.getRules().getType().isLand()).findFirst().orElseThrow();
        final double multiplier = QuestSpellShop.updateMultiplier();
        final long credits = quest.getAssets().getCredits();
        final int had = quest.getCards().getCardpool().count(card);
        QuestSpellShop.sellItems(poolOf(card, 1));
        final int price = Math.max(Math.min((int) (multiplier * QuestSpellShop.getCardValue(card)), quest.getCards().getSellPriceLimit()), 1);
        assertEquals(quest.getAssets().getCredits(), credits + price);
        assertEquals(quest.getCards().getCardpool().count(card), had - 1);
    }

    // Fails if the extras of a card are counted against anything but a playset, or a basic land, which a deck may hold any number of, against anything but that setting
    @Test
    public void extrasAreWhatIsAboveAPlayset() throws IOException {
        FModel.getQuest().load(QuestFixture.install());
        final PaperCard shock = StaticData.instance().getCommonCards().getCard("Shock");
        final PaperCard forest = StaticData.instance().getCommonCards().getCard("Forest");
        final ItemPool<InventoryItem> owned = poolOf(shock, 7);
        owned.add(forest, 60);
        final ItemPool<InventoryItem> extras = QuestSpellShop.extras(owned);
        assertEquals(extras.count(shock), 7 - FModel.getQuestPreferences().getPrefInt(QPref.PLAYSET_SIZE));
        assertEquals(extras.count(forest), Math.max(0, 60 - FModel.getQuestPreferences().getPrefInt(QPref.PLAYSET_ANY_NUMBER_SIZE)));
    }
}
