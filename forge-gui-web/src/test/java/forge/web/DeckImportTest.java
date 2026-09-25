package forge.web;

import forge.deck.DeckSection;
import forge.game.GameType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

public class DeckImportTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static List<String> kinds(final DeckImport.Read r) {
        return r.lines().stream().map(ToBrowser.ImportLine::kind).toList();
    }

    // Fails if the gutter and the text disagree: every input line needs exactly one mark
    @Test
    public void exportHeadingsAreIgnored() {
        final String text = "Name: Burn\nCreatures (4)\n4 Goblin Guide\n\n// spells\n4 Lightning Bolt [M11] 146\nSideboard\n2 Duress";
        final DeckImport.Read r = DeckImport.read(text, Check.of(GameType.Constructed, null));
        Assert.assertEquals(kinds(r), List.of("heading", "ignored", "read", "ignored", "ignored", "read", "heading", "read"));
        Assert.assertEquals(r.name(), "Burn");
        Assert.assertEquals(r.deck().getMain().countByName("Lightning Bolt"), 4);
        Assert.assertEquals(r.deck().get(DeckSection.Sideboard).countByName("Duress"), 2);
    }

    // Fails if a typo gives no way to fix it
    @Test
    public void typoOffersClosestName() {
        final DeckImport.Read r = DeckImport.read("1 Nihil Spelbomb", Check.of(GameType.Constructed, null));
        Assert.assertEquals(kinds(r), List.of("problem"));
        Assert.assertEquals(r.problems().get(0).fixes().get(0).kind(), "use");
        Assert.assertEquals(r.problems().get(0).fixes().get(0).text(), "Nihil Spellbomb");
    }

    // Fails if one legendary creature in a Commander list is not made the commander
    @Test
    public void singleCandidateBecomesCommander() {
        final DeckImport.Read r = DeckImport.read("1 Meren of Clan Nel Toth\n1 Sol Ring\n98 Swamp", Check.of(GameType.Commander, null));
        Assert.assertEquals(r.deck().getCommanders().get(0).getName(), "Meren of Clan Nel Toth");
        Assert.assertEquals(r.deck().getMain().countByName("Meren of Clan Nel Toth"), 0);
    }

    // Fails if two candidates are guessed rather than asked
    @Test
    public void twoCandidatesAsk() {
        final DeckImport.Read r = DeckImport.read("1 Meren of Clan Nel Toth\n1 Sidisi, Undead Vizier\n98 Swamp",
                Check.of(GameType.Commander, null));
        Assert.assertTrue(r.deck().getCommanders().isEmpty());
        Assert.assertEquals(r.problems().get(0).line(), -1);
        Assert.assertEquals(r.problems().get(0).fixes().stream().filter(f -> f.kind().equals("commander")).count(), 2);
    }

    // Fails if a banned card is dropped rather than imported and marked
    @Test
    public void bannedCardImportedAndMarked() {
        final DeckImport.Read r = DeckImport.read("Commander\n1 Meren of Clan Nel Toth\nDeck\n1 Mana Crypt", Check.of(GameType.Commander, null));
        Assert.assertEquals(r.deck().getMain().countByName("Mana Crypt"), 1);
        Assert.assertEquals(kinds(r).get(3), "problem");
    }
}
