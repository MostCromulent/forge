package forge.web;

import forge.deck.Deck;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.limited.SealedCardPoolGenerator;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.StaticData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/** The core entry points online web events use, which desktop leaves at their defaults. */
public class OnlineCoreTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    // Fails if configuring a sealed event from a built pool still runs the prompting constructor, whose questions a
    // browser host cannot answer there; the time limit turns such a hang into a failure
    @Test(timeOut = 30_000)
    public void configureWithABuiltGenerator() {
        final ServerGameLobby lobby = new ServerGameLobby();
        lobby.createEvent(EventFormat.SEALED);
        final SealedCardPoolGenerator gen = SealedCardPoolGenerator.full(6);
        assertTrue(lobby.configureEvent(LimitedPoolType.Full, gen, 0, 0));
        assertSame(lobby.getCurrentEvent().getSealedGenerator(), gen);
    }

    // Fails if startGame seats a benched slot: two ready seats with one benched are too few to play
    @Test
    public void aBenchedSeatSitsOut() {
        final ServerGameLobby lobby = new ServerGameLobby();
        final Deck deck = new Deck("Forests");
        deck.getMain().add(StaticData.instance().getCommonCards().getCard("Forest"), 40);
        for (int i = 0; i < 2; i++) {
            final LobbySlot slot = lobby.getSlot(i);
            slot.setType(LobbySlotType.AI);
            slot.setName("Seat " + i);
            slot.setDeck(deck);
            slot.setIsReady(true);
        }
        lobby.getSlot(1).setBenched(true);
        assertNull(lobby.startGame(), "a benched seat was counted as a player");
    }
}
