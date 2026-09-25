package forge.web;

import forge.StaticData;
import forge.card.CardEdition;
import forge.card.ColorSet;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.gamemodes.limited.CustomLimited;
import forge.gamemodes.limited.DraftProducts;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.limited.SealedCardPoolGenerator;
import forge.item.PaperCard;
import forge.model.CardBlock;
import forge.model.FModel;
import forge.util.storage.IStorage;
import forge.web.FromBrowser.SealedCreate;
import forge.web.ToBrowser.LimitedEdition;
import forge.web.ToBrowser.LimitedOptions;
import forge.web.ToBrowser.LimitedPools;
import forge.web.ToBrowser.Opponent;
import forge.web.ToBrowser.PoolRow;
import forge.web.ToBrowser.SealedBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The host's offline sealed pools, kept where desktop keeps them: what the setup form offers, making a pool, and listing them. */
final class OfflineEvents {
    private OfflineEvents() {
    }

    static IStorage<DeckGroup> sealed() {
        return FModel.getDecks().getSealed();
    }

    static LimitedOptions options() {
        final DraftProducts.SealedLists lists = DraftProducts.sealed();
        return new LimitedOptions(blocks(lists.blocks()), blocks(lists.fantasyBlocks()),
                lists.prereleases().stream().map(e -> new LimitedEdition(e.code(), e.name())).toList(), lists.templates());
    }

    static LimitedPools pools() {
        final List<PoolRow> rows = new ArrayList<>();
        synchronized (DeckCatalog.DECKS) {
            for (final DeckGroup group : sealed()) {
                final Deck human = group.getHumanDeck();
                final int size = human == null ? 0 : human.getMain().countAll();
                final List<Opponent> opponents = new ArrayList<>();
                for (int i = 0; i < group.getAiDecks().size(); i++) {
                    opponents.add(new Opponent("Opponent " + (i + 1), colours(group.getAiDecks().get(i))));
                }
                rows.add(new PoolRow(group.getName(), size > 0, size, opponents));
            }
        }
        return new LimitedPools(rows);
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

    /** Stores a pool, replacing one of the same name, as desktop's sealed screen does once the player agrees. */
    static void store(final DeckGroup group) {
        synchronized (DeckCatalog.DECKS) {
            if (sealed().contains(group.getName())) {
                sealed().delete(group.getName());
            }
            sealed().add(group);
        }
    }

    private static SealedCardPoolGenerator generator(final SealedCreate c) {
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
