package forge.web;

import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.PreparedMatch;
import forge.gamemodes.quest.QuestController;
import forge.gamemodes.quest.QuestEventDuel;
import forge.gamemodes.quest.QuestUtil;
import forge.item.IPaperCard;
import forge.model.FModel;
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
}
