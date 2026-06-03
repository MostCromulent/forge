package forge.gamemodes.match;

import forge.game.VoteChoice;
import forge.game.VoteSession;

/** Next game: any QUIT quits; once everyone has voted, continue unless NEW outnumbers CONTINUE. */
public final class NextGameVoteResolver implements VoteResolver {
    @Override
    public VoteOutcome resolve(final VoteSession session) {
        int cont = 0, restart = 0;
        for (final VoteChoice c : session.getVotes().values()) {
            switch (c) {
                case QUIT -> { return VoteOutcome.QUIT; }
                case CONTINUE -> cont++;
                case NEW -> restart++;
                default -> { }
            }
        }
        if (!session.allVoted()) {
            return null;
        }
        return cont >= restart ? VoteOutcome.CONTINUE : VoteOutcome.RESTART;
    }
}
