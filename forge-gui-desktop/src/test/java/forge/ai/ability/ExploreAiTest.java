package forge.ai.ability;

import forge.ai.AITest;
import forge.card.mana.ManaAtom;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

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

    @Test
    public void exploreLeavesOutFloatingMana() {
        final Player ai = initAndCreateGame().getPlayers().get(1);
        addCard("Forest", ai);
        final Card solRing = addCard("Sol Ring", ai);
        addCardToZone("Forest", ai, ZoneType.Hand);
        // Sol Ring paid {1} of a spell and left one {C}, which is gone by next turn
        solRing.setTapped(true);
        ai.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, solRing, null, ai));
        final Card top = addCardToZone("Craw Wurm", ai, ZoneType.Library);

        assertTrue(ExploreAi.shouldPutInGraveyard(top, ai), "four mana next turn is two short of {4}{G}{G}");
    }
}
