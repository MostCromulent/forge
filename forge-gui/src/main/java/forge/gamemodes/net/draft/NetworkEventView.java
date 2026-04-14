package forge.gamemodes.net.draft;

import java.io.Serializable;
import java.util.List;

public final class NetworkEventView implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String eventId;
    private final EventFormat format;
    private final EventPhase phase;
    private final List<EventParticipant> participants;
    private final int pickTimerSeconds;
    private final String productDescription;
    private final boolean deckConformance;

    public NetworkEventView(String eventId, EventFormat format, EventPhase phase,
                            List<EventParticipant> participants, int pickTimerSeconds,
                            String productDescription, boolean deckConformance) {
        this.eventId = eventId;
        this.format = format;
        this.phase = phase;
        this.participants = List.copyOf(participants);
        this.pickTimerSeconds = pickTimerSeconds;
        this.productDescription = productDescription;
        this.deckConformance = deckConformance;
    }

    public String getEventId() { return eventId; }
    public EventFormat getFormat() { return format; }
    public EventPhase getPhase() { return phase; }
    public List<EventParticipant> getParticipants() { return participants; }
    public int getPickTimerSeconds() { return pickTimerSeconds; }
    public String getProductDescription() { return productDescription; }
    public boolean isDeckConformance() { return deckConformance; }
}
