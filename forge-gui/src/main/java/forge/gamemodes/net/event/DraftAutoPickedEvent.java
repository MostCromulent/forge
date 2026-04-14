package forge.gamemodes.net.event;

import forge.gamemodes.net.server.RemoteClient;
import forge.item.PaperCard;

public final class DraftAutoPickedEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final int seatIndex;
    private final PaperCard card;
    private final int pickNumber;

    public DraftAutoPickedEvent(int seatIndex, PaperCard card, int pickNumber) {
        this.seatIndex = seatIndex;
        this.card = card;
        this.pickNumber = pickNumber;
    }

    public int getSeatIndex() { return seatIndex; }
    public PaperCard getCard() { return card; }
    public int getPickNumber() { return pickNumber; }

    @Override
    public void updateForClient(RemoteClient client) { }
}
