package forge.gamemodes.net.draft;

import forge.gamemodes.limited.LimitedPoolType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class NetworkEvent {
    private final String eventId;
    private final EventFormat format;
    private EventPhase phase;
    private final List<EventParticipant> participants;
    private int pickTimerSeconds;
    private String productDescription;
    private boolean deckConformance;
    private List<String> boosterConfiguration;
    private LimitedPoolType poolType;

    public NetworkEvent(EventFormat format) {
        this.eventId = UUID.randomUUID().toString().substring(0, 8);
        this.format = format;
        this.phase = EventPhase.LOBBY_GATHER;
        this.participants = new ArrayList<>();
        this.pickTimerSeconds = 60;
        this.deckConformance = true;
        this.productDescription = "";
        this.poolType = LimitedPoolType.Full;
    }

    public String getEventId() { return eventId; }
    public EventFormat getFormat() { return format; }
    public EventPhase getPhase() { return phase; }
    public void setPhase(EventPhase phase) { this.phase = phase; }
    public List<EventParticipant> getParticipants() { return participants; }
    public int getPickTimerSeconds() { return pickTimerSeconds; }
    public void setPickTimerSeconds(int seconds) { this.pickTimerSeconds = seconds; }
    public String getProductDescription() { return productDescription; }
    public void setProductDescription(String desc) { this.productDescription = desc; }
    public boolean isDeckConformance() { return deckConformance; }
    public void setDeckConformance(boolean value) { this.deckConformance = value; }
    public List<String> getBoosterConfiguration() { return boosterConfiguration; }
    public void setBoosterConfiguration(List<String> config) { this.boosterConfiguration = config; }
    public LimitedPoolType getPoolType() { return poolType; }
    public void setPoolType(LimitedPoolType poolType) { this.poolType = poolType; }

    public void addParticipant(EventParticipant participant) {
        participants.add(participant);
    }

    public NetworkEventView toView() {
        return new NetworkEventView(eventId, format, phase,
                participants, pickTimerSeconds, productDescription, deckConformance);
    }
}
