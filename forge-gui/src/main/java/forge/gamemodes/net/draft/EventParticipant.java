package forge.gamemodes.net.draft;

import java.io.Serializable;

public final class EventParticipant implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type { HUMAN, AI }

    private final String name;
    private final Type type;
    private final int seatIndex;

    public EventParticipant(String name, Type type, int seatIndex) {
        this.name = name;
        this.type = type;
        this.seatIndex = seatIndex;
    }

    public String getName() { return name; }
    public Type getType() { return type; }
    public int getSeatIndex() { return seatIndex; }
    public boolean isHuman() { return type == Type.HUMAN; }
    public boolean isAI() { return type == Type.AI; }

    public String getDisplayName() {
        return isAI() ? name + " (AI)" : name;
    }
}
