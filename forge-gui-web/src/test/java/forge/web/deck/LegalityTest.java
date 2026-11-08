package forge.web.deck;

import forge.StaticData;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.web.WebTestSupport;
import forge.web.lobby.LobbyFormats;
import forge.web.protocol.ToBrowser.Format;
import forge.web.protocol.ToBrowser.FormatGroup;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

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

    // Fails if a commander format's card list is offered as a pool for a 60-card Constructed game
    @Test
    public void commanderFormatsAreNotConstructedCardPools() {
        final List<String> offered = LobbyFormats.choices().stream()
                .filter(f -> f.group() == FormatGroup.Sanctioned || f.group() == FormatGroup.Casual || f.group() == FormatGroup.Custom)
                .map(Format::id).toList();
        Assert.assertTrue(offered.contains("Premodern"), "a casual Constructed format is missing: " + offered);
        for (final String name : List.of("Commander", "Brawl", "Oathbreaker", "PreDH")) {
            Assert.assertFalse(offered.contains(name), name + " is offered as a Constructed card pool");
        }
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
