package forge.web;

import forge.deck.Deck;
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
