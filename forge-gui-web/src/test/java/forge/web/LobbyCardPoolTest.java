package forge.web;

import forge.deck.Deck;
import forge.game.GameFormat;
import forge.game.GameType;
import forge.gui.GuiBase;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

/** The Legality a host chooses for Constructed, checked against every seat. No match is played. */
public class LobbyLegalityTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static void onUi(final Runnable r) {
        GuiBase.getInterface().invokeInEdtAndWait(r);
    }

    private interface TableTest {
        void run(LocalGame local, Lobby lobby) throws Exception;
    }

    /** A hosted table with the computer's seat holding the given deck. */
    private static void atTable(final Deck computerDeck, final TableTest body) throws Exception {
        final LocalGame local = new LocalGame();
        final WebGuiGame gui = new WebGuiGame();
        try {
            onUi(() -> local.openHost("Host", gui, () -> { }, (from, text) -> { }));
            final Lobby lobby = new Lobby(local);
            final int computer = local.webSeat() == 0 ? 1 : 0;
            onUi(() -> {
                local.hostedLobby().getSlot(computer).setDeck(computerDeck);
                local.pushLobby();
            });
            body.run(local, lobby);
        } finally {
            gui.close();
            onUi(local::shutdown);
        }
    }

    /** Fails if only the deck-size check runs, so a Pauper table starts with a banned card in a deck. */
    @Test(timeOut = 60_000)
    public void aBannedCardStopsPlay() throws Exception {
        atTable(TestDecks.of("Atog Pile", "Atog", 4, "Mountain", 56), (local, lobby) -> {
            onUi(() -> lobby.setLegality("Pauper"));
            final List<String> problems = lobby.problems();
            Assert.assertTrue(problems.stream().anyMatch(p -> p.contains("Pauper") && p.contains("Atog")),
                    "no problem names Atog under Pauper: " + problems);
        });
    }

    /** Fails if the Legality survives a switch to Commander, where Pauper would then judge commander decks. */
    @Test(timeOut = 60_000)
    public void leavingConstructedClearsTheLegality() throws Exception {
        atTable(TestDecks.of("Bears", "Grizzly Bears", 20, "Forest", 40), (local, lobby) -> {
            onUi(() -> lobby.setLegality("Pauper"));
            Assert.assertNotNull(lobby.legality(), "the Legality was not taken");
            onUi(() -> lobby.setFormat(GameType.Commander.name()));
            Assert.assertNull(lobby.legality(), "Commander kept the Pauper Legality");
            onUi(() -> lobby.setLegality("Pauper"));
            Assert.assertNull(lobby.legality(), "a Legality was accepted outside Constructed");
        });
    }

    /**
     * Fails if the finder's list ignores the Legality: a colour generator builds from the whole card pool, or theme
     * decks, which cannot honour a pool, are still offered.
     */
    @Test(timeOut = 120_000)
    public void theDeckListFollowsTheLegality() throws Exception {
        atTable(TestDecks.of("Bears", "Grizzly Bears", 20, "Forest", 40), (local, lobby) -> {
            onUi(() -> lobby.setLegality("Pauper"));
            final ToBrowser.Decks decks = lobby.decks();
            Assert.assertEquals(decks.legality(), "Pauper");
            Assert.assertTrue(decks.decks().stream().noneMatch(d -> d.key().startsWith("gen:theme:")),
                    "a theme deck was offered under a card pool");
            final Deck coloured = lobby.deckForTest("gen:color:Red");
            Assert.assertNotNull(coloured, "the red generator built nothing");
            Assert.assertNull(DeckCatalog.poolProblem(FModel.getFormats().getFormat("Pauper"), coloured),
                    "the red generator used cards outside Pauper");
        });
    }

    /** Fails if the Legality control offers a heading with nothing under it, as the Block group can be. */
    @Test(timeOut = 60_000)
    public void everyLegalityHeadingHasFormats() throws Exception {
        atTable(TestDecks.of("Bears", "Grizzly Bears", 20, "Forest", 40), (local, lobby) -> {
            final var groups = lobby.state().table().legalities();
            Assert.assertFalse(groups.isEmpty(), "no card pools offered");
            for (final ToBrowser.LegalityGroup g : groups) {
                Assert.assertFalse(g.formats().isEmpty(), g.name() + " is offered with nothing in it");
            }
        });
    }

    /**
     * Fails if a generated deck dealt before the Legality keeps playing afterwards: the catalogue rebuilds its key
     * under the new pool, so the seat would show a legal deck while the slot still holds the old one.
     */
    @Test(timeOut = 120_000)
    public void aGeneratedDeckDealtBeforeTheLegalityIsDealtAgain() throws Exception {
        atTable(TestDecks.of("Bears", "Grizzly Bears", 20, "Forest", 40), (local, lobby) -> {
            final int computer = local.webSeat() == 0 ? 1 : 0;
            lobby.decks();
            onUi(() -> lobby.setDeck(computer, "gen:color:Red"));
            final GameFormat pauper = FModel.getFormats().getFormat("Pauper");
            Assert.assertNotNull(DeckCatalog.poolProblem(pauper, local.hostedLobby().getSlot(computer).getDeck()),
                    "the red deck was already Pauper-legal, so this test proves nothing");
            onUi(() -> lobby.setLegality("Pauper"));
            onUi(lobby::decks);
            Assert.assertNull(DeckCatalog.poolProblem(pauper, local.hostedLobby().getSlot(computer).getDeck()),
                    "the computer still plays its pre-Pauper deck");
        });
    }

    /** Fails if the deck list just sent is reported as out of date, which rebuilds the catalogue a second time. */
    @Test(timeOut = 60_000)
    public void aDeckListJustSentIsCurrent() throws Exception {
        atTable(TestDecks.of("Bears", "Grizzly Bears", 20, "Forest", 40), (local, lobby) -> {
            lobby.decks();
            Assert.assertFalse(lobby.restrictionsChanged(), "a fresh deck list counted as stale");
        });
    }

    /** Fails if one card in two printings is counted and named twice. */
    @Test
    public void aCardInTwoPrintingsIsNamedOnce() {
        final var db = FModel.getMagicDb().getCommonCards();
        final Deck deck = TestDecks.of("Atogs", "Mountain", 56);
        deck.getMain().add(db.getCard("Atog", "ATQ"), 2);
        deck.getMain().add(db.getCard("Atog", "MRD"), 2);
        final String problem = DeckCatalog.poolProblem(FModel.getFormats().getFormat("Pauper"), deck);
        Assert.assertEquals(problem, "Not legal in Pauper: 1 card. Atog.");
    }

    /** Fails if a Commander table is ever announced still holding a Constructed card pool. */
    @Test(timeOut = 60_000)
    public void noUpdateCarriesAPoolIntoCommander() throws Exception {
        final LocalGame local = new LocalGame();
        final WebGuiGame gui = new WebGuiGame();
        final java.util.concurrent.atomic.AtomicBoolean leaked = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            onUi(() -> local.openHost("Host", gui, () -> {
                final var hosted = local.hostedLobby();
                if (hosted != null && hosted.hasVariant(GameType.Commander) && hosted.getCardPool() != null) {
                    leaked.set(true);
                }
            }, (from, text) -> { }));
            final Lobby lobby = new Lobby(local);
            onUi(() -> lobby.setLegality("Pauper"));
            onUi(() -> lobby.setFormat(GameType.Commander.name()));
            Assert.assertFalse(leaked.get(), "an update showed Commander with the Pauper pool");
        } finally {
            gui.close();
            onUi(local::shutdown);
        }
    }

    /** Fails if a restricted-list problem is worded as a ban, or the card's name is lost. */
    @Test
    public void restrictedCardsAreNamedAsRestricted() {
        final Deck deck = TestDecks.of("Lotus", "Black Lotus", 2, "Island", 58);
        final String problem = DeckCatalog.poolProblem(FModel.getFormats().getFormat("Vintage"), deck);
        Assert.assertNotNull(problem);
        Assert.assertTrue(problem.contains("one copy") && problem.contains("Black Lotus"), problem);
    }

    /** Fails if a legal deck is reported, which would block Play for no reason. */
    @Test
    public void aLegalDeckHasNoProblem() {
        final Deck deck = TestDecks.of("Bears", "Grizzly Bears", 20, "Forest", 40);
        Assert.assertNull(DeckCatalog.poolProblem(FModel.getFormats().getFormat("Pauper"), deck));
    }
}
