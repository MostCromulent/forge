package forge.gamemodes.match;

import forge.game.VoteChoice;
import forge.game.VoteSession;

/** Draw offer: any DECLINE cancels; unanimous ACCEPT draws; otherwise still open. */
public final class DrawVoteResolver implements VoteResolver {
    @Override
    public VoteOutcome resolve(final VoteSession session) {
        if (session.getVotes().isEmpty()) {
            return null;
        }
        boolean allAccepted = true;
        for (final VoteChoice c : session.getVotes().values()) {
            if (c == VoteChoice.DECLINE) {
                return VoteOutcome.DECLINED;
            }
            if (c != VoteChoice.ACCEPT) {
                allAccepted = false;
            }
        }
        return allAccepted ? VoteOutcome.ACCEPTED : null;
    }
}
