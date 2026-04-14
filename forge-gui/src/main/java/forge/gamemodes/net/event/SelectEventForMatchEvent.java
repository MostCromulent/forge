package forge.gamemodes.net.event;

import forge.gamemodes.net.server.RemoteClient;

public final class SelectEventForMatchEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final String eventId; // null to clear selection
    private final boolean deckConformance;

    public SelectEventForMatchEvent(String eventId, boolean deckConformance) {
        this.eventId = eventId;
        this.deckConformance = deckConformance;
    }

    public String getEventId() { return eventId; }
    public boolean isDeckConformance() { return deckConformance; }

    @Override
    public void updateForClient(RemoteClient client) { }
}
