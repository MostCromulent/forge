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
import org.apache.commons.lang3.Range;

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
            return "No restriction";
        }
        return pool == null ? format.toString() : format + " · " + pool.getName();
    }
}

/**
 * Whether a deck can be played under a check, card by card and as a whole. The editor's verdict, the catalogue's hiding
 * and the importer's line marks all read this, so the three never disagree. It asks the engine's rules and reports them;
 * it only adds what DeckFormat.getDeckConformanceProblem leaves out, which is the commander formats' ban lists and every
 * problem after the first.
 */
final class Legality {
    private Legality() {
    }

    /** Problems with the deck as a whole, and each card's problem by name. */
    record Result(Map<String, String> flags, List<String> deckItems) {
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
                    return capitalised(deckItems.get(0)) + ".";
                }
                final Map.Entry<String, String> only = flags.entrySet().iterator().next();
                return describe(only.getKey(), only.getValue()) + ".";
            }
            final List<String> parts = new ArrayList<>(deckItems.subList(0, Math.min(2, deckItems.size())));
            final Map<String, Integer> kinds = new LinkedHashMap<>();
            for (final String flag : flags.values()) {
                kinds.merge(kindOf(flag), 1, Integer::sum);
            }
            kinds.forEach((kind, n) -> parts.add(n + (n == 1 ? " card " : " cards ") + kind));
            return problemCount() + " problems: " + joined(parts) + ".";
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
        for (final Map.Entry<String, PaperCard> e : byName.entrySet()) {
            final String problem = problemInDeck(e.getValue(), all.countByName(e.getKey()), check, problems);
            if (problem != null) {
                flags.put(e.getKey(), problem);
            }
        }
        return new Result(flags, check.unrestricted() ? List.of() : deckItems(deck, df, commanders, flags.isEmpty()));
    }

    /** Why one card can't go in this deck, or null: a ban, the format, the card pool or the commander's colours. Counts are not considered. */
    static String cardProblem(final PaperCard card, final Check check, final List<PaperCard> commanders) {
        return cardProblems(check, commanders).apply(card);
    }

    /** cardProblem with its rules looked up once, for asking about many cards in a row. */
    static Function<PaperCard, String> cardProblems(final Check check, final List<PaperCard> commanders) {
        if (check.unrestricted()) {
            return card -> null;
        }
        final DeckFormat df = check.deckFormat();
        final GameFormat banList = check.pool() != null ? check.pool() : commanderFormat(check.format());
        final Set<String> banned = banList == null ? Set.of() : new HashSet<>(banList.getBannedCardNames());
        final Predicate<PaperCard> inIdentity = commanders.isEmpty() ? card -> true : df.isLegalCardForCommanderPredicate(commanders);
        final String outside = "outside " + String.join(" ", identityLetters(commanders).split(""));
        return card -> {
            if (banned.contains(card.getName())) {
                return "banned in " + formatName(check);
            }
            if (!df.isLegalCard(card) || (check.pool() != null && !check.pool().getFilterRules().test(card))) {
                return "not legal in " + formatName(check);
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
        final ColorSet identity = ColorSet.fromMask(colours);
        return (identity.hasWhite() ? "W" : "") + (identity.hasBlue() ? "U" : "") + (identity.hasBlack() ? "B" : "")
                + (identity.hasRed() ? "R" : "") + (identity.hasGreen() ? "G" : "");
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

    private static String problemInDeck(final PaperCard card, final int count, final Check check,
            final Function<PaperCard, String> problems) {
        final int most = check.deckFormat().getMaxCardCopies(card);
        if (count > most) {
            return count + " of " + most;
        }
        final String problem = problems.apply(card);
        if (problem != null) {
            return problem;
        }
        final GameFormat pool = check.pool();
        if (pool != null && count > 1 && pool.getRestrictedCards().contains(card.getName())) {
            return pool.getName() + " allows one";
        }
        return null;
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
            items.add(size + " of " + (main.getMinimum() + slots) + " cards");
        } else if (main.getMaximum() != Integer.MAX_VALUE && size > main.getMaximum() + slots) {
            items.add(size + " cards, " + (main.getMaximum() + slots) + " at most");
        }
        final Range<Integer> side = df.getSideRange();
        final CardPool sideboard = deck.get(DeckSection.Sideboard);
        final int sideCount = sideboard == null ? 0 : sideboard.countAll();
        if (side != null && sideCount > side.getMaximum()) {
            items.add("sideboard of " + sideCount + ", " + side.getMaximum() + " at most");
        }
        if (df.hasCommander() && commanders.isEmpty()) {
            items.add("no commander");
        }
        if (df.hasSignatureSpell() && deck.getSignatureSpell() == null) {
            items.add("no signature spell");
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

    private static String describe(final String name, final String flag) {
        return Character.isDigit(flag.charAt(0)) ? name + ": " + flag + " copies" : name + " is " + flag;
    }

    // How a group of cards is summed up in a verdict that names several problems
    private static String kindOf(final String flag) {
        if (flag.startsWith("outside ")) {
            return flag;
        }
        if (flag.startsWith("banned")) {
            return "banned";
        }
        if (flag.startsWith("not legal")) {
            return flag;
        }
        return Character.isDigit(flag.charAt(0)) ? "over the copy limit" : "restricted";
    }

    private static String capitalised(final String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** "a", "a, and b", "a, b, and c". */
    private static String joined(final List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + ", and " + parts.get(parts.size() - 1);
    }
}
