package forge.gamemodes.match;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.DeckgenUtil;
import forge.game.GameType;
import forge.game.player.Player;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.net.TestDeckLoader;
import forge.net.TestUtils;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

public class GameLobbyVariantTest {
    @BeforeClass
    public void setUp() {
        TestUtils.ensureFModelInitialized();
    }

    /** Every seat a computer, ready, holding the given deck. */
    private static LocalLobby lobbyOf(final int seats, final Deck deck) {
        final LocalLobby lobby = new LocalLobby();
        while (lobby.getNumberOfSlots() < seats) {
            lobby.addSlot();
        }
        for (int i = 0; i < seats; i++) {
            final LobbySlot slot = lobby.getSlot(i);
            slot.setType(LobbySlotType.AI);
            slot.setDeck(deck);
            slot.setIsReady(true);
        }
        return lobby;
    }

    /** Starts the match and returns its players; the game is ended again before returning. */
    private static List<Player> startWith(final LocalLobby lobby) {
        final Runnable start = lobby.startGame();
        Assert.assertNotNull(start, "the lobby refused to start");
        start.run();
        final List<Player> players = List.copyOf(lobby.getHostedMatch().getGame().getPlayers());
        lobby.getHostedMatch().endCurrentGame();
        return players;
    }

    /** Fails if a generated Momir deck drops the planar deck the seat brought. */
    @Test
    public void momirKeepsTheSeatsPlanes() {
        final Deck planesOnly = new Deck("Planes only");
        planesOnly.putSection(DeckSection.Planes, DeckgenUtil.generatePlanarPool());
        final LocalLobby lobby = lobbyOf(2, planesOnly);
        lobby.applyVariant(GameType.MomirBasic);
        lobby.applyVariant(GameType.Planechase);
        for (final Player p : startWith(lobby)) {
            Assert.assertTrue(p.getRegisteredPlayer().getPlanes().iterator().hasNext(),
                    p.getName() + " started with no planes");
        }
    }

    /** Fails if heroes keep a team each when the archenemy role was never moved, so they fight each other. */
    @Test
    public void heroesShareATeamByDefault() {
        final Deck deck = TestDeckLoader.createMinimalDeck("Forest", 60);
        deck.putSection(DeckSection.Schemes, DeckgenUtil.generateSchemePool());
        final LocalLobby lobby = lobbyOf(4, deck);
        lobby.applyVariant(GameType.Archenemy);
        final List<Player> players = startWith(lobby);
        Assert.assertEquals(players.size(), 4);
        // Only the archenemy's seat is registered with schemes; the zone itself fills later, on the game's thread
        Assert.assertEquals(players.stream().filter(GameLobbyVariantTest::registeredArchenemy).count(), 1L,
                "not exactly one archenemy");
        for (final Player p : players) {
            Assert.assertEquals(p.getTeam(), registeredArchenemy(p) ? 0 : 1, p.getName() + " is on the wrong team");
        }
    }

    /** Fails if removing seats after the role moved drives lastArchenemy to -1 and the next removal throws. */
    @Test
    public void removingSeatsKeepsOneArchenemy() {
        final LocalLobby lobby = lobbyOf(4, TestDeckLoader.createMinimalDeck("Forest", 60));
        lobby.applyVariant(GameType.Archenemy);
        lobby.applyToSlot(2, UpdateLobbyPlayerEvent.create(
                null, null, -1, -1, -1, true, lobby.getSlot(2).isDevMode(), null, null));
        Assert.assertTrue(lobby.getSlot(2).isArchenemy(), "the role did not move");
        lobby.removeSlot(3);
        lobby.removeSlot(2);
        int archenemies = 0;
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            archenemies += lobby.getSlot(i).isArchenemy() ? 1 : 0;
        }
        Assert.assertEquals(archenemies, 1);
    }

    private static boolean registeredArchenemy(final Player p) {
        final Iterable<?> schemes = p.getRegisteredPlayer().getSchemes();
        return schemes != null && schemes.iterator().hasNext();
    }
}
