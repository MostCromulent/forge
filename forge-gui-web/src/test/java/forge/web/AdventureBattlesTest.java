package forge.web;

import forge.adventure.IAdventureBattleHost;
import forge.adventure.IAdventureBattleHost.AIPlayerData;
import forge.adventure.IAdventureBattleHost.BattleRequest;
import forge.adventure.IAdventureBattleHost.BattleResult;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.PreparedMatch;
import forge.item.PaperCard;
import forge.model.FModel;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.testng.Assert.assertEquals;

/** Tests the files through which an adventure in another process hands a battle to this server and gets its result back. */
public class AdventureBattlesTest {
    private static final List<String> HUMAN_SCRIPT = Arrays.asList("SVar:X:Count$Valid Creature.YouCtrl", "DB$ GainLife | Defined$ You | LifeAmount$ X");
    private static final List<String> AI_SCRIPT = Arrays.asList("DB$ Draw | Defined$ You | NumCards$ 1");

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @BeforeMethod
    public void useTempDir() throws IOException {
        IAdventureBattleHost.setIpcDir(Files.createTempDirectory("adventure_ipc_test").toString());
    }

    @AfterMethod(alwaysRun = true)
    public void cleanUp() {
        IAdventureBattleHost.purgeIpcDir();
    }

    private static BattleRequest requestWithScripts() {
        final BattleRequest request = new BattleRequest();
        request.humanStartingLife = 20;
        request.humanStartGameScripts.add(HUMAN_SCRIPT);
        final AIPlayerData ai = new AIPlayerData();
        ai.name = "Enemy";
        ai.startingLife = 20;
        ai.teamNumber = 1;
        ai.aiType = "";
        ai.startGameScripts.add(AI_SCRIPT);
        request.aiPlayers.add(ai);
        IAdventureBattleHost.saveHumanDeck(TestDecks.of("Human", "Forest", 40));
        IAdventureBattleHost.saveAiDeck(TestDecks.of("Enemy", "Mountain", 40), 0);
        return request;
    }

    @Test
    public void requestCarriesStartGameScripts() throws Exception {
        requestWithScripts().write();

        final BattleRequest read = BattleRequest.read();

        assertEquals(read.humanStartGameScripts, List.of(HUMAN_SCRIPT));
        assertEquals(read.aiPlayers.get(0).startGameScripts, List.of(AI_SCRIPT));
    }

    @Test
    public void prepareGivesEachPlayerItsStartGameScripts() {
        final PreparedMatch prepared = AdventureBattles.prepare(requestWithScripts());

        for (final RegisteredPlayer p : prepared.players()) {
            assertEquals(p.getStartGameScripts(), List.of(p == prepared.human() ? HUMAN_SCRIPT : AI_SCRIPT));
        }
    }

    @Test
    public void resultCarriesAntePrintings() throws Exception {
        final PaperCard won = FModel.getMagicDb().getCommonCards().getCard("Lightning Bolt", "M10");
        final PaperCard lost = FModel.getMagicDb().getCommonCards().getCard("Llanowar Elves", "M19");
        final BattleResult result = new BattleResult();
        result.ante.addWon(List.of(won));
        result.ante.addLost(List.of(lost));
        result.write();

        final BattleResult read = BattleResult.read();

        assertEquals(read.ante.wonCards, List.of(won));
        assertEquals(read.ante.lostCards, List.of(lost));
    }
}
