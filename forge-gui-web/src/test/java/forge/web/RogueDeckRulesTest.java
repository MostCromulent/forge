package forge.web;

import forge.card.MagicColor;
import forge.deck.CardPool;
import forge.deck.DeckSection;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.path.NodeSanctum;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

@Test(singleThreaded = true)
public class RogueDeckRulesTest {
    private RogueRun run;

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @BeforeMethod
    public void aragornsRun() {
        RogueFixture.clean();
        run = RogueFixture.run(NodeSanctum.class);
    }

    @AfterMethod(alwaysRun = true)
    public void plainRandom() {
        MyRandom.setRandom(new Random());
    }

    private static PaperCard card(final String name) {
        return FModel.getMagicDb().getCommonCards().getCard(name);
    }

    private static Set<String> names(final List<PaperCard> cards) {
        return cards.stream().map(PaperCard::getName).collect(Collectors.toSet());
    }

    @Test
    public void basicsCostNothing() {
        final CardPool removed = new CardPool();
        removed.add(card("Forest"), 3);
        removed.add(card("Wastes"), 1);
        removed.add(card("Snow-Covered Island"), 1);
        Assert.assertEquals(RogueRun.removalCost(removed), 0);
    }

    @Test
    public void eachNonbasicCostsOne() {
        final CardPool removed = new CardPool();
        removed.add(card("Sol Ring"), 2);
        removed.add(card("Command Tower"), 1);
        removed.add(card("Forest"), 3);
        Assert.assertEquals(RogueRun.removalCost(removed), 3);
    }

    @Test
    public void aColouredCommanderHasNoWastes() {
        final List<PaperCard> lands = run.basicLands();
        Assert.assertEquals(run.getCommanderColorIdentityMask(), MagicColor.ALL_COLORS & ~MagicColor.BLACK);
        Assert.assertEquals(names(lands), Set.of("Plains", "Island", "Mountain", "Forest"));
        Assert.assertTrue(lands.size() > 4, "every art of the edition");
        Assert.assertEquals(new HashSet<>(lands).size(), lands.size());
    }

    @Test
    public void aColourlessCommanderHasOnlyWastes() {
        final CardPool commanders = run.getCurrentDeck().getOrCreate(DeckSection.Commander);
        commanders.clear();
        commanders.add(card("Kozilek, the Great Distortion"));
        Assert.assertEquals(names(run.basicLands()), Set.of("Wastes"));
    }

    @Test
    public void aMissingBasicComesFromTheFallback() {
        // Legions has no basic lands
        run.getSelectedRogueDeck().setLandEdition("LGN");
        final List<PaperCard> lands = run.basicLands();
        Assert.assertEquals(lands.size(), 4);
        Assert.assertEquals(names(lands), Set.of("Plains", "Island", "Mountain", "Forest"));
        lands.forEach(land -> Assert.assertEquals(land.getEdition(), "MH3"));
    }
}
