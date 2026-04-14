package forge.gamemodes.net.event;

import forge.deck.Deck;
import forge.gamemodes.net.server.RemoteClient;

public final class ReceiveEventPoolEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final String eventId;
    private final Deck pool;

    public ReceiveEventPoolEvent(String eventId, Deck pool) {
        this.eventId = eventId;
        this.pool = pool;
    }

    public String getEventId() { return eventId; }
    public Deck getPool() { return pool; }

    @Override
    public void updateForClient(RemoteClient client) { }
}
