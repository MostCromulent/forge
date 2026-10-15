package forge.web.limited;

import forge.StaticData;
import forge.card.CardEdition;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.CubeImporter;
import forge.gamemodes.limited.CustomLimited;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.limited.SealedCardPoolGenerator;
import forge.gamemodes.limited.ThemedChaosDraft;
import forge.model.CardBlock;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.util.Localizer;
import forge.util.storage.IStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What a limited event can be built from, read without prompting, for a frontend that asks for every choice at once. */
final class DraftProducts {
    private DraftProducts() {
    }

    /** A block offered for drafting: it has draft packs, and it is not Conspiracy, whose draft-time prompts only a local GUI can answer. */
    static boolean isDraftableBlock(final CardBlock block) {
        return block.getCntBoostersDraft() > 0 && !block.getName().contains("Conspiracy");
    }

    /** Desktop's preset combinations for a block, or an empty list when it asks for one set per pack instead. */
    static List<String> draftCombos(final CardBlock block) {
        final List<String> sets = BoosterDraft.blockSets(block);
        return block.getCntBoostersDraft() == 3 && sets.size() > 1 && sets.size() < 4 ? BoosterDraft.getSetCombos(sets) : new ArrayList<>();
    }

    static BoosterDraft fullDraft() {
        return BoosterDraft.createFullDraft();
    }

    /** combo is one set code per pack, joined with "/", or the block's only set's code. */
    static BoosterDraft blockDraft(final CardBlock block, final String combo, final LimitedPoolType type) {
        return BoosterDraft.createBlockDraft(type, block, combo);
    }

    static BoosterDraft cubeDraft(final CustomLimited cube) {
        return BoosterDraft.createCubeDraft(LimitedPoolType.Custom, cube);
    }

    /** Throws IllegalArgumentException when no set fits the theme. */
    static BoosterDraft chaosDraft(final ThemedChaosDraft theme) {
        final BoosterDraft draft = BoosterDraft.createChaosDraft(theme);
        if (draft == null) {
            throw new IllegalArgumentException("No set fits the theme " + theme.getLabel() + ".");
        }
        return draft;
    }

    /** Throws IllegalArgumentException, with a message a player can read, when the cube can't be imported. */
    static BoosterDraft cubeCobraDraft(final String cubeId) {
        return BoosterDraft.createCubeDraft(LimitedPoolType.Import, importCube(cubeId));
    }

    private static CustomLimited importCube(final String cubeId) {
        final CustomLimited imported;
        try {
            imported = new CubeImporter(cubeId).importCube();
        } catch (Exception e) {
            throw new IllegalArgumentException(Localizer.getInstance().getMessage("lblErrorImportingCube") + ": " + e.getMessage(), e);
        }
        if (imported == null) {
            throw new IllegalArgumentException(Localizer.getInstance().getMessage("lblFailedToImportCube") + ": " + cubeId);
        }
        return imported;
    }

    static SealedCardPoolGenerator fullSealed(final int packs) {
        return SealedCardPoolGenerator.createFull(packs);
    }

    static SealedCardPoolGenerator prereleaseSealed(final CardEdition edition) {
        return SealedCardPoolGenerator.createPrerelease(edition);
    }

    /** combo is one entry of SealedCardPoolGenerator.blockCombos. */
    static SealedCardPoolGenerator blockSealed(final CardBlock block, final String combo) {
        return SealedCardPoolGenerator.createBlock(block, combo);
    }

    static SealedCardPoolGenerator customSealed(final CustomLimited template, final int packs) {
        return SealedCardPoolGenerator.createCustom(template, packs);
    }

    /** Throws IllegalArgumentException, with a message a player can read, when the cube can't be imported. */
    static SealedCardPoolGenerator cubeCobraSealed(final String cubeId, final int packs) {
        return SealedCardPoolGenerator.createImported(importCube(cubeId), packs);
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
            if (isDraftableBlock(b) && !BoosterDraft.blockSets(b).isEmpty()) {
                out.add(new DraftBlock(b.getName(), b.getCntBoostersDraft(), BoosterDraft.blockSets(b), draftCombos(b)));
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
