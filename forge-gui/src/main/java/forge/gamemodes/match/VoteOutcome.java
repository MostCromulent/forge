package forge.gamemodes.match;

/** Terminal result of a settled {@link forge.game.VoteSession}; the UI maps each value to its wording. */
public enum VoteOutcome {
    ACCEPTED,   // draw offer accepted -> game ends in a draw
    DECLINED,   // draw offer declined -> no state change
    CONTINUE,   // next game -> continue the match
    RESTART,    // next game -> start a new match
    QUIT        // next game -> quit the match
}
