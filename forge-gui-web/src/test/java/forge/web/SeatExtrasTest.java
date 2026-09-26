package forge.web;

import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.gui.GuiBase;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

/** What a seat brings beyond its main deck, and seats that need no deck at all. No match is played. */
public class SeatExtrasTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    static void onUi(final Runnable r) {
        GuiBase.getInterface().invokeInEdtAndWait(r);
    }

    interface TableTest {
        void run(LocalGame local, Lobby lobby) throws Exception;
    }

    /** A hosted table: the browser's seat and one computer seat, neither holding a deck. */
    static void atTable(final TableTest body) throws Exception {
        final LocalGame local = new LocalGame();
        final WebGuiGame gui = new WebGuiGame();
        try {
            onUi(() -> local.openHost("Host", gui, () -> { }, (from, text) -> { }));
            awaitSeat(local);
            body.run(local, new Lobby(local, () -> false, Map.of()));
        } finally {
            gui.close();
            onUi(local::shutdown);
        }
    }

    /** Waits for the browser's client to take its seat, which it does over the loopback after the table opens. */
    static void awaitSeat(final LocalGame local) throws InterruptedException {
        for (int i = 0; i < 100 && local.webSeat() < 0; i++) {
            Thread.sleep(100);
        }
        Assert.assertTrue(local.webSeat() >= 0, "the browser never took its seat");
    }

    /** Waits for the lobby to report no problems, and returns the last list it gave. */
    static List<String> awaitNoProblems(final Lobby lobby) throws InterruptedException {
        List<String> problems = lobby.problems();
        for (int i = 0; i < 100 && !problems.isEmpty(); i++) {
            Thread.sleep(100);
            problems = lobby.problems();
        }
        return problems;
    }

    /** The seat that is not the browser's own: the computer's. */
    static int computer(final LocalGame local) {
        return local.webSeat() == 0 ? 1 : 0;
    }

    /** The first deck in the catalogue that is built and has no problem. */
    static String legalDeck(final Lobby lobby) {
        return lobby.decks().decks().stream().filter(d -> d.generated() == null && d.problem() == null)
                .map(ToBrowser.DeckSummary::key).findFirst().orElseThrow();
    }

    static int count(final Deck deck, final DeckSection section) {
        final CardPool cards = deck == null ? null : deck.get(section);
        return cards == null ? 0 : cards.countAll();
    }

    /** Fails if a new main deck, sent as a whole deck, drops the planar deck the seat already had. */
    @Test(timeOut = 60_000)
    public void aNewMainDeckKeepsThePlanes() throws Exception {
        atTable((local, lobby) -> {
            final int c = computer(local);
            onUi(() -> lobby.setVariant("Planechase", true));
            onUi(lobby::decks);
            final String first = legalDeck(lobby);
            onUi(() -> lobby.setDeck(c, first));
            final int planes = count(local.hostedLobby().getSlot(c).getDeck(), DeckSection.Planes);
            Assert.assertTrue(planes >= 10, "the computer was given " + planes + " planes");
            final String second = lobby.decks().decks().stream().filter(d -> d.generated() == null && d.problem() == null)
                    .map(ToBrowser.DeckSummary::key).filter(k -> !k.equals(first)).findFirst().orElseThrow();
            onUi(() -> lobby.setDeck(c, second));
            Assert.assertEquals(count(local.hostedLobby().getSlot(c).getDeck(), DeckSection.Planes), planes,
                    "a new main deck dropped the planes");
        });
    }

    /** Fails if a missing avatar, or a planar or scheme deck too small, reaches Play, where startGame would refuse it. */
    @Test(timeOut = 120_000)
    public void problemsCatchEachVariantFault() throws Exception {
        final var prefs = FModel.getPreferences();
        final boolean enforced = prefs.getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY);
        prefs.setPref(FPref.ENFORCE_DECK_LEGALITY, true);
        try {
            atTable((local, lobby) -> {
                final int c = computer(local);
                onUi(lobby::decks);
                final String deck = legalDeck(lobby);
                onUi(() -> lobby.setDeck(c, deck));
                onUi(() -> lobby.setVariant("Planechase", true));
                onUi(() -> lobby.setVariant("Vanguard", true));
                onUi(lobby::decks);
                // A precon names no avatar and carries no planes, so "its own" leaves both sections empty
                onUi(() -> lobby.setSeatExtra(c, "Avatar", DeckCatalog.OWN));
                onUi(() -> lobby.setSeatExtra(c, "Planes", DeckCatalog.OWN));
                final List<String> problems = lobby.problems();
                final String name = local.hostedLobby().getSlot(c).getName();
                Assert.assertTrue(problems.contains(name + " has no avatar."), "no avatar problem: " + problems);
                Assert.assertTrue(problems.stream().anyMatch(p -> p.startsWith(name + "'s planar deck")), "no planes problem: " + problems);
                onUi(() -> lobby.setSeatExtra(c, "Avatar", DeckCatalog.RANDOM));
                onUi(() -> lobby.setSeatExtra(c, "Planes", DeckCatalog.GENERATE));
                Assert.assertTrue(lobby.problems().stream().noneMatch(p -> p.startsWith(name)),
                        "a legal computer seat still had problems: " + lobby.problems());
            });
        } finally {
            prefs.setPref(FPref.ENFORCE_DECK_LEGALITY, enforced);
        }
    }

    /** Fails if the web lobby sets the archenemy itself instead of asking the engine's lobby, which keeps one. */
    @Test(timeOut = 60_000)
    public void theArchenemyMoves() throws Exception {
        atTable((local, lobby) -> {
            final int c = computer(local);
            onUi(() -> lobby.setVariant("Archenemy", true));
            onUi(() -> lobby.setArchenemy(c));
            Assert.assertTrue(local.hostedLobby().getSlot(c).isArchenemy(), "the computer did not become the archenemy");
            Assert.assertFalse(local.hostedLobby().getSlot(local.webSeat()).isArchenemy(), "two archenemies");
            onUi(lobby::decks);
            Assert.assertTrue(count(local.hostedLobby().getSlot(c).getDeck(), DeckSection.Schemes) == 0
                    || count(local.hostedLobby().getSlot(c).getDeck(), DeckSection.Schemes) >= 20,
                    "the archenemy's schemes were not dealt as a deck");
        });
    }

    /** Fails if a computer seat's random avatar can be one the computer cannot play. */
    @Test(timeOut = 60_000)
    public void aComputerSeatGetsAComputerAvatar() throws Exception {
        atTable((local, lobby) -> {
            final int c = computer(local);
            onUi(lobby::decks);
            final String deck = legalDeck(lobby);
            onUi(() -> lobby.setDeck(c, deck));
            onUi(() -> lobby.setVariant("Vanguard", true));
            onUi(lobby::decks);
            for (int i = 0; i < 20; i++) {
                onUi(() -> lobby.setSeatExtra(c, "Avatar", DeckCatalog.RANDOM));
                final CardPool avatar = local.hostedLobby().getSlot(c).getDeck().get(DeckSection.Avatar);
                Assert.assertNotNull(avatar, "the computer got no avatar");
                final var card = avatar.iterator().next().getKey();
                Assert.assertFalse(card.getRules().getAiHints().getRemAIDecks(), card.getName() + " is not for the computer");
            }
        });
    }

    /**
     * A hosted table whose lobby answers its own updates as a browser's session does, rebuilding the deck list when
     * the rules change. Updates arrive while a change is still being made, which the plain table does not show.
     */
    static void atLiveTable(final TableTest body) throws Exception {
        final LocalGame local = new LocalGame();
        final WebGuiGame gui = new WebGuiGame();
        final java.util.concurrent.atomic.AtomicReference<Lobby> ref = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            onUi(() -> local.openHost("Host", gui, () -> {
                final Lobby lobby = ref.get();
                if (lobby != null && lobby.restrictionsChanged()) {
                    lobby.decks();
                }
            }, (from, text) -> { }));
            ref.set(new Lobby(local, () -> false, Map.of()));
            awaitSeat(local);
            body.run(local, ref.get());
        } finally {
            gui.close();
            onUi(local::shutdown);
        }
    }

    /** Fails if switching to Momir Basic wipes the planes the computer's seat was just dealt. */
    @Test(timeOut = 60_000)
    public void momirKeepsTheComputersPlanes() throws Exception {
        atLiveTable((local, lobby) -> {
            final int c = computer(local);
            onUi(lobby::decks);
            onUi(() -> lobby.setVariant("Planechase", true));
            onUi(() -> lobby.setFormat(GameType.MomirBasic.name()));
            onUi(() -> {
                if (lobby.restrictionsChanged()) {
                    lobby.decks();
                }
            });
            // The table's own update deals the seats on another thread, which can still be at it here
            for (int i = 0; i < 250 && count(local.hostedLobby().getSlot(c).getDeck(), DeckSection.Planes) < 10; i++) {
                Thread.sleep(20);
            }
            Assert.assertTrue(count(local.hostedLobby().getSlot(c).getDeck(), DeckSection.Planes) >= 10,
                    "the computer lost its planes when the format changed");
        });
    }

    /** Fails if a computer seat added after a variant came on is dealt its deck without the variant's section. */
    @Test(timeOut = 60_000)
    public void anAddedSeatGetsItsAvatar() throws Exception {
        atTable((local, lobby) -> {
            onUi(lobby::decks);
            final String deck = legalDeck(lobby);
            onUi(() -> lobby.setVariant("Vanguard", true));
            onUi(lobby::decks);
            onUi(() -> lobby.setPlayerCount(local.hostedLobby().getNumberOfSlots() + 1));
            final int added = local.hostedLobby().getNumberOfSlots() - 1;
            onUi(() -> lobby.setDeck(added, deck));
            Assert.assertEquals(count(local.hostedLobby().getSlot(added).getDeck(), DeckSection.Avatar), 1,
                    "the added computer seat has no avatar");
        });
    }

    /** Fails if the teams Archenemy set stay behind once it is off, which makes a later free-for-all a team game. */
    @Test(timeOut = 60_000)
    public void teamsGoBackWhenArchenemyEnds() throws Exception {
        atTable((local, lobby) -> {
            onUi(() -> lobby.setPlayerCount(local.hostedLobby().getNumberOfSlots() + 1));
            onUi(() -> lobby.setVariant("Archenemy", true));
            onUi(() -> lobby.setArchenemy(computer(local)));
            onUi(() -> lobby.setVariant("Archenemy", false));
            final var host = local.hostedLobby();
            final java.util.Set<Integer> teams = new java.util.HashSet<>();
            for (int i = 0; i < host.getNumberOfSlots(); i++) {
                teams.add(host.getSlot(i).getTeam());
            }
            Assert.assertEquals(teams.size(), host.getNumberOfSlots(), "seats still share a team: " + teams);
        });
    }

    /** Fails if a Momir seat reports its empty dealt deck as too small, which the format never builds. */
    @Test(timeOut = 60_000)
    public void aMomirSeatShowsNoDeckFault() throws Exception {
        final var prefs = FModel.getPreferences();
        final boolean enforced = prefs.getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY);
        prefs.setPref(FPref.ENFORCE_DECK_LEGALITY, true);
        try {
            atTable((local, lobby) -> {
                onUi(() -> lobby.setFormat(GameType.MomirBasic.name()));
                onUi(lobby::decks);
                for (final ToBrowser.Seat seat : lobby.state().table().seats()) {
                    Assert.assertNull(seat.problem(), seat.name() + " shows " + seat.problem());
                }
            });
        } finally {
            prefs.setPref(FPref.ENFORCE_DECK_LEGALITY, enforced);
        }
    }

    /** Fails if Momir Basic still demands a deck, or a seat's readiness waits on a deck nobody can choose. */
    @Test(timeOut = 60_000)
    public void aMomirTableIsReadyWithoutDecks() throws Exception {
        atTable((local, lobby) -> {
            onUi(() -> lobby.setFormat(GameType.MomirBasic.name()));
            onUi(lobby::decks);
            Assert.assertEquals(awaitNoProblems(lobby), List.of(), "a Momir table could not start");
        });
    }
}
