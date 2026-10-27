package forge.player;

import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.trackable.TrackableTypes;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

public class TargetSelectionShould {

    @Test
    public void targetATokenWhenTheChosenViewIsNotTheTokensLiveView() {
        // A network host resolves the client's pick to the first view registered for the id: the token's LKI copy
        HumanControlledGame g = new HumanControlledGame();
        Card source = g.card(g.opponent, "Token Source", "Types:Artifact Creature Construct", "PT:1/1");
        g.game.getAction().moveToPlay(source, null, null);
        SpellAbility copy = AbilityFactory.getAbility("DB$ CopyPermanent | Defined$ Self", source);
        copy.setActivatingPlayer(g.opponent);
        AbilityUtils.resolve(copy);
        Card token = g.opponent.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).findFirst().orElseThrow();
        CardView decoded = g.game.getTracker().getObj(TrackableTypes.CardViewType, token.getId());
        assertThat(decoded).as("view the tracker holds for the token's id").isNotSameAs(token.getView());
        assertThat(decoded.getZone()).as("zone of that view").isNull();

        Card bounce = g.card(g.human, "Bounce", "Types:Instant");
        SpellAbility sa = AbilityFactory.getAbility("SP$ ChangeZone | ValidTgts$ Permanent.nonLand+OppCtrl,Card.inZoneStack+OppCtrl"
                + " | TgtZone$ Stack,Battlefield | Origin$ Battlefield,Stack | Destination$ Hand", bounce);
        sa.setActivatingPlayer(g.human);
        Mockito.doReturn(decoded).when(g.gui.asGui()).oneOrNone(any(), any());

        boolean chosen = new TargetSelection(g.controller, sa).chooseTargets(null, null, null, false, false);

        assertThat(chosen).isTrue();
        assertThat(sa.getTargets().getTargetCards()).containsExactly(token);
    }
}
