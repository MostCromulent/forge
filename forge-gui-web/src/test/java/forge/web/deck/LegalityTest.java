package forge.web.deck;

import forge.StaticData;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.web.WebTestSupport;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class LegalityTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
        FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, true);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, false);
    }

    private static PaperCard card(final String name) {
        return StaticData.instance().getCommonCards().getCard(name);
    }

    private static Deck commanderDeck(final String commander) {
        final Deck d = new Deck("t");
        d.getOrCreate(DeckSection.Commander).add(card(commander), 1);
        return d;
    }

    // Fails if a card outside the commander's colours is not marked, or the verdict is not the engine's
    @Test
    public void cardOutsideTheCommandersColoursIsFlagged() {
        final Deck d = commanderDeck("Meren of Clan Nel Toth");
        d.getMain().add(card("Lightning Bolt"), 1);
        final Legality.Result r = Legality.check(d, Check.of(GameType.Commander, null));
        Assert.assertEquals(r.flags().get("Lightning Bolt"), "outside B G");
        Assert.assertEquals(r.verdict(), GameType.Commander.getDeckFormat().getDeckConformanceProblem(d));
    }

    // Fails if No restriction applies a format's rules
    @Test
    public void noRestrictionOnlyCountsCopies() {
        final Deck d = commanderDeck("Meren of Clan Nel Toth");
        d.getMain().add(card("Mana Crypt"), 1);
        Assert.assertNull(Legality.check(d, Check.none()).verdict());
    }

    // Fails if a card pool's own message, such as singleton, no longer reaches the verdict as one line
    @Test
    public void cardPoolReportsDeckRules() {
        final Deck d = new Deck("t");
        d.getMain().add(card("Sol Ring"), 2);
        d.getMain().add(card("Forest"), 98);
        Assert.assertEquals(DeckCatalog.problem(d, GameType.Constructed, FModel.getFormats().get("Canadian Highlander")),
                "Not legal in Canadian Highlander. The deck contains more copies than allowed of the following cards: Sol Ring");
    }
}
