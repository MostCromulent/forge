package forge.gui.interfaces;

import forge.deck.Deck;
import forge.gamemodes.net.NetworkEventView;
import forge.interfaces.IPlayerChangeListener;
import forge.interfaces.IUpdateable;
import forge.item.PaperCard;

import java.util.List;

public interface ILobbyView extends IUpdateable {
    void setPlayerChangeListener(IPlayerChangeListener iPlayerChangeListener);

    /** Called when a network event (draft/sealed) is created. */
    default void onEventCreated(NetworkEventView view) { }
    /** Called when a draft pack arrives for this player. */
    default void onDraftPackArrived(int seatIndex, List<PaperCard> pack,
            int packNumber, int pickNumber, int timerDurationSeconds) { }
    /** Called when any seat in the pod picks a card. */
    default void onDraftSeatPicked(int seatIndex, int pickNumber, int[] seatQueueDepths) { }
    /** Called when the server auto-picks a card for this player (timer expiry). */
    default void onDraftAutoPicked(int seatIndex, PaperCard card, int pickNumber) { }
    /** Called when the draft is complete and the player receives their pool. */
    default void onReceiveEventPool(String eventId, Deck pool) { }
    /** Called when the host selects an event for match play. */
    default void onSelectEventForMatch(String eventId, boolean deckConformance) { }
}
