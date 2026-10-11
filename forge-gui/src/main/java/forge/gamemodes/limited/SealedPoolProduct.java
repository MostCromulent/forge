package forge.gamemodes.limited;

import forge.card.CardEdition;
import forge.model.CardBlock;

/** What a sealed pool's packs are made from. */
public sealed interface SealedPoolProduct {
    record Full(int packs) implements SealedPoolProduct {
    }

    record Prerelease(CardEdition edition) implements SealedPoolProduct {
    }

    /** packs is one entry of {@link SealedCardPoolGenerator#blockCombos}. */
    record Block(CardBlock block, String packs) implements SealedPoolProduct {
    }

    record Cube(CustomLimited cube, int packs) implements SealedPoolProduct {
    }
}
