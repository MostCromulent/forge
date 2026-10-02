package forge.web;

import forge.card.ColorSet;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.game.GameFormat;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.Localizer;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** What a deck is checked against: a format, and for Constructed an optional card pool. Unrestricted keeps only the copy limit of four. */
record Check(GameType format, GameFormat pool, boolean unrestricted) {
    static Check of(final GameType format, final GameFormat pool) {
        return new Check(format, pool, false);
    }

    static Check none() {
        return new Check(GameType.Constructed, null, true);
    }

    DeckFormat deckFormat() {
        return unrestricted ? DeckFormat.Constructed : format.getDeckFormat();
    }

    String label() {
        if (unrestricted) {
            return Localizer.getInstance().getMessage("lblWebEditorNoRestriction");
        }
        return pool == null ? format.toString() : format + " · " + pool.getName();
    }
}

/** Reports the engine's deck rules, adding the commander ban lists and every problem after the first, which DeckFormat leaves out. */
final class Legality {
    private Legality() {
    }

    /** The deck's own problems and each card's by name, with kinds holding how a card's problem is summed up in a verdict that names several. */
    record Result(Map<String, String> flags, Map<String, String> kinds, List<String> deckItems) {
        int problemCount() {
            return deckItems.size() + flags.size();
        }

        /** One sentence for the deck, or null when there is nothing wrong. */
        String verdict() {
            if (problemCount() == 0) {
                return null;
            }
            if (problemCount() == 1) {
                if (!deckItems.isEmpty()) {
                    return Localizer.getInstance().getMessage("lblWebLegalitySentence", StringUtils.capitalize(deckItems.get(0)));
                }
                final Map.Entry<String, String> only = flags.entrySet().iterator().next();
                return Localizer.getInstance().getMessage("lblWebLegalitySentence", describe(only.getKey(), only.getValue(), kinds.get(only.getKey())));
            }
            final List<String> parts = new ArrayList<>(deckItems.subList(0, Math.min(2, deckItems.size())));
            final Map<String, Integer> counted = new LinkedHashMap<>();
            for (final String kind : kinds.values()) {
                counted.merge(kind, 1, Integer::sum);
            }
            counted.forEach((kind, n) -> parts.add(Localizer.getInstance().getMessage(n == 1 ? "lblWebLegalityOneCardKind" : "lblWebLegalityCardsKind", n, kind)));
            return Localizer.getInstance().getMessage("lblWebLegalityProblems", problemCount(), joined(parts));
        }
    }

    static Result check(final Deck deck, final Check check) {
        final DeckFormat df = check.deckFormat();
        final List<PaperCard> commanders = commanders(deck, check);
        final CardPool all = deck.getAllCardsInASinglePool(df.hasCommander(), false);
        final Map<String, PaperCard> byName = new LinkedHashMap<>();
        for (final Map.Entry<PaperCard, Integer> e : all) {
            byName.putIfAbsent(e.getKey().getName(), e.getKey());
        }
        final Function<PaperCard, String> problems = cardProblems(check, commanders);
        final Map<String, String> flags = new LinkedHashMap<>();
        final Map<String, String> kinds = new LinkedHashMap<>();
        for (final Map.Entry<String, PaperCard> e : byName.entrySet()) {
            final String[] problem = problemInDeck(e.getValue(), all.countByName(e.getKey()), check, problems);
            if (problem != null) {
                flags.put(e.getKey(), problem[0]);
                kinds.put(e.getKey(), problem[1]);
            }
        }
        return new Result(flags, kinds, check.unrestricted() ? List.of() : deckItems(deck, df, commanders, flags.isEmpty()));
    }

    /** Why a card can't go in this deck, or null: a ban, the format, the card pool or the commander's colours. Counts are not considered. */
    static Function<PaperCard, String> cardProblems(final Check check, final List<PaperCard> commanders) {
        if (check.unrestricted()) {
            return card -> null;
        }
        final DeckFormat df = check.deckFormat();
        final GameFormat banList = check.pool() != null ? check.pool() : commanderFormat(check.format());
        final Set<String> banned = banList == null ? Set.of() : new HashSet<>(banList.getBannedCardNames());
        final Predicate<PaperCard> inIdentity = commanders.isEmpty() ? card -> true : df.isLegalCardForCommanderPredicate(commanders);
        final String letters = identityLetters(commanders);
        final String outside = letters.isEmpty() ? Localizer.getInstance().getMessage("lblWebLegalityOutsideColourless")
                : Localizer.getInstance().getMessage("lblWebLegalityOutside", String.join(" ", letters.split("")));
        return card -> {
            if (banned.contains(card.getName())) {
                return bannedIn(check);
            }
            if (!df.isLegalCard(card) || (check.pool() != null && !check.pool().getFilterRules().test(card))) {
                return Localizer.getInstance().getMessage("lblWebLegalityNotLegalIn", formatName(check));
            }
            if (!commanders.contains(card) && !inIdentity.test(card)) {
                return outside;
            }
            return null;
        };
    }

    /** The commanders' colour identity as WUBRG letters, or "" when there are none. */
    static String identityLetters(final List<PaperCard> commanders) {
        byte colours = 0;
        for (final PaperCard c : commanders) {
            colours |= c.getRules().getColorIdentity().getColor();
        }
        return CardCatalog.wubrg(ColorSet.fromMask(colours));
    }

    /** The cards whose colours the rest of the deck must share. In Oathbreaker that is the oathbreaker alone, not its spell. */
    static List<PaperCard> commanders(final Deck deck, final Check check) {
        if (check.unrestricted() || !check.deckFormat().hasCommander()) {
            return List.of();
        }
        if (check.format() == GameType.Oathbreaker) {
            final PaperCard oathbreaker = deck.getOathbreaker();
            return oathbreaker == null ? List.of() : List.of(oathbreaker);
        }
        return deck.getCommanders();
    }

    /** A card's problem in this deck and how a verdict naming several sums it up, or null when it has none. */
    private static String[] problemInDeck(final PaperCard card, final int count, final Check check,
            final Function<PaperCard, String> problems) {
        final int most = check.deckFormat().getMaxCardCopies(card);
        if (count > most) {
            return new String[] {Localizer.getInstance().getMessage("lblWebLegalityCopies", count, most), Localizer.getInstance().getMessage("lblWebLegalityOverCopyLimit")};
        }
        final String problem = problems.apply(card);
        if (problem != null) {
            // A ban is summed up without its format; being outside the identity or the format is summed up as it is
            return new String[] {problem, problem.equals(bannedIn(check)) ? Localizer.getInstance().getMessage("lblWebLegalityBanned") : problem};
        }
        final GameFormat pool = check.pool();
        if (pool != null && count > 1 && pool.getRestrictedCards().contains(card.getName())) {
            return new String[] {Localizer.getInstance().getMessage("lblWebLegalityAllowsOne", pool.getName()), Localizer.getInstance().getMessage("lblWebLegalityRestricted")};
        }
        return null;
    }

    private static String bannedIn(final Check check) {
        return Localizer.getInstance().getMessage("lblWebLegalityBannedIn", formatName(check));
    }

    /** Problems with the deck as a whole, each worked out here because the engine reports only its first one. */
    private static List<String> deckItems(final Deck deck, final DeckFormat df, final List<PaperCard> commanders,
            final boolean noCardProblems) {
        final List<String> items = new ArrayList<>();
        // The command zone counts toward the deck's size, as players count it: a Commander deck is 100 cards
        final int slots = !df.hasCommander() ? 0 : df.hasSignatureSpell() ? 2 : 1;
        final CardPool commanderSection = deck.get(DeckSection.Commander);
        final int size = deck.getMain().countAll() + (slots > 0 && commanderSection != null ? commanderSection.countAll() : 0);
        final Range<Integer> main = df.getMainRange();
        if (size < main.getMinimum() + slots) {
            items.add(Localizer.getInstance().getMessage("lblWebLegalityTooFew", size, main.getMinimum() + slots));
        } else if (main.getMaximum() != Integer.MAX_VALUE && size > main.getMaximum() + slots) {
            items.add(Localizer.getInstance().getMessage("lblWebLegalityTooMany", size, main.getMaximum() + slots));
        }
        final Range<Integer> side = df.getSideRange();
        final CardPool sideboard = deck.get(DeckSection.Sideboard);
        final int sideCount = sideboard == null ? 0 : sideboard.countAll();
        if (side != null && sideCount > side.getMaximum()) {
            items.add(Localizer.getInstance().getMessage("lblWebLegalitySideboard", sideCount, side.getMaximum()));
        }
        if (df.hasCommander() && commanders.isEmpty()) {
            items.add(Localizer.getInstance().getMessage("lblWebLegalityNoCommander"));
        }
        if (df.hasSignatureSpell() && deck.getSignatureSpell() == null) {
            items.add(Localizer.getInstance().getMessage("lblWebLegalityNoSignatureSpell"));
        }
        if (items.isEmpty() && noCardProblems) {
            final String engine = df.getDeckConformanceProblem(deck);
            if (engine != null) {
                items.add(engine);
            }
        }
        return items;
    }

    private static GameFormat commanderFormat(final GameType format) {
        return switch (format) {
            case Commander, Brawl, Oathbreaker -> FModel.getFormats().getFormat(format.getEnglishName());
            default -> null;
        };
    }

    private static String formatName(final Check check) {
        return check.pool() != null ? check.pool().getName() : check.format().toString();
    }

    /** One card's problem as the verdict states it: a count of copies, or what the card is. */
    private static String describe(final String name, final String flag, final String kind) {
        return Localizer.getInstance().getMessage("lblWebLegalityOverCopyLimit").equals(kind) ? Localizer.getInstance().getMessage("lblWebLegalityCardCopies", name, flag)
                : Localizer.getInstance().getMessage("lblWebLegalityCardIs", name, flag);
    }

    /** "a", "a, and b", "a, b, and c". */
    private static String joined(final List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return Localizer.getInstance().getMessage("lblWebLegalityAnd", String.join(", ", parts.subList(0, parts.size() - 1)), parts.get(parts.size() - 1));
    }
}
