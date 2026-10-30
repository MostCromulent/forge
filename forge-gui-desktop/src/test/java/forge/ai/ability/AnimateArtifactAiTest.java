package forge.ai.ability;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

public class AnimateArtifactAiTest extends AITest {

    /** Rise and Shine's counters go on the remembered 0/0, so the target is not skipped. Two lands can't overload it. */
    @Test
    public void castsRiseAndShineOnNoncreatureArtifact() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        Card relic = addCard("Darksteel Relic", ai);
        addCard("Island", ai);
        addCard("Forest", ai);
        addCardToZone("Rise and Shine", ai, ZoneType.Hand);

        playUntilPhase(game, PhaseType.END_OF_TURN);

        AssertJUnit.assertEquals("Rise and Shine should have been cast", 1,
                countCardsWithName(game, "Rise and Shine", ZoneType.Graveyard));
        AssertJUnit.assertTrue("Darksteel Relic should be a creature", relic.isCreature());
        AssertJUnit.assertEquals(4, relic.getCounters(CounterEnumType.P1P1));
    }

    /** A solved Case of the Filched Falcon exists to be sacrificed, so the AI uses it before its own turn. */
    @Test
    public void activatesSolvedFilchedFalconOnNoncreatureArtifact() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);

        Card caseCard = addCard("Case of the Filched Falcon", ai);
        caseCard.setSolved(true);
        Card relic1 = addCard("Darksteel Relic", ai);
        Card relic2 = addCard("Darksteel Relic", ai);
        addToken("c_a_clue_draw", ai);
        addCards("Island", 3, ai);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, opponent);
        playUntilNextTurn(game);

        AssertJUnit.assertEquals("Case should have been sacrificed", 1,
                countCardsWithName(game, "Case of the Filched Falcon", ZoneType.Graveyard));
        Card animated = relic1.isCreature() ? relic1 : relic2;
        AssertJUnit.assertTrue("A Darksteel Relic should be a creature", animated.isCreature());
        AssertJUnit.assertEquals(4, animated.getCounters(CounterEnumType.P1P1));
    }
}
