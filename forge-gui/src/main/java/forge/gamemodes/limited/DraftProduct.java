package forge.gamemodes.limited;

import forge.model.CardBlock;

import java.util.List;

/** What a draft's packs are made from. */
public sealed interface DraftProduct {
    record Full() implements DraftProduct {
    }

    /** packs is one set code per pack, from {@link BoosterDraft#blockSets}. A block with one set has nothing to choose, and packs is not read. */
    record Block(CardBlock block, List<String> packs) implements DraftProduct {
    }

    record Cube(CustomLimited cube) implements DraftProduct {
    }

    record Chaos(ThemedChaosDraft theme) implements DraftProduct {
    }
}
