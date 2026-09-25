package forge.web;

import forge.StaticData;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameFormat;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.model.FModel;
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

    // Fails if an off-colour card is not flagged with the commander's letters
    @Test
    public void identityIsFlagged() {
        final Deck d = commanderDeck("Meren of Clan Nel Toth");
        d.getMain().add(card("Swords to Plowshares"), 1);
        Assert.assertEquals(Legality.check(d, Check.of(GameType.Commander, null)).flags().get("Swords to Plowshares"), "outside B G");
    }

    // Fails if the copy limit ignores a copy sitting in the sideboard
    @Test
    public void copiesCountAcrossMainAndSideboard() {
        final Deck d = new Deck("t");
        d.getMain().add(card("Lightning Bolt"), 4);
        d.getOrCreate(DeckSection.Sideboard).add(card("Lightning Bolt"), 1);
        Assert.assertEquals(Legality.check(d, Check.of(GameType.Constructed, null)).flags().get("Lightning Bolt"), "5 of 4");
    }

    // Fails if a card legal in the format but outside a Pauper pool is not flagged, or is called banned
    @Test
    public void poolOutsiderIsNotLegalNotBanned() {
        final Deck d = new Deck("t");
        d.getMain().add(card("Tarmogoyf"), 1);
        final GameFormat pauper = FModel.getFormats().getFormat("Pauper");
        Assert.assertEquals(Legality.check(d, Check.of(GameType.Constructed, pauper)).flags().get("Tarmogoyf"), "not legal in Pauper");
    }

    // Fails if the verdict hides the size problem behind the identity problem, as getDeckConformanceProblem does
    @Test
    public void verdictNamesSizeAndCards() {
        final Deck d = commanderDeck("Meren of Clan Nel Toth");
        d.getMain().add(card("Swamp"), 97);
        d.getMain().add(card("Swords to Plowshares"), 1);
        Assert.assertEquals(Legality.check(d, Check.of(GameType.Commander, null)).verdict(),
                "2 problems: 99 of 100 cards, and 1 card outside B G.");
    }

    // Fails if No restriction still applies a format's rules
    @Test
    public void noRestrictionOnlyCountsCopies() {
        final Deck d = commanderDeck("Meren of Clan Nel Toth");
        d.getMain().add(card("Mana Crypt"), 1);
        Assert.assertNull(Legality.check(d, Check.none()).verdict());
    }
}
