package forge.web.limited;

import forge.deck.DeckGroup;

/** What a draft against the computer is played for when it is not the Limited pages', as a quest tournament's is. */
public interface DraftOwner {
    /** The one name the drafted decks are kept under. */
    String deckName();

    /** The drafted decks were saved under deckName. */
    void saved(DeckGroup decks);

    /** The draft was left without being saved. */
    void discarded();
}
