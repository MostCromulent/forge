package forge.web;

import forge.StaticData;
import forge.card.CardEdition;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.deck.DeckSection;
import forge.gamemodes.limited.SealedCardPoolGenerator;
import forge.item.PaperCard;
import forge.item.SealedTemplate;
import forge.model.CardBlock;
import forge.model.FModel;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class SealedProductsTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static CardBlock block(final String name) {
        for (final CardBlock b : FModel.getBlocks()) {
            if (b.getName().equals(name)) {
                return b;
            }
        }
        throw new AssertionError("No block called " + name);
    }

    /** Fails if addBoosters miscounts, or the Full path still prompts for a count. */
    @Test
    public void fullGivesTheChosenPackCount() {
        final CardPool pool = SealedCardPoolGenerator.full(6).getCardPool(false);
        assertEquals(pool.countAll(), 6 * SealedTemplate.genericDraftBooster.getNumberOfCardsExpected());
    }

    /** Fails if a combo is parsed differently from desktop's prompted path. */
    @Test
    public void aBlockComboGivesPacksOfThoseSets() {
        final CardBlock innistrad = block("Innistrad");
        final String combo = SealedCardPoolGenerator.blockCombos(innistrad).stream()
                .filter(c -> c.contains("ISD") && c.contains("DKA")).findFirst().orElseThrow();
        final List<String> codes = new ArrayList<>();
        for (final String part : combo.split(",")) {
            final String[] pieces = part.trim().split(" ");
            codes.add(pieces[pieces.length - 1]);
        }
        final CardPool pool = SealedCardPoolGenerator.block(innistrad, combo).getCardPool(false);
        assertTrue(pool.countAll() > 0);
        for (final Map.Entry<PaperCard, Integer> e : pool) {
            if (!e.getKey().getRules().getType().isBasicLand()) {
                assertTrue(codes.contains(e.getKey().getEdition()), e.getKey() + " is not from " + combo);
            }
        }
    }

    /** Fails if the prerelease bundle string is not parsed. */
    @Test
    public void aPrereleaseUsesItsEdition() {
        final List<CardEdition> editions = new ArrayList<>();
        StaticData.instance().getEditions().getPrereleaseEditions().forEach(editions::add);
        editions.sort(null);
        final CardEdition newest = editions.get(editions.size() - 1);
        final SealedCardPoolGenerator gen = SealedCardPoolGenerator.prerelease(newest);
        assertEquals(gen.getProductName(), newest.getName());
        assertFalse(gen.getCardPool(false).isEmpty());
    }

    /** Fails if GauntletMini's lookup of the group by the human deck's name would miss. */
    @Test
    public void buildGroupNamesTheHumanDeckAfterTheGroup() {
        final SealedCardPoolGenerator gen = SealedCardPoolGenerator.full(6);
        final CardPool pool = gen.getCardPool(false);
        final DeckGroup group = gen.buildGroup("Test pool", pool);
        assertEquals(group.getName(), "Test pool");
        final Deck human = group.getHumanDeck();
        assertNotNull(human);
        assertEquals(human.getName(), "Test pool");
        assertEquals(human.get(DeckSection.Sideboard).countAll(), pool.countAll());
        assertEquals(group.getAiDecks().size(), 7);
        assertEquals(Set.copyOf(group.getAiDecks()).size(), 7);
    }
}
