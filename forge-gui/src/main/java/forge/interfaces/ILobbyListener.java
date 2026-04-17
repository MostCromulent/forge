package forge.interfaces;

import java.util.List;

import forge.deck.Deck;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.net.NetworkEventView;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.item.PaperCard;

public interface ILobbyListener {
    void message(String source, String message);
    void update(GameLobbyData state, int slot);
    void close();
    ClientGameLobby getLobby();

    default void eventCreated(NetworkEventView view) { }
    default void draftPackArrived(int seatIndex, List<PaperCard> pack,
            int packNumber, int pickNumber, int timerDurationSeconds) { }
    default void draftSeatPicked(int seatIndex, int pickNumber, int[] seatQueueDepths) { }
    default void draftAutoPicked(int seatIndex, PaperCard card, int pickNumber) { }
    default void receiveEventPool(String eventId, Deck pool) { }
    default void selectEventForMatch(String eventId, boolean deckConformance) { }
}
