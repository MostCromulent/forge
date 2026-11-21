package forge.ai.ability;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertTrue;

public class ManaAiTest extends AITest {

    @Test
    public void ritualCountsTheManaOfEachSource() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Swamp", ai);
        addCard("Sol Ring", ai);
        addCardToZone("Sengir Vampire", ai, ZoneType.Hand);
        final SpellAbility ritual = addCardToZone("Dark Ritual", ai, ZoneType.Hand).getFirstSpellAbility();
        ritual.setActivatingPlayer(ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);

        assertTrue(ManaAi.doManaRitualLogic(ai, ritual, false), "the Swamp pays for Dark Ritual, and its {B}{B}{B} and Sol Ring's {C}{C} cast Sengir Vampire");
    }
}
