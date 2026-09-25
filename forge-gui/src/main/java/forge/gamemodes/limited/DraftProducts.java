package forge.gamemodes.limited;

import forge.StaticData;
import forge.card.CardEdition;
import forge.model.CardBlock;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.util.storage.IStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a limited event can be built from, read without prompting: the lists desktop's setup dialogs offer, for a
 * frontend that asks for every choice at once.
 */
public final class DraftProducts {
    private DraftProducts() {
    }

    public record Block(String name, int packs, List<String> combos) {
    }

    public record Edition(String code, String name) {
    }

    public record SealedLists(List<Block> blocks, List<Block> fantasyBlocks, List<Edition> prereleases, List<String> templates) {
    }

    /** A draftable block: its pack count, its sets, and desktop's preset combinations, or none when each pack's set is chosen. */
    public record DraftBlock(String name, int packs, List<String> sets, List<String> combos) {
    }

    public record DraftLists(List<DraftBlock> blocks, List<DraftBlock> fantasyBlocks, List<String> cubes, List<String> themes,
            String lastCube) {
    }

    public static DraftLists draft() {
        final List<String> cubes = BoosterDraft.loadCustomDrafts().stream().map(CustomLimited::getName).sorted().toList();
        final List<ThemedChaosDraft> themes = new ArrayList<>();
        FModel.getThemedChaosDrafts().forEach(themes::add);
        Collections.sort(themes);
        return new DraftLists(draftBlocks(FModel.getBlocks()), draftBlocks(FModel.getFantasyBlocks()), cubes,
                themes.stream().map(ThemedChaosDraft::getLabel).toList(),
                FModel.getPreferences().getPref(ForgePreferences.FPref.LAST_IMPORTED_CUBE_ID));
    }

    public static CustomLimited cube(final String name) {
        return BoosterDraft.loadCustomDrafts().stream().filter(c -> c.getName().equals(name)).findFirst().orElse(null);
    }

    public static ThemedChaosDraft theme(final String label) {
        for (final ThemedChaosDraft t : FModel.getThemedChaosDrafts()) {
            if (t.getLabel().equals(label)) {
                return t;
            }
        }
        return null;
    }

    private static List<DraftBlock> draftBlocks(final IStorage<CardBlock> storage) {
        final List<DraftBlock> out = new ArrayList<>();
        for (final CardBlock b : storage) {
            if (BoosterDraft.isDraftableBlock(b) && !BoosterDraft.blockSets(b).isEmpty()) {
                out.add(new DraftBlock(b.getName(), b.getCntBoostersDraft(), BoosterDraft.blockSets(b), BoosterDraft.blockCombos(b)));
            }
        }
        return out;
    }

    public static SealedLists sealed() {
        final List<CardEdition> editions = new ArrayList<>();
        StaticData.instance().getEditions().getPrereleaseEditions().forEach(editions::add);
        Collections.sort(editions);
        Collections.reverse(editions);
        final List<Edition> prereleases = editions.stream().map(e -> new Edition(e.getCode(), e.getName())).toList();
        final List<String> templates = SealedCardPoolGenerator.loadCustomSealed().stream().map(CustomLimited::getName).toList();
        return new SealedLists(sealedBlocks(FModel.getBlocks()), sealedBlocks(FModel.getFantasyBlocks()), prereleases, templates);
    }

    public static CardBlock block(final String name, final boolean fantasy) {
        return (fantasy ? FModel.getFantasyBlocks() : FModel.getBlocks()).get(name);
    }

    public static CustomLimited sealedTemplate(final String name) {
        return SealedCardPoolGenerator.loadCustomSealed().stream().filter(t -> t.getName().equals(name)).findFirst().orElse(null);
    }

    private static List<Block> sealedBlocks(final IStorage<CardBlock> storage) {
        final List<Block> out = new ArrayList<>();
        for (final CardBlock b : storage) {
            try {
                out.add(new Block(b.getName(), b.getCntBoostersSealed(), SealedCardPoolGenerator.blockCombos(b)));
            } catch (final RuntimeException e) {
                // Desktop's dialog fails on a block with no combo for its pack count, so it is not offered
            }
        }
        return out;
    }
}
