package forge.gamemodes.limited;

import com.google.common.collect.Lists;
import forge.StaticData;
import forge.card.CardEdition;
import forge.deck.CardPool;
import forge.deck.DeckBase;
import forge.deck.DeckGroup;
import forge.gui.util.SGuiChoose;
import forge.gui.util.SOptionPane;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.skin.FSkinProp;
import forge.model.CardBlock;
import forge.model.FModel;
import forge.util.Localizer;
import forge.util.storage.IStorage;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/** Asks the player what a draft or sealed pool is made of, then sets it up. */
public final class LimitedSetupPrompts {
    private LimitedSetupPrompts() {
    }

    public static BoosterDraft createDraft(final LimitedPoolType draftType) {
        final BoosterDraft draft = createDraft(draftType, false);
        if (draft != null) {
            draft.initializeBoosters();
        }
        return draft;
    }

    /**
     * Create a draft for network play. Product is generated but boosters are NOT
     * initialized — the caller must configure pod size and human seats, then call
     * {@link BoosterDraft#initializeBoosters()} manually.
     *
     * @param draftType the draft pool type
     * @return a partially-initialized draft, or null if product generation fails
     */
    public static BoosterDraft createDraftForNetwork(final LimitedPoolType draftType) {
        return createDraft(draftType, true);
    }

    private static BoosterDraft createDraft(final LimitedPoolType draftType, final boolean forNetwork) {
        final DraftProduct product = chooseDraftProduct(draftType, forNetwork);
        return product == null ? null : new BoosterDraft(product);
    }

    public static WinstonDraft createWinstonDraft(final LimitedPoolType draftType) {
        final DraftProduct product = chooseDraftProduct(draftType, false);
        return product == null ? null : new WinstonDraft(product);
    }

    /** Null when the player backs out of a choice, or there is nothing to choose from. */
    private static DraftProduct chooseDraftProduct(final LimitedPoolType draftType, final boolean forNetwork) {
        switch (draftType) {
            case Full: // Draft from all cards in Forge
                return new DraftProduct.Full();

            case Block: // Draft from cards by block or set
            case FantasyBlock:
                final List<CardBlock> blocks = new ArrayList<>();
                final IStorage<CardBlock> storage = draftType == LimitedPoolType.Block
                        ? FModel.getBlocks()
                        : FModel.getFantasyBlocks();

                // TODO Conspiracy blocks gated for network draft: pack-effect prompts
                // (Agent of Acquisitions, Cogwork Librarian, etc.) pop on the host, the
                // conspiracy player-flag state isn't replicated to clients, and the draft
                // log isn't shipped over the wire. Custom/Chaos/Import paths can still
                // smuggle CNS cards in — fix those when the underlying issues are resolved.
                for (final CardBlock b : storage) {
                    if (b.getCntBoostersDraft() > 0) {
                        if (forNetwork && b.getName().contains("Conspiracy")) continue;
                        blocks.add(b);
                    }
                }

                final CardBlock block = SGuiChoose.oneOrNone(Localizer.getInstance().getMessage("lblChooseBlock"), blocks);
                if (block == null) {
                    return null;
                }

                final List<String> sets = BoosterDraft.blockSets(block);
                if (sets.isEmpty()) {
                    SOptionPane.showErrorDialog(Localizer.getInstance().getMessage("lblBlockNotContainSetCombinations", block.toString()));
                    return null;
                }

                List<String> packs = sets;
                if (sets.size() > 1) {
                    final int nPacks = block.getCntBoostersDraft();
                    if (nPacks == 3 && sets.size() < 4) {
                        final String combo = SGuiChoose.oneOrNone(Localizer.getInstance().getMessage("lblChooseSetCombination"), BoosterDraft.getSetCombos(sets));
                        packs = combo == null ? null : List.of(combo.split("/"));
                    } else {
                        packs = choosePackByPack(sets, nPacks);
                    }

                    if (packs == null) {
                        return null;
                    }
                }
                return new DraftProduct.Block(block, packs);

            case Custom:
                final List<CustomLimited> myDrafts = BoosterDraft.loadCustomDrafts();

                if (myDrafts.isEmpty()) {
                    SOptionPane.showMessageDialog(Localizer.getInstance().getMessage("lblNotFoundCustomDraftFiles"));
                    return null;
                }
                myDrafts.sort(Comparator.comparing(DeckBase::getName));

                final CustomLimited customDraft = SGuiChoose.oneOrNone(Localizer.getInstance().getMessage("lblChooseCustomDraft"), myDrafts);
                return customDraft == null ? null : new DraftProduct.Cube(customDraft);

            case Chaos:
                // Get chaos draft themes
                final List<ThemedChaosDraft> themes = new ArrayList<>();
                final IStorage<ThemedChaosDraft> themeStorage = FModel.getThemedChaosDrafts();
                for (final ThemedChaosDraft theme : themeStorage) {
                    themes.add(theme);
                }
                Collections.sort(themes); // sort for user interface
                // Ask user to select theme
                final String dialogQuestion = Localizer.getInstance().getMessage("lblChooseChaosTheme");
                final ThemedChaosDraft theme = SGuiChoose.oneOrNone(dialogQuestion, themes);
                if (theme == null || !BoosterDraft.chaosEditions(theme).iterator().hasNext()) {
                    return null;
                }
                return new DraftProduct.Chaos(theme);

            case Import:
                /*
                 * Import a cube from CubeCobra.
                 * Default settings are 3 boosters with a size of 15 cards.
                 */

                String lastCubeId = FModel.getPreferences().getPref(FPref.LAST_IMPORTED_CUBE_ID);
                String inputCubeId = SOptionPane.showInputDialog(
                        Localizer.getInstance().getMessage("lblEnterCubeCobraURL") + ":",
                        Localizer.getInstance().getMessage("lblImportCube"),
                        null,
                        lastCubeId);

                final CustomLimited importedDraft = inputCubeId == null ? null : importCube(inputCubeId);
                return importedDraft == null ? null : new DraftProduct.Cube(importedDraft);

            default:
                throw new NoSuchElementException("Draft for mode " + draftType + " has not been set up!");
        }
    }

    /** Tells the player why and returns null when the cube can't be imported. */
    private static CustomLimited importCube(final String cubeId) {
        try {
            CubeImporter importer = new CubeImporter(cubeId);
            CustomLimited importedDraft = importer.importCube();
            if (importedDraft == null) {
                SOptionPane.showErrorDialog(Localizer.getInstance().getMessage("lblFailedToImportCube") + ": " + cubeId);
            }
            return importedDraft;
        } catch (Exception e) {
            SOptionPane.showErrorDialog(Localizer.getInstance().getMessage("lblErrorImportingCube") + ": " + e.getMessage());
            return null;
        }
    }

    private static List<String> choosePackByPack(final List<String> setz, int packs) {
        final List<String> chosen = new ArrayList<>();

        for (int i = 1; i <= packs; i++) {
            String choice = SGuiChoose.oneOrNone(Localizer.getInstance().getMessage("lblChooseSetForNPack", String.valueOf(i), String.valueOf(packs)), setz);
            if (choice == null) {
                return null;
            }
            chosen.add(choice);
        }
        return chosen;
    }

    public static DeckGroup generateSealedDeck(final boolean addBasicLands) {
        final String prompt = Localizer.getInstance().getMessage("lblChooseSealedDeckFormat");
        final LimitedPoolType poolType = SGuiChoose.oneOrNone(prompt, LimitedPoolType.values());
        if (poolType == null) { return null; }

        SealedCardPoolGenerator sd = createSealedGenerator(poolType);
        if (sd == null || sd.isEmpty()) { return null; }

        final CardPool humanPool = sd.getCardPool(true);
        if (humanPool == null) { return null; }

        final String sDeckName = SOptionPane.showInputDialog(
                Localizer.getInstance().getMessage("lblSaveCardPoolAs") + ":",
                Localizer.getInstance().getMessage("lblSaveCardPool"),
                FSkinProp.ICO_QUESTION);

        if (StringUtils.isBlank(sDeckName)) {
            return null;
        }

        final IStorage<DeckGroup> sealedDecks = FModel.getDecks().getSealed();
        if (sealedDecks.contains(sDeckName)) {
            if (!SOptionPane.showConfirmDialog(
                    Localizer.getInstance().getMessage("lblDeckExistsReplaceConfirm", sDeckName),
                    Localizer.getInstance().getMessage("lblSealedDeckGameExists"))) {
                return null;
            }
            sealedDecks.delete(sDeckName);
        }

        final DeckGroup sealed = sd.buildDeckGroup(sDeckName, humanPool, addBasicLands);
        if (sealed == null) { return null; }

        FModel.getDecks().getSealed().add(sealed);
        return sealed;
    }

    /** Null when the player backs out of a choice, or there is nothing to choose from. */
    public static SealedCardPoolGenerator createSealedGenerator(final LimitedPoolType poolType) {
        final SealedPoolProduct product = chooseSealedPoolProduct(poolType);
        return product == null ? null : new SealedCardPoolGenerator(product);
    }

    private static SealedPoolProduct chooseSealedPoolProduct(final LimitedPoolType poolType) {
        switch(poolType) {
            case Full:
                final Integer packs = chooseNumberOfBoosters();
                return packs == null ? null : new SealedPoolProduct.Full(packs);

            case Prerelease:
                ArrayList<CardEdition> editions = Lists.newArrayList(StaticData.instance().getEditions().getPrereleaseEditions());
                Collections.sort(editions);
                Collections.reverse(editions);

                CardEdition chosenEdition = SGuiChoose.oneOrNone(Localizer.getInstance().getMessage("lblChooseAnEdition"), editions);
                return chosenEdition == null ? null : new SealedPoolProduct.Prerelease(chosenEdition);

            case Block:
            case FantasyBlock:
                List<CardBlock> blocks = new ArrayList<>();
                Iterable<CardBlock> src = poolType == LimitedPoolType.Block ? FModel.getBlocks() : FModel.getFantasyBlocks();
                for (CardBlock b : src) {
                    blocks.add(b);
                }

                final CardBlock block = SGuiChoose.oneOrNone(Localizer.getInstance().getMessage("lblChooseBlock"), blocks);
                if (block == null) { return null; }

                final List<String> setCombos = SealedCardPoolGenerator.blockCombos(block);
                final String p = setCombos.size() > 1 ? SGuiChoose.oneOrNone(Localizer.getInstance().getMessage("lblChoosePackNumberToPlay"), setCombos) : setCombos.get(0);
                return p == null ? null : new SealedPoolProduct.Block(block, p);

            case Custom:
                final List<CustomLimited> customs = SealedCardPoolGenerator.loadCustomSealed();

                // present list to user
                if (customs.isEmpty()) {
                    SOptionPane.showMessageDialog(Localizer.getInstance().getMessage("lblNotFoundCustomSealedFiles"));
                    return null;
                }

                final CustomLimited draft = SGuiChoose.oneOrNone(Localizer.getInstance().getMessage("lblChooseCustomSealedPool"), customs);
                if (draft == null) { return null; }

                final Integer customPacks = chooseNumberOfBoosters();
                return customPacks == null ? null : new SealedPoolProduct.Cube(draft, customPacks);

            case Import:
                /*
                  Import a cube from CubeCobra.
                  Default settings include a variable number of boosters with a size of 15 cards.
                 */
                String inputCubeId = SOptionPane.showInputDialog(
                        Localizer.getInstance().getMessage("lblEnterCubeCobraURL") + ":",
                        Localizer.getInstance().getMessage("lblImportCube"),
                        null);

                final CustomLimited importedDraft = inputCubeId == null ? null : importCube(inputCubeId);
                if (importedDraft == null) { return null; }

                final Integer importPacks = chooseNumberOfBoosters();
                return importPacks == null ? null : new SealedPoolProduct.Cube(importedDraft, importPacks);

            default:
                return null;
        }
    }

    private static Integer chooseNumberOfBoosters() {
        return SGuiChoose.getInteger(Localizer.getInstance().getMessage("lblHowManyBoosterPacks"), 3, 12);
    }
}
