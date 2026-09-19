package forge.web;

import forge.deck.Deck;
import forge.item.PaperCard;
import forge.model.FModel;

final class TestDecks {
    private TestDecks() {}

    static Deck of(final String name, final Object... cardNameCountPairs) {
        final Deck deck = new Deck(name);
        for (int i = 0; i < cardNameCountPairs.length; i += 2) {
            final PaperCard card = FModel.getMagicDb().getCommonCards().getCard((String) cardNameCountPairs[i]);
            if (card == null) {
                throw new IllegalStateException("No card named " + cardNameCountPairs[i]);
            }
            deck.getMain().add(card, (Integer) cardNameCountPairs[i + 1]);
        }
        return deck;
    }
}
