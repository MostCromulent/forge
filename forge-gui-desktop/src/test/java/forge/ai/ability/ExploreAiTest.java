package forge.ai.ability;

import forge.ai.AITest;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;

public class ExploreAiTest extends AITest {

    @Test
    public void exploreKeepsASpellCastableSoon() {
        final Player ai = initAndCreateGame().getPlayers().get(1);
        addCard("Forest", ai);
        addCard("Sol Ring", ai);
        addCardToZone("Forest", ai, ZoneType.Hand);
        final Card top = addCardToZone("Air Elemental", ai, ZoneType.Library);

        assertFalse(ExploreAi.shouldPutInGraveyard(top, ai), "Sol Ring makes two, so with a land played next turn {3}{U}{U} is one mana away");
    }
}
