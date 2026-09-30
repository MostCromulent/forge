package forge.web;

import forge.deck.CardPool;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.LimitedPoolType;
import forge.item.PaperCard;
import forge.model.CardBlock;
import forge.model.FModel;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Map;

import static org.testng.Assert.assertEquals;

public class DraftProductsTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    /** Fails if the Full branch still prompts, or builds a product other than three generic packs. */
    @Test
    public void fullDraftsThreeGenericPacks() {
        final BoosterDraft draft = BoosterDraft.full();
        assertEquals(draft.getNumRounds(), 3);
        draft.initializeBoosters();
        final CardPool pack = draft.nextChoice();
        assertEquals(pack.countAll(), 15);
    }

    /** Fails if a combo is parsed differently from desktop's prompted path. */
    @Test
    public void aBlockComboGivesThoseSets() {
        final CardBlock innistrad = FModel.getBlocks().get("Innistrad");
        final BoosterDraft draft = BoosterDraft.block(innistrad, "ISD/ISD/DKA", LimitedPoolType.Block);
        assertEquals(draft.getNumRounds(), 3);
        draft.initializeBoosters();
        for (final Map.Entry<PaperCard, Integer> e : draft.nextChoice()) {
            if (!e.getKey().getRules().getType().isBasicLand()) {
                assertEquals(e.getKey().getEdition(), "ISD", e.getKey() + " is not from the first pack's set");
            }
        }
    }
}
