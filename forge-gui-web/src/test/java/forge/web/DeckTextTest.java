package forge.web;

import forge.StaticData;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.util.FileSection;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Arrays;

public class DeckTextTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    // Fails if a guest's deck, kept in the browser as text, comes back with a different printing or section
    @Test
    public void deckSurvivesTextRoundTrip() {
        final Deck deck = new Deck("Round trip");
        deck.getMain().add(StaticData.instance().getCommonCards().getCard("Lightning Bolt", "M11", 1), 3);
        deck.getOrCreate(DeckSection.Sideboard).add(StaticData.instance().getCommonCards().getCard("Duress"), 2);
        final String text = String.join("\n", DeckSerializer.serializeDeck(deck));
        final Deck back = DeckSerializer.fromSections(FileSection.parseSections(Arrays.asList(text.split("\\r?\\n"))));
        Assert.assertEquals(back.getName(), "Round trip");
        Assert.assertEquals(back.getMain().count(StaticData.instance().getCommonCards().getCard("Lightning Bolt", "M11", 1)), 3);
        Assert.assertEquals(back.get(DeckSection.Sideboard).countByName("Duress"), 2);
    }
}
