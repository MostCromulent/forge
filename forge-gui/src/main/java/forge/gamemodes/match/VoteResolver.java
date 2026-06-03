package forge.gamemodes.match;

import forge.game.VoteSession;

/** Computes the terminal {@link VoteOutcome} of a vote, or {@code null} while it is still open. */
public interface VoteResolver {
    VoteOutcome resolve(VoteSession session);
}
