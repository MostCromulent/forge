package forge.gui.events;

import forge.game.VoteChoice;
import forge.player.PlayerControllerHuman;

public record UiEventNextGameDecision(PlayerControllerHuman controller, VoteChoice decision) implements UiEvent {

    @Override
    public <T> T visit(IUiEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

}
