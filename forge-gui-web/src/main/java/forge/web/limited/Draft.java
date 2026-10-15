package forge.web.limited;

import forge.web.protocol.ToBrowser.DraftState;

/** A draft this browser picks in: its own against the computer (OfflineDraft), or a seat at an online table (OnlineDraft). */
public interface Draft {
    /** The state last sent, for a browser that arrives again; null before the first pack. */
    DraftState latest();

    /** Picks the card at index of the pack shown in state step. A click on a pack that has moved on is ignored. */
    void pick(int step, int index, boolean sideboard);

    /** Moves the pick at index into the sideboard or back into the main deck, as desktop lets a drafter sort picks as they go. */
    void move(int index, boolean sideboard);
}
