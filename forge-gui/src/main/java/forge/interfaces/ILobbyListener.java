package forge.interfaces;

import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.EventPhase;
import forge.gamemodes.net.NetworkEventView;

public interface ILobbyListener {
    void message(String source, String message);
    void update(GameLobbyData state, int slot);
    void close();
    ClientGameLobby getLobby();

    default void eventCreated(NetworkEventView view) { }
    default void eventPhaseChanged(EventPhase phase) { }
    default void draftPackArrived(int seatIndex, java.util.List<forge.item.PaperCard> pack,
            int packNumber, int pickNumber, int timerDurationSeconds) { }
    default void draftSeatPicked(int seatIndex, int pickNumber, int[] seatQueueDepths) { }
    default void draftAutoPicked(int seatIndex, forge.item.PaperCard card, int pickNumber) { }
    default void receiveEventPool(String eventId, forge.deck.Deck pool) { }
    default void selectEventForMatch(String eventId, boolean deckConformance) { }
}
