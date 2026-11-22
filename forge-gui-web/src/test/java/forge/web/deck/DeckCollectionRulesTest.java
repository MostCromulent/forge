package forge.web.deck;

import forge.StaticData;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.storage.StorageBase;
import forge.web.WebTestSupport;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DeckCollectionRulesTest {
    private static final String NO_CREDITS = "No credits left";
    private final DeckStore.Storages storages = format -> {
        throw new AssertionError("a run's deck is not stored with the player's decks");
    };
    private int credits;

    /** Each card but a basic land costs a credit to take out. */
    private final DeckEditor.Rules rules = new DeckEditor.Rules() {
        private int cost(final CardPool cards) {
            return cards.toFlatList().stream().filter(c -> !c.getRules().getType().isBasicLand()).toList().size();
        }

        @Override
        public String refuse(final CardPool removed) {
            return cost(removed) > credits ? NO_CREDITS : null;
        }

        @Override
        public void changed(final CardPool removed, final CardPool restored) {
            credits += cost(restored) - cost(removed);
        }

        @Override
        public String counter() {
            return credits + " credits";
        }

        @Override
        public Set<String> basics() {
            return Set.of("Forest");
        }
    };

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
        FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, true);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, false);
    }

    @BeforeMethod
    public void twoCredits() {
        credits = 2;
    }

    private static PaperCard card(final String name) {
        return StaticData.instance().getCommonCards().getCard(name);
    }

    private static Deck deck() {
        final Deck deck = new Deck("Run");
        deck.getOrCreate(DeckSection.Commander).add(card("Marwyn, the Nurturer"));
        for (final String name : List.of("Sol Ring", "Llanowar Elves", "Shock")) {
            deck.getMain().add(card(name));
        }
        deck.getMain().add(card("Forest"), 4);
        return deck;
    }

    private static DeckEditor.Collection collection(final boolean mainOnly, final DeckEditor.Rules rules) {
        return new DeckEditor.Collection("Run", CardPool::new, c -> false, () -> { }, d -> List.of(), () -> { }, mainOnly, rules);
    }

    private DeckEditor editor(final Deck deck, final boolean mainOnly) {
        final Map<String, Deck> stored = new HashMap<>();
        return new DeckEditor(deck, false, true, new DeckEditor.Stored(new StorageBase<>("Runs", null, stored) {
            @Override
            public void add(final Deck saved) {
                stored.put(saved.getName(), saved);
            }
        }), LegalityScope.of(GameType.RogueCommander, null), storages, false, (id, text, f) -> { }, collection(mainOnly, rules));
    }

    private static void assertMain(final DeckEditor e, final Deck wanted) {
        Assert.assertTrue(e.deck().getMain().equals(wanted.getMain()), e.deck().getMain().toString());
    }

    // Fails if a removal the rules refuse changes the deck, or leaves a step in the undo history
    @Test
    public void aRemovalBeyondTheCreditsIsRefused() {
        final DeckEditor e = editor(deck(), true);
        Assert.assertNull(e.remove("Sol Ring", DeckSection.Main, 1));
        Assert.assertNull(e.remove("Shock", DeckSection.Main, 1));
        Assert.assertEquals(credits, 0);
        Assert.assertEquals(e.state().counter(), "0 credits");
        Assert.assertEquals(e.remove("Llanowar Elves", DeckSection.Main, 1), NO_CREDITS);
        Assert.assertEquals(e.deck().getMain().countAll(), 5);
        Assert.assertEquals(e.deck().getMain().countByName("Llanowar Elves"), 1);
        Assert.assertEquals(credits, 0);
        Assert.assertNull(e.remove("Forest", DeckSection.Main, 1), "a basic land is free");
        Assert.assertNull(e.undo());
        Assert.assertNull(e.undo());
        Assert.assertEquals(e.deck().getMain().countByName("Shock"), 1);
    }

    @Test
    public void undoRefundsExactlyWhatWasSpent() {
        final DeckEditor e = editor(deck(), true);
        Assert.assertNull(e.remove("Sol Ring", DeckSection.Main, 1));
        Assert.assertNull(e.setLands(Map.of("Forest", 1)));
        Assert.assertNull(e.remove("Shock", DeckSection.Main, 1));
        Assert.assertEquals(credits, 0);
        Assert.assertNull(e.undo());
        Assert.assertEquals(credits, 1);
        Assert.assertNull(e.undo());
        Assert.assertEquals(credits, 1);
        Assert.assertNull(e.undo());
        Assert.assertEquals(credits, 2);
        assertMain(e, deck());
    }

    @Test
    public void undoOfAnAddSpendsNothing() {
        final DeckEditor e = editor(deck(), true);
        Assert.assertNull(e.setLands(Map.of("Forest", 9)));
        Assert.assertEquals(credits, 2);
        Assert.assertNull(e.undo());
        Assert.assertEquals(credits, 2);
        assertMain(e, deck());
    }

    // Fails if an undo takes out cards the rules would not let the player take out
    @Test
    public void undoOfAnAddIsAskedToo() {
        final DeckEditor e = editor(deck(), false);
        Assert.assertNull(e.move("Sol Ring", DeckSection.Main, DeckSection.Sideboard, 1));
        Assert.assertNull(e.move("Sol Ring", DeckSection.Sideboard, DeckSection.Main, 1));
        credits = 0;
        Assert.assertEquals(e.undo(), NO_CREDITS);
        assertMain(e, deck());
        credits = 1;
        Assert.assertNull(e.undo());
        Assert.assertEquals(e.deck().getMain().countByName("Sol Ring"), 0);
        Assert.assertEquals(credits, 0);
    }

    @Test
    public void movingOutOfMainSpends() {
        final DeckEditor e = editor(deck(), false);
        Assert.assertNull(e.move("Sol Ring", DeckSection.Main, DeckSection.Sideboard, 1));
        Assert.assertEquals(credits, 1);
        credits = 0;
        Assert.assertEquals(e.move("Shock", DeckSection.Main, DeckSection.Sideboard, 1), NO_CREDITS);
        Assert.assertEquals(e.deck().get(DeckSection.Sideboard).countAll(), 1);
        Assert.assertEquals(e.deck().getMain().countByName("Shock"), 1);
    }

    @Test
    public void importReplaceIsRefused() {
        final DeckEditor e = editor(deck(), true);
        final Deck other = new Deck("List");
        other.getMain().add(card("Forest"), 40);
        Assert.assertFalse(e.state().canReplace());
        Assert.assertNotNull(e.replaceAll(other));
        assertMain(e, deck());
        Assert.assertEquals(credits, 2);
    }

    @Test
    public void importAddCannotBringBackARemovedCard() {
        final DeckEditor e = editor(deck(), true);
        Assert.assertNull(e.remove("Sol Ring", DeckSection.Main, 1));
        final DeckImport.Read read = DeckImport.read("1 Sol Ring\n2 Forest\n1 Wastes", e.check(), e.collection());
        Assert.assertEquals(read.notImported(), 2);
        Assert.assertEquals(read.problems().size(), 2, "a list that holds no commander is no problem");
        Assert.assertNull(read.legality().verdict());
        Assert.assertNull(e.addAll(read.deck()));
        Assert.assertEquals(e.deck().getMain().countByName("Forest"), 6);
        final Deck list = new Deck("List");
        list.getMain().add(card("Sol Ring"));
        list.getMain().add(card("Wastes"));
        Assert.assertNull(e.addAll(list));
        Assert.assertEquals(e.deck().getMain().countAll(), 8);
        Assert.assertNotNull(e.add("Sol Ring", DeckSection.Main, 1));
    }

    @Test
    public void wastesAreRefusedWhenNotListed() {
        final DeckEditor e = editor(deck(), true);
        Assert.assertNotNull(e.add("Wastes", DeckSection.Main, 1));
        Assert.assertNotNull(e.setLands(Map.of("Wastes", 1)));
        assertMain(e, deck());
        e.state().lands().forEach(l -> Assert.assertEquals(l.allowed(), l.name().equals("Forest"), l.name()));
        Assert.assertNull(e.add("Forest", DeckSection.Main, 1));
    }

    @Test
    public void aNonbasicCannotBeReprinted() {
        final Deck deck = deck();
        deck.getMain().removeAll(card("Shock"));
        deck.getMain().add(StaticData.instance().getCommonCards().getCard("Shock", "M19"));
        final DeckEditor e = editor(deck, true);
        final PaperCard other = StaticData.instance().getCommonCards().getCard("Shock", "10E");
        Assert.assertNotNull(e.setPrintings("Shock", DeckSection.Main, Map.of(other.getImageKey(false), 1)));
        assertMain(e, deck);
        Assert.assertEquals(credits, 2);
        final PaperCard forest = StaticData.instance().getCommonCards().getCard("Forest", "10E");
        Assert.assertNull(e.setPrintings("Forest", DeckSection.Main, Map.of(forest.getImageKey(false), 4)));
        Assert.assertEquals(credits, 2);
    }

    // Fails if a deck beyond the format's 45 cards, or with a card its commander's colours do not allow, is called illegal
    @Test
    public void noSizeVerdict() {
        final Deck deck = deck();
        deck.getMain().add(card("Forest"), 57);
        Assert.assertEquals(deck.getMain().countAll(), 64);
        Assert.assertNotNull(Legality.check(deck, LegalityScope.of(GameType.RogueCommander, null)).verdict());
        Assert.assertNull(editor(deck, true).state().verdict());
    }
}
