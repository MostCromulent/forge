package forge.game;

/**
 * A vote option shared across all multiplayer vote kinds. {@link #PENDING} marks a voter yet to
 * vote; {@link #OFFER} is the draw-offer initiation signal (never stored as a voter's choice).
 */
public enum VoteChoice {
    PENDING,
    OFFER,
    ACCEPT,
    DECLINE,
    CONTINUE,
    NEW,
    QUIT
}
