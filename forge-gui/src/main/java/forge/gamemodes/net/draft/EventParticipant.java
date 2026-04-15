package forge.gamemodes.net.draft;

import java.io.Serializable;

public final class EventParticipant implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type { HUMAN, AI }

    private final String name;
    private final Type type;
    private int seatIndex;
    private final int lobbySlotIndex;

    public EventParticipant(String name, Type type, int seatIndex, int lobbySlotIndex) {
        this.name = name;
        this.type = type;
        this.seatIndex = seatIndex;
        this.lobbySlotIndex = lobbySlotIndex;
    }

    public String getName() { return name; }
    public Type getType() { return type; }
    public int getSeatIndex() { return seatIndex; }
    public void setSeatIndex(int seatIndex) { this.seatIndex = seatIndex; }
    public int getLobbySlotIndex() { return lobbySlotIndex; }
    public boolean isHuman() { return type == Type.HUMAN; }
    public boolean isAI() { return type == Type.AI; }

    public String getDisplayName() {
        return isAI() ? name + " (AI)" : name;
    }
}
