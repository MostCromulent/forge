package forge.web;

import forge.item.PaperCard;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.function.Function;

public class CardCatalogTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static CardCatalog.Query q(final String text) {
        return new CardCatalog.Query(text, "", "any", "any", "name", 0, false);
    }

    private static List<String> names(final ToBrowser.CataloguePage p) {
        return p.rows().stream().map(ToBrowser.CatalogueRow::name).toList();
    }

    // Fails if names that merely contain the text come before names that start with it
    @Test
    public void textRanksStartsBeforeContains() {
        final ToBrowser.CataloguePage page = CardCatalog.get().query(1, q("grave pact"), c -> null, null, n -> 0);
        Assert.assertTrue(page.ranked());
        Assert.assertEquals(names(page).get(0), "Grave Pact");
    }

    // Fails if the land filter lets spells through, as CardRulesPredicates.IS_NON_LAND would
    @Test
    public void landFilterIsLands() {
        final ToBrowser.CataloguePage page = CardCatalog.get().query(1,
                new CardCatalog.Query("", "", "land", "any", "name", 0, false), c -> null, null, n -> 0);
        Assert.assertFalse(page.rows().isEmpty());
        Assert.assertTrue(page.rows().stream().allMatch(r -> "Lands".equals(r.heading())));
    }

    // Fails if a card the deck can't use shows while the switch is off, or stays hidden with it on
    @Test
    public void switchShowsUnusableCards() {
        final Function<PaperCard, String> offColour = c -> c.getRules().getColorIdentity().hasWhite() ? "outside B G" : null;
        final ToBrowser.CataloguePage hidden = CardCatalog.get().query(1, q("swords to plowshares"), offColour, null, n -> 0);
        Assert.assertEquals(hidden.total(), 0);
        Assert.assertTrue(hidden.hiddenBySwitch() > 0);
        final ToBrowser.CataloguePage shown = CardCatalog.get().query(1,
                new CardCatalog.Query("swords to plowshares", "", "any", "any", "name", 0, true), offColour, null, n -> 0);
        Assert.assertEquals(shown.rows().get(0).problem(), "outside B G");
    }

    // Fails if Forge's search syntax, which desktop's card search reads, is taken as a card name here
    @Test
    public void searchSyntaxFilters() {
        final ToBrowser.CataloguePage page = CardCatalog.get().query(1, q("c:r t:instant lightning"), c -> null, null, n -> 0);
        Assert.assertFalse(page.ranked(), "a query using syntax was ranked as a name");
        Assert.assertTrue(names(page).contains("Lightning Bolt"));
        Assert.assertTrue(page.rows().stream().allMatch(r -> r.colors().contains("R")), "a card that is not red got through");
        // A double-faced card counts when either face is an instant, as desktop's search has it, so a plain creature is the test
        Assert.assertEquals(CardCatalog.get().query(1, q("c:r t:instant goblin guide"), c -> null, null, n -> 0).total(), 0);
    }

    // Fails if variant cards (planes, schemes) reach the catalogue
    @Test
    public void onlyMainDeckCards() {
        Assert.assertEquals(CardCatalog.get().query(1, q("academy at tolaria west"), c -> null, null, n -> 0).total(), 0);
    }

    // Fails if a page carries more than one screenful or loses the total
    @Test
    public void pagesHoldSixtyAndCountAll() {
        final ToBrowser.CataloguePage page = CardCatalog.get().query(1, q(""), c -> null, null, n -> 0);
        Assert.assertEquals(page.rows().size(), 60);
        Assert.assertTrue(page.total() > 10_000);
    }
}
