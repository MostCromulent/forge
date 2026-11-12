package forge.net;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.gamemodes.match.DrawOfferCoordinator;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LocalLobby;
import org.testng.Assert;
import org.testng.annotations.Test;

/** A local lobby starts a new match whenever asked, as each one opens on a screen of its own. */
public class LocalLobbyStartTest {
    private static Deck deck(final String name) {
        final Deck deck = new Deck(name);
        deck.getMain().add("Forest", 40);
        deck.getMain().add("Grizzly Bears", 20);
        return deck;
    }

    private static void end(final HostedMatch match) {
        final Game game = match.getGame();
        game.getAction().invoke(() -> {
            game.setGameOver(GameEndReason.Draw);
            DrawOfferCoordinator.releaseInputQueues(game);
        });
    }

    // Fails if a player whose game is running cannot start another one from the local lobby
    @Test(timeOut = 120_000)
    public void aRunningGameDoesNotBlockTheNextStart() {
        TestUtils.ensureFModelInitialized();
        final LocalLobby lobby = new LocalLobby();
        lobby.getSlot(0).setDeck(deck("Local"));
        lobby.getSlot(1).setDeck(deck("Ai"));

        lobby.startGame().run();
        final HostedMatch first = lobby.getHostedMatch(0);
        Assert.assertNotNull(first);
        HostedMatch second = null;
        try {
            Assert.assertTrue(first.isGameRunning());
            final Runnable next = lobby.startGame();
            Assert.assertNotNull(next, "the running game blocked the next start");
            next.run();
            second = lobby.getHostedMatch(0);
            Assert.assertNotSame(second, first, "the lobby does not answer for its newest match");
            Assert.assertTrue(first.isGameRunning(), "starting another match ended the running one");
        } finally {
            end(first);
            if (second != null && second != first) {
                end(second);
            }
        }
    }
}
