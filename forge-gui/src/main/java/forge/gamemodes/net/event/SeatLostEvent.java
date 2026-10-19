package forge.gamemodes.net.event;

import forge.gamemodes.net.server.RemoteClient;

/**
 * Server tells a client that it will not be seated: the 300s disconnect
 * window expired, the match was never one this user was in, or the host
 * kicked the player.
 * Replaces the prior "infer seat-lost from any LobbyUpdateEvent during
 * RECONNECTING" heuristic, which fired spuriously on the lobby update that
 * normally accompanies a successful resume.
 */
public final class SeatLostEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    public enum Reason { MATCH_GONE, KICKED }

    private final Reason reason;

    public SeatLostEvent(final Reason reason) {
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    @Override
    public void updateForClient(final RemoteClient client) {
    }

    @Override
    public String toString() {
        return "SeatLost " + reason;
    }
}
