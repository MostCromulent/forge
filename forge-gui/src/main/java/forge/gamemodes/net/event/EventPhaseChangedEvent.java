package forge.gamemodes.net.event;

import forge.gamemodes.net.EventPhase;
import forge.gamemodes.net.server.RemoteClient;

public final class EventPhaseChangedEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final EventPhase phase;

    public EventPhaseChangedEvent(EventPhase phase) {
        this.phase = phase;
    }

    public EventPhase getPhase() { return phase; }

    @Override
    public void updateForClient(RemoteClient client) { }
}
