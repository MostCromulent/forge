package forge.web;

import forge.StaticData;
import forge.card.CardRules;
import forge.card.CardType;
import forge.card.ColorSet;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.itemmanager.AdvancedSearchParser;
import forge.itemmanager.SFilterUtil;
import forge.model.FModel;
import forge.web.ToBrowser.CataloguePage;
import forge.web.ToBrowser.CatalogueRow;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Every card a deck can be built from, one row per card, for the deck editor's catalogue. Built once and shared by every
 * browser; a query filters it for one deck, whose rules arrive as a function naming each card's problem.
 */
final class CardCatalog {
    static final int PAGE = 60;
    /** The order a decklist is written in, which the headings and the type sort follow. */
    static final List<String> HEADINGS = List.of("Creatures", "Planeswalkers", "Instants", "Sorceries", "Artifacts",
            "Enchantments", "Battles", "Lands");

    /** What the browser asked for. colours is letters such as "BG"; type and mv are "any" or one value; sort is name, mv, colour or type. */
    record Query(String text, String colours, String type, String mv, String sort, int offset, boolean showAll) {
    }

    private record Row(PaperCard card, String normalised, String colours, String cost, int mv, String heading) {
    }

    private final List<Row> rows;

    private CardCatalog(final List<Row> rows) {
        this.rows = rows;
    }

    /** A sealed or draft pool as a catalogue: these cards only, whatever their section. */
    static CardCatalog of(final Iterable<PaperCard> cards) {
        final List<Row> rows = new ArrayList<>();
        cards.forEach(card -> rows.add(row(card)));
        return new CardCatalog(rows);
    }

    // The holder idiom builds the catalogue on first use, once, whichever thread asks first
    private static final class Holder {
        static final CardCatalog INSTANCE = build();
    }

    static CardCatalog get() {
        return Holder.INSTANCE;
    }

    private static CardCatalog build() {
        // Card scripts may load lazily, and every card has to be read to be listed
        FModel.getMagicDb().ensureAllCardsLoaded();
        final List<Row> rows = new ArrayList<>();
        for (final PaperCard card : StaticData.instance().getCommonCards().getUniqueCards()) {
            final CardRules rules = card.getRules();
            // Planes, schemes, attractions and the like live in sections of their own, never in a main deck
            if (rules.isUnsupported() || rules.isVariant() || rules.isCustom()
                    || DeckSection.matchingSection(card) != DeckSection.Main) {
                continue;
            }
            rows.add(row(card));
        }
        return new CardCatalog(rows);
    }

    private static Row row(final PaperCard card) {
        final CardRules rules = card.getRules();
        return new Row(card, normalize(card.getName()), letters(rules.getColor()), JsonCodec.manaCost(rules.getManaCost()),
                rules.getManaCost().getCMC(), heading(card));
    }

    /**
     * A page of the catalogue for one deck. problemOf names why a card can't go in it, or null; such cards are left out
     * unless the query asks for everything. commanderOnly, when set, narrows to cards that can lead the deck.
     */
    CataloguePage query(final int request, final Query q, final Function<PaperCard, String> problemOf,
            final Predicate<PaperCard> commanderOnly, final ToIntFunction<String> inDeck) {
        final String typed = q.text() == null ? "" : q.text().trim();
        // Search syntax such as c:bg or mv<=3 is read by desktop's own parser; plain text is a name, ranked as desktop ranks names
        final Predicate<PaperCard> syntax = usesSyntax(typed) ? SFilterUtil.buildTextFilter(typed, false, true, false, false, false) : null;
        final String text = syntax == null ? normalize(typed) : "";
        final List<Row> startsWith = new ArrayList<>();
        final List<Row> matches = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        int hidden = 0;
        for (final Row row : rows) {
            if ((syntax != null && !syntax.test(row.card())) || (!text.isEmpty() && !row.normalised().contains(text)) || !passes(row, q)
                    || (commanderOnly != null && !commanderOnly.test(row.card()))) {
                continue;
            }
            final String problem = problemOf.apply(row.card());
            if (problem != null && !q.showAll()) {
                hidden++;
                continue;
            }
            (!text.isEmpty() && row.normalised().startsWith(text) ? startsWith : matches).add(row);
        }
        final List<Row> ordered;
        if (text.isEmpty()) {
            matches.sort(order(q.sort()));
            ordered = matches;
        } else {
            // The same rule as the browser's own search boxes: names starting with the text first, shortest first
            startsWith.sort(Comparator.comparingInt(r -> r.card().getName().length()));
            startsWith.addAll(matches);
            ordered = startsWith;
        }
        final int from = Math.min(Math.max(0, q.offset()), ordered.size());
        final List<CatalogueRow> page = new ArrayList<>();
        for (final Row row : ordered.subList(from, Math.min(from + PAGE, ordered.size()))) {
            page.add(toBrowser(row, problemOf.apply(row.card()), inDeck.applyAsInt(row.card().getName())));
        }
        return new CataloguePage(request, page, ordered.size(), from, ordered.isEmpty() ? hidden : 0, !text.isEmpty());
    }

    /** Whether a query uses Forge's search syntax: a term the advanced parser reads, an or, a bracket, or a term left out with a minus. */
    static boolean usesSyntax(final String text) {
        for (final String term : text.split("\\s+")) {
            if ((term.startsWith("-") && term.length() > 1) || term.contains("|") || term.contains("(") || term.contains(")")
                    || "or".equalsIgnoreCase(term) || AdvancedSearchParser.parseAdvancedRulesToken(term) != null
                    || AdvancedSearchParser.parseAdvancedPaperCardToken(term) != null) {
                return true;
            }
        }
        return false;
    }

    /** The card name closest to a misspelt one: at most three edits away, and no more than a third of its length. */
    String closestName(final String misspelt) {
        final String wanted = normalize(misspelt);
        final int most = Math.min(3, wanted.length() / 3);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (final Row row : rows) {
            final int distance = StringUtils.getLevenshteinDistance(wanted, row.normalised(), most);
            if (distance >= 0 && distance < bestDistance) {
                best = row.card().getName();
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean passes(final Row row, final Query q) {
        final String colours = q.colours() == null ? "" : q.colours();
        if (!colours.isEmpty()) {
            final boolean any = row.colours().chars().anyMatch(c -> colours.indexOf(c) >= 0);
            if (!any) {
                return false;
            }
        }
        if (!"any".equals(q.type()) && q.type() != null && !isType(row.card().getRules().getType(), q.type())) {
            return false;
        }
        if (!"any".equals(q.mv()) && q.mv() != null) {
            return "7+".equals(q.mv()) ? row.mv() >= 7 : String.valueOf(row.mv()).equals(q.mv());
        }
        return true;
    }

    private static boolean isType(final CardType type, final String wanted) {
        return switch (wanted) {
            case "creature" -> type.isCreature();
            case "planeswalker" -> type.isPlaneswalker();
            case "instant" -> type.isInstant();
            case "sorcery" -> type.isSorcery();
            case "artifact" -> type.isArtifact();
            case "enchantment" -> type.isEnchantment();
            case "battle" -> type.isBattle();
            case "land" -> type.isLand();
            default -> true;
        };
    }

    private static Comparator<Row> order(final String sort) {
        final Comparator<Row> byName = Comparator.comparing(r -> r.card().getName());
        return switch (sort == null ? "name" : sort) {
            case "mv" -> Comparator.comparingInt(Row::mv).thenComparing(byName);
            case "colour" -> Comparator.comparing(Row::colours).thenComparing(byName);
            case "type" -> Comparator.<Row>comparingInt(r -> HEADINGS.indexOf(r.heading())).thenComparing(byName);
            default -> byName;
        };
    }

    private static CatalogueRow toBrowser(final Row row, final String problem, final int inDeck) {
        final CardRules rules = row.card().getRules();
        return new CatalogueRow(row.card().getName(), row.card().getImageKey(false), row.cost(), row.mv(),
                row.colours(), rules.getType().toString(), pt(rules), row.heading(), inDeck, problem);
    }

    private static String pt(final CardRules rules) {
        if (rules.getType().isCreature()) {
            return rules.getPower() + "/" + rules.getToughness();
        }
        return rules.getType().isPlaneswalker() ? rules.getInitialLoyalty() : null;
    }

    /** A card's colours as WUBRG letters, or "C" for a colourless card. */
    static String letters(final ColorSet colours) {
        final String out = (colours.hasWhite() ? "W" : "") + (colours.hasBlue() ? "U" : "") + (colours.hasBlack() ? "B" : "")
                + (colours.hasRed() ? "R" : "") + (colours.hasGreen() ? "G" : "");
        return out.isEmpty() ? "C" : out;
    }

    /** Lower case, accents stripped, and nothing but letters, digits and spaces, as desktop's ListChooser compares names. */
    static String normalize(final String s) {
        return StringUtils.stripAccents(s.toLowerCase()).replaceAll("[^a-z0-9 ]", "");
    }

    // A card lands under the first heading its type matches, the order a decklist is normally written in
    static String heading(final PaperCard card) {
        final CardType type = card.getRules().getType();
        if (type.isLand()) {
            return "Lands";
        }
        if (type.isCreature()) {
            return "Creatures";
        }
        if (type.isPlaneswalker()) {
            return "Planeswalkers";
        }
        if (type.isInstant()) {
            return "Instants";
        }
        if (type.isSorcery()) {
            return "Sorceries";
        }
        if (type.isBattle()) {
            return "Battles";
        }
        return type.isEnchantment() ? "Enchantments" : "Artifacts";
    }
}
