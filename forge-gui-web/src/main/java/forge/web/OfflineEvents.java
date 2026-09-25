package forge.web;

import forge.StaticData;
import forge.card.CardEdition;
import forge.card.ColorSet;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.CustomLimited;
import forge.gamemodes.limited.DraftProducts;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.limited.SealedCardPoolGenerator;
import forge.gamemodes.limited.ThemedChaosDraft;
import forge.item.PaperCard;
import forge.model.CardBlock;
import forge.model.FModel;
import forge.util.storage.IStorage;
import forge.web.FromBrowser.DraftStart;
import forge.web.FromBrowser.SealedCreate;
import forge.web.ToBrowser.DraftBlockOption;
import forge.web.ToBrowser.LimitedEdition;
import forge.web.ToBrowser.LimitedOptions;
import forge.web.ToBrowser.LimitedPools;
import forge.web.ToBrowser.Opponent;
import forge.web.ToBrowser.PoolRow;
import forge.web.ToBrowser.SealedBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** The host's offline sealed pools, kept where desktop keeps them: what the setup form offers, making a pool, and listing them. */
final class OfflineEvents {
    private OfflineEvents() {
    }

    static IStorage<DeckGroup> sealed() {
        return FModel.getDecks().getSealed();
    }

    /** Where pools of a kind are kept: "draft" in desktop's drafts, anything else in its sealed pools. */
    static IStorage<DeckGroup> storage(final String kind) {
        return "draft".equals(kind) ? FModel.getDecks().getDraft() : sealed();
    }

    static LimitedOptions options() {
        final DraftProducts.SealedLists lists = DraftProducts.sealed();
        final DraftProducts.DraftLists draft = DraftProducts.draft();
        return new LimitedOptions(blocks(lists.blocks()), blocks(lists.fantasyBlocks()),
                lists.prereleases().stream().map(e -> new LimitedEdition(e.code(), e.name())).toList(), lists.templates(),
                draftBlocks(draft.blocks()), draftBlocks(draft.fantasyBlocks()), draft.cubes(), draft.themes(), draft.lastCube());
    }

    static LimitedPools pools() {
        return new LimitedPools(rows(sealed()), rows(storage("draft")));
    }

    private static List<PoolRow> rows(final IStorage<DeckGroup> storage) {
        final List<PoolRow> rows = new ArrayList<>();
        synchronized (DeckCatalog.DECKS) {
            for (final DeckGroup group : storage) {
                final Deck human = group.getHumanDeck();
                final int size = human == null ? 0 : human.getMain().countAll();
                final List<Opponent> opponents = new ArrayList<>();
                for (int i = 0; i < group.getAiDecks().size(); i++) {
                    opponents.add(new Opponent("Opponent " + (i + 1), colours(group.getAiDecks().get(i))));
                }
                rows.add(new PoolRow(group.getName(), size > 0, size, opponents));
            }
        }
        return rows;
    }

    /**
     * Builds the pool the form describes, named name, with its opponents' decks. The player's pool is drawn as desktop
     * draws it, so a block whose booster the player chooses asks through the host's browser; call it off the socket
     * thread. Null when that question was cancelled. Throws IllegalArgumentException with a reason a player can read.
     */
    static DeckGroup create(final SealedCreate c, final String name) {
        final SealedCardPoolGenerator gen = generator(c);
        if (gen.isEmpty()) {
            throw new IllegalArgumentException("That product has no packs to open.");
        }
        final CardPool pool = gen.getCardPool(true);
        return pool == null ? null : gen.buildGroup(name, pool);
    }

    /**
     * What builds the draft the form describes. Building runs later, on the draft's own thread, since importing a cube
     * waits on a web site; what can be checked now is checked now. Throws IllegalArgumentException with a reason.
     */
    static Supplier<BoosterDraft> draft(final DraftStart d) {
        final LimitedPoolType type;
        try {
            type = LimitedPoolType.valueOf(d.product());
        } catch (final IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("There is no product called " + d.product() + ".");
        }
        return switch (type) {
            case Full -> BoosterDraft::full;
            case Block, FantasyBlock -> {
                final CardBlock block = d.block() == null ? null : DraftProducts.block(d.block(), type == LimitedPoolType.FantasyBlock);
                if (block == null || !BoosterDraft.isDraftableBlock(block)) {
                    throw new IllegalArgumentException("That block can't be drafted.");
                }
                final List<String> sets = BoosterDraft.blockSets(block);
                final String combo = sets.size() == 1 ? sets.get(0) : d.combo();
                if (combo == null || (sets.size() > 1 && !validCombo(combo, sets, block.getCntBoostersDraft()))) {
                    throw new IllegalArgumentException("Choose a set for each pack.");
                }
                yield () -> BoosterDraft.block(block, combo, type);
            }
            case Custom -> {
                final CustomLimited cube = d.cube() == null ? null : DraftProducts.cube(d.cube());
                if (cube == null) {
                    throw new IllegalArgumentException("There is no cube called " + d.cube() + ".");
                }
                yield () -> BoosterDraft.cube(cube);
            }
            case Chaos -> {
                final ThemedChaosDraft theme = d.theme() == null ? null : DraftProducts.theme(d.theme());
                if (theme == null) {
                    throw new IllegalArgumentException("There is no chaos theme called " + d.theme() + ".");
                }
                yield () -> BoosterDraft.chaos(theme);
            }
            case Import -> {
                if (d.cubeId() == null || d.cubeId().isBlank()) {
                    throw new IllegalArgumentException("Enter a CubeCobra link or ID.");
                }
                final String id = d.cubeId().trim();
                yield () -> BoosterDraft.cubeCobra(id);
            }
            case Prerelease -> throw new IllegalArgumentException("A prerelease is opened, not drafted.");
        };
    }

    private static boolean validCombo(final String combo, final List<String> sets, final int packs) {
        final String[] parts = combo.split("/");
        return parts.length == packs && sets.containsAll(List.of(parts));
    }

    /** Stores a pool, replacing one of the same name, as desktop's limited screens do once the player agrees. */
    static void store(final IStorage<DeckGroup> storage, final DeckGroup group) {
        synchronized (DeckCatalog.DECKS) {
            if (storage.contains(group.getName())) {
                storage.delete(group.getName());
            }
            storage.add(group);
        }
    }

    static SealedCardPoolGenerator generator(final SealedCreate c) {
        final LimitedPoolType type;
        try {
            type = LimitedPoolType.valueOf(c.product());
        } catch (final IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("There is no product called " + c.product() + ".");
        }
        return switch (type) {
            case Full -> SealedCardPoolGenerator.full(packs(c.packs()));
            case Prerelease -> {
                final CardEdition edition = c.edition() == null ? null : StaticData.instance().getEditions().get(c.edition());
                if (edition == null || edition.getPrerelease() == null) {
                    throw new IllegalArgumentException("There is no prerelease for " + c.edition() + ".");
                }
                yield SealedCardPoolGenerator.prerelease(edition);
            }
            case Block, FantasyBlock -> {
                final CardBlock block = c.block() == null ? null : DraftProducts.block(c.block(), type == LimitedPoolType.FantasyBlock);
                if (block == null || c.combo() == null || !SealedCardPoolGenerator.blockCombos(block).contains(c.combo())) {
                    throw new IllegalArgumentException("That block and set combination can't be opened.");
                }
                yield SealedCardPoolGenerator.block(block, c.combo());
            }
            case Custom -> {
                final CustomLimited template = c.template() == null ? null : DraftProducts.sealedTemplate(c.template());
                if (template == null) {
                    throw new IllegalArgumentException("There is no sealed pool called " + c.template() + ".");
                }
                yield SealedCardPoolGenerator.custom(template, packs(c.packs()));
            }
            case Import -> {
                if (c.cubeId() == null || c.cubeId().isBlank()) {
                    throw new IllegalArgumentException("Enter a CubeCobra link or ID.");
                }
                yield SealedCardPoolGenerator.cubeCobra(c.cubeId().trim(), packs(c.packs()));
            }
            case Chaos -> throw new IllegalArgumentException("Chaos has no sealed product.");
        };
    }

    /** Desktop asks for between 3 and 12 packs. */
    private static int packs(final int wanted) {
        if (wanted < 3 || wanted > 12) {
            throw new IllegalArgumentException("Choose between 3 and 12 packs.");
        }
        return wanted;
    }

    private static List<DraftBlockOption> draftBlocks(final List<DraftProducts.DraftBlock> blocks) {
        return blocks.stream().map(b -> new DraftBlockOption(b.name(), b.packs(), b.sets(), b.combos(), podSize(b.sets()))).toList();
    }

    /** As BoosterDraft.block sets it: a single set's recommended pod, and a full pod for anything else. */
    private static int podSize(final List<String> sets) {
        final CardEdition edition = sets.size() == 1 ? StaticData.instance().getEditions().get(sets.get(0)) : null;
        return edition == null ? BoosterDraft.N_PLAYERS : edition.getDraftOptions().getRecommendedPodSize();
    }

    private static List<SealedBlock> blocks(final List<DraftProducts.Block> blocks) {
        return blocks.stream().map(b -> new SealedBlock(b.name(), b.packs(), b.combos())).toList();
    }

    private static String colours(final Deck deck) {
        byte mask = 0;
        for (final Map.Entry<PaperCard, Integer> e : deck.getMain()) {
            mask |= e.getKey().getRules().getColor().getColor();
        }
        return CardCatalog.letters(ColorSet.fromMask(mask));
    }
}
