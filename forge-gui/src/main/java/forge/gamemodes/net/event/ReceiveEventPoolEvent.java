package forge.gamemodes.net.event;

import forge.deck.DeckGroup;
import forge.gamemodes.net.server.RemoteClient;

public final class ReceiveEventPoolEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final String eventId;
    private final DeckGroup pool;

    public ReceiveEventPoolEvent(String eventId, DeckGroup pool) {
        this.eventId = eventId;
        this.pool = pool;
    }

    public String getEventId() { return eventId; }
    public DeckGroup getPool() { return pool; }

    @Override
    public void updateForClient(RemoteClient client) { }
}
