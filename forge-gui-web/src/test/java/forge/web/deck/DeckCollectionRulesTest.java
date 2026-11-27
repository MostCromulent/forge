package forge.web.deck;

import forge.StaticData;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.Localizer;
import forge.web.WebTestSupport;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.FromBrowser.Bare;
import forge.web.protocol.FromBrowser.EditOp;
import forge.web.protocol.FromBrowser.EditorEdit;
import forge.web.protocol.FromBrowser.Plain;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DeckCollectionRulesTest {
    private static final String NO_CREDITS = "No credits left";
    private final DeckStore.Storages storages = format -> {
        throw new AssertionError("a run's deck is not stored with the player's decks");
    };
    private final List<Deck> saved = new ArrayList<>();
    private int credits;
    private final Deque<Integer> spent = new ArrayDeque<>();

    /** Each card but a basic land costs a credit to take out, and an undo gives back what its change spent. */
    private final DeckEditor.Rules rules = new DeckEditor.Rules() {
        private int cost(final CardPool cards) {
            return cards.toFlatList().stream().filter(c -> !c.getRules().getType().isBasicLand()).toList().size();
        }

        @Override
        public String refuse(final CardPool removed) {
            return cost(removed) > credits ? NO_CREDITS : null;
        }

        @Override
        public void changed(final CardPool removed, final boolean undo) {
            if (undo) {
                credits += spent.pop();
            } else {
                spent.push(cost(removed));
                credits -= cost(removed);
            }
        }

        @Override
        public String counter() {
            return credits + " credits";
        }

        @Override
        public int credits() {
            return credits;
        }

        @Override
        public boolean free(final PaperCard card) {
            return card.getRules().getType().isBasicLand();
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
        spent.clear();
        saved.clear();
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

    private DeckEditor.Collection collection(final boolean mainOnly) {
        return new DeckEditor.Collection("Run", CardPool::new, c -> false, () -> { }, d -> List.of(), () -> { }, mainOnly, rules);
    }

    private DeckEditor editor(final Deck deck, final boolean mainOnly) {
        return new DeckEditor(deck, false, true, new DeckEditor.Run(saved::add), LegalityScope.of(GameType.RogueCommander, null), storages,
                false, (id, text, f) -> { }, collection(mainOnly));
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
    }

    // Fails if a removal of several cards is charged or undone card by card rather than as one change
    @Test
    public void removingSeveralCardsIsOneChange() {
        final DeckEditor e = editor(deck(), true);
        Assert.assertEquals(e.state().removals().free(), List.of("Forest"));
        Assert.assertNull(e.removeCards(Map.of("Sol Ring", 1, "Shock", 1, "Forest", 2)));
        Assert.assertEquals(e.deck().getMain().countAll(), 3);
        Assert.assertEquals(spent.size(), 1);
        Assert.assertEquals(credits, 0);
        Assert.assertEquals(e.state().removals().credits(), 0);
        Assert.assertNull(e.undo());
        assertMain(e, deck());
        Assert.assertEquals(credits, 2);
        Assert.assertFalse(e.state().canUndo());
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
        Assert.assertEquals(e.setLands(Map.of("Wastes", 1)), Localizer.getInstance().getMessage(DeckEditor.NOT_OWNED));
        assertMain(e, deck());
        e.state().lands().forEach(l -> Assert.assertEquals(l.refusal() == null, l.name().equals("Forest"), l.name()));
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

    // Fails if a change is not saved, if a refused one is, or if a saved deck changes with the next change
    @Test
    public void eachChangeIsSavedThroughTheTarget() {
        final DeckEditor e = editor(deck(), true);
        Assert.assertNull(e.remove("Sol Ring", DeckSection.Main, 1));
        Assert.assertNull(e.setLands(Map.of("Forest", 5)));
        credits = 0;
        Assert.assertEquals(e.remove("Shock", DeckSection.Main, 1), NO_CREDITS);
        Assert.assertNull(e.undo());
        Assert.assertEquals(saved.stream().map(d -> d.getMain().countAll()).toList(), List.of(6, 7, 6));
        Assert.assertEquals(saved.get(0).getCommanders().size(), 1);
    }

    // Fails if an undo sent after the editor closed changes the run's deck, or if an editor opened since can undo what the closed one did
    @Test
    public void aClosedEditorCannotUndo() {
        final BrowserChannel channel = message -> { };
        final DeckWorkspace decks = new DeckWorkspace(null, null, () -> true, () -> false, new HashMap<>());
        final Runnable open = () -> decks.openCollectionDeck(deck(), new DeckEditor.Run(saved::add), GameType.RogueCommander, collection(true),
                true, channel);
        final Runnable remove = () -> DeckWorkspace.COMMANDS.run(decks, channel,
                new EditorEdit(EditOp.remove, "Sol Ring", DeckSection.Main, null, 1, null, null, null));
        final Runnable undo = () -> DeckWorkspace.COMMANDS.run(decks, channel, new Bare(Plain.editorUndo));
        open.run();
        remove.run();
        undo.run();
        Assert.assertEquals(saved.size(), 2);
        remove.run();
        Assert.assertEquals(credits, 1);
        decks.closeCollectionDeck(channel);
        undo.run();
        open.run();
        undo.run();
        Assert.assertEquals(saved.size(), 3);
        Assert.assertEquals(credits, 1);
    }
}
