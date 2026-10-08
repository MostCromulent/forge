package forge.game.event;

import java.io.Serializable;

public interface GameEvent extends Event, Serializable {

    <T> T visit(IGameEventVisitor<T> visitor);

    /**
     * True for an event that reports nothing that happened in the game. It only tells a GUI that
     * something it shows, such as a zone or a card's stats, may be out of date and should be drawn again.
     * Every other event describes a thing that happened: a cast, a move, damage, a new phase.
     */
    default boolean isRedrawHint() {
        return false;
    }
}
