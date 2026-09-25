package forge.web;

import forge.card.CardEdition;
import forge.deck.CardPool;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.DraftProducts;
import forge.gamemodes.limited.LimitedPoolType;
import forge.item.PaperCard;
import forge.model.CardBlock;
import forge.model.FModel;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

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

    /** Fails if the factory drops the single-set adjustments desktop makes: the set's own pod size. */
    @Test
    public void aSingleSetTakesItsPodSize() {
        for (final CardBlock block : FModel.getBlocks()) {
            final List<String> sets = BoosterDraft.blockSets(block);
            if (!BoosterDraft.isDraftableBlock(block) || sets.size() != 1) {
                continue;
            }
            final CardEdition edition = FModel.getMagicDb().getEditions().get(sets.get(0));
            if (edition != null && edition.getDraftOptions().getRecommendedPodSize() != BoosterDraft.N_PLAYERS) {
                assertEquals(BoosterDraft.block(block, sets.get(0), LimitedPoolType.Block).getPodSize(),
                        edition.getDraftOptions().getRecommendedPodSize());
                return;
            }
        }
        throw new SkipException("No single-set draft block recommends a pod size other than 8");
    }

    /** Fails if the web could offer a block whose picks ask questions, or one with no draft packs. */
    @Test
    public void noConspiracyInTheLists() {
        final DraftProducts.DraftLists lists = DraftProducts.draft();
        assertFalse(lists.blocks().isEmpty());
        for (final DraftProducts.DraftBlock b : lists.blocks()) {
            assertFalse(b.name().contains("Conspiracy"), b.name());
        }
        for (final CardBlock b : FModel.getBlocks()) {
            if (b.getCntBoostersDraft() == 0) {
                assertFalse(BoosterDraft.isDraftableBlock(b), b.getName());
            }
        }
        final DraftProducts.DraftBlock innistrad = lists.blocks().stream().filter(b -> b.name().equals("Innistrad")).findFirst().orElseThrow();
        assertTrue(innistrad.combos().contains("ISD/ISD/DKA"), "desktop's preset combos are missing");
    }
}
