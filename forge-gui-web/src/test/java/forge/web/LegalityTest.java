package forge.web;

import forge.StaticData;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.item.PaperCard;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class LegalityTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static PaperCard card(final String name) {
        return StaticData.instance().getCommonCards().getCard(name);
    }

    private static Deck commanderDeck(final String commander) {
        final Deck d = new Deck("t");
        d.getOrCreate(DeckSection.Commander).add(card(commander), 1);
        return d;
    }

    // Fails if a Commander ban is missed, which getDeckConformanceProblem does not check
    @Test
    public void commanderBanIsFlagged() {
        final Deck d = commanderDeck("Meren of Clan Nel Toth");
        d.getMain().add(card("Mana Crypt"), 1);
        final Legality.Result r = Legality.check(d, Check.of(GameType.Commander, null));
        Assert.assertEquals(r.flags().get("Mana Crypt"), "banned in Commander");
    }

    // Fails if No restriction still applies a format's rules
    @Test
    public void noRestrictionOnlyCountsCopies() {
        final Deck d = commanderDeck("Meren of Clan Nel Toth");
        d.getMain().add(card("Mana Crypt"), 1);
        Assert.assertNull(Legality.check(d, Check.none()).verdict());
    }
}
