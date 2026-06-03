package forge.gamemodes.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import forge.game.VoteChoice;
import forge.game.VoteKind;
import forge.game.player.PlayerView;
import forge.util.Localizer;

/**
 * Server -> client snapshot of an in-flight vote: its {@link VoteKind}, an optional initiator,
 * every voter's {@link VoteChoice}, and the {@link VoteOutcome} once settled (null while voting).
 */
public record VoteTally(VoteKind kind, PlayerView initiator, List<Entry> entries, VoteOutcome outcome)
        implements Serializable {
    public record Entry(PlayerView player, VoteChoice choice) implements Serializable {}

    public boolean isPending(final PlayerView player) {
        return player != null && entries.stream()
                .anyMatch(e -> player.equals(e.player()) && e.choice() == VoteChoice.PENDING);
    }

    /** Localized "voter: choice" lines for rendering the tally on any surface. */
    public List<String> tallyLines() {
        final Localizer localizer = Localizer.getInstance();
        final List<String> lines = new ArrayList<>();
        for (final Entry e : entries) {
            lines.add(e.player().getName() + ": " + choiceLabel(e.choice(), localizer));
        }
        return lines;
    }

    private static String choiceLabel(final VoteChoice choice, final Localizer localizer) {
        return switch (choice) {
            case ACCEPT -> localizer.getMessage("lblAccept");
            case DECLINE -> localizer.getMessage("lblDecline");
            case CONTINUE -> localizer.getMessage("btnNextGame");
            case NEW -> localizer.getMessage("btnStartNewMatch");
            case QUIT -> localizer.getMessage("btnQuitMatch");
            default -> localizer.getMessage("lblDrawPending");
        };
    }
}
