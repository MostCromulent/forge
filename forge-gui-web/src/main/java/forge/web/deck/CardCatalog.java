package forge.web.deck;

import forge.StaticData;
import forge.card.CardEdition;
import forge.card.CardRules;
import forge.card.CardType;
import forge.card.ColorSet;
import forge.deck.CardPool;
import forge.game.GameFormat;
import forge.item.PaperCard;
import forge.util.Localizer;
import forge.web.images.Foil;
import forge.web.protocol.JsonCodec;
import forge.web.protocol.ToBrowser;
import forge.web.protocol.ToBrowser.EditorCard;
import forge.web.protocol.ToBrowser.EditorPrinting;
import forge.web.protocol.ToBrowser.EditorGroup;
import forge.web.protocol.ToBrowser.Printing;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Every card that a deck can be built from. The catalogue is built once and shared by every browser. */
public final class CardCatalog {
    /** The order in which a decklist is written. The headings and the type sort use this order. */
    static final List<String> HEADINGS = List.of("Creatures", "Planeswalkers", "Instants", "Sorceries", "Artifacts",
            "Enchantments", "Battles", "Lands");
    private static final Pattern NOT_NAME_CHARACTER = Pattern.compile("[^a-z0-9 ]");
    public static ToBrowser.CardSets sets() {
        return Sets.ALL;
    }

    /** A card's colours as WUBRG letters, or "C" for a colourless card. */
    public static String letters(final ColorSet colours) {
        final String out = wubrg(colours);
        return out.isEmpty() ? "C" : out;
    }

    /** Colours as WUBRG letters, or an empty string for colourless. */
    public static String wubrg(final ColorSet colours) {
        return (colours.hasWhite() ? "W" : "") + (colours.hasBlue() ? "U" : "") + (colours.hasBlack() ? "B" : "")
                + (colours.hasRed() ? "R" : "") + (colours.hasGreen() ? "G" : "");
    }

    /** Lower case, with accents stripped and only letters, digits and spaces kept. Desktop's ListChooser compares names the same way. */
    static String normalize(final String s) {
        return NOT_NAME_CHARACTER.matcher(StringUtils.stripAccents(s.toLowerCase())).replaceAll("");
    }

    // A card with several types goes under the first match in the order below, so an artifact land is a land
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

    public static List<EditorGroup> groups(final CardPool pool, final Legality.Result legality) {
        final Map<String, List<EditorCard>> byHeading = new LinkedHashMap<>();
        HEADINGS.forEach(h -> byHeading.put(h, new ArrayList<>()));
        final Map<String, String> headings = new LinkedHashMap<>();
        for (final Map.Entry<PaperCard, Integer> e : pool) {
            headings.putIfAbsent(e.getKey().getName(), heading(e.getKey()));
        }
        for (final EditorCard card : cards(pool, legality)) {
            byHeading.get(headings.get(card.name())).add(card);
        }
        final List<EditorGroup> out = new ArrayList<>();
        byHeading.forEach((heading, cards) -> {
            if (!cards.isEmpty()) {
                out.add(new EditorGroup(heading, cards));
            }
        });
        return out;
    }

    /** A pool's cards, one per name, in mana value then name order. */
    public static List<EditorCard> cards(final CardPool pool, final Legality.Result legality) {
        final List<EditorCard> out = new ArrayList<>();
        if (pool == null) {
            return out;
        }
        final Map<String, List<Map.Entry<PaperCard, Integer>>> byName = new LinkedHashMap<>();
        for (final Map.Entry<PaperCard, Integer> e : pool) {
            byName.computeIfAbsent(e.getKey().getName(), n -> new ArrayList<>()).add(Map.entry(e.getKey(), e.getValue()));
        }
        byName.forEach((name, printings) -> {
            printings.sort(Map.Entry.<PaperCard, Integer>comparingByValue().reversed());
            final PaperCard shown = printings.get(0).getKey();
            final CardRules rules = shown.getRules();
            final List<EditorPrinting> split = printings.stream()
                    .map(p -> new EditorPrinting(p.getKey().getImageKey(false), p.getValue())).toList();
            out.add(new EditorCard(name, printings.stream().mapToInt(Map.Entry::getValue).sum(), Foil.key(shown),
                    JsonCodec.manaCost(rules.getManaCost()), rules.getManaCost().getCMC(), letters(rules.getColor()),
                    printings.size(), split, legality.flags().get(name), printings.stream().allMatch(p -> p.getKey().isFoil())));
        });
        out.sort(Comparator.comparingInt(EditorCard::mv).thenComparing(EditorCard::name));
        return out;
    }

    /** Card names that start with the text come first, shortest first, then names that contain it. */
    public static List<String> searchCardNames(final String query, final int limit) {
        final String text = normalize(query == null ? "" : query);
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        final List<String> startsWith = new ArrayList<>();
        final List<String> contains = new ArrayList<>();
        for (final PaperCard card : StaticData.instance().getCommonCards().getUniqueCards()) {
            final String name = normalize(card.getName());
            if (name.startsWith(text)) {
                startsWith.add(card.getName());
            } else if (name.contains(text)) {
                contains.add(card.getName());
            }
        }
        startsWith.sort(Comparator.comparingInt(String::length));
        startsWith.addAll(contains);
        return new ArrayList<>(startsWith.subList(0, Math.min(limit, startsWith.size())));
    }

    /** Every printing of one card, newest first as in desktop's printing dialog. A specific printing can then be picked for a deck or a sleeve. */
    public static List<Printing> printings(final String name, final GameFormat pool) {
        final CardEdition.Collection editions = StaticData.instance().getEditions();
        final List<PaperCard> cards = new ArrayList<>(StaticData.instance().getCommonCards().getAllCardsNoAlt(name));
        cards.sort(Comparator.comparing((PaperCard card) -> editions.get(card.getEdition()) == null ? null : editions.get(card.getEdition()).getDate(),
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(PaperCard::getCollectorNumber, Comparator.nullsLast(Comparator.naturalOrder())));
        final List<Printing> out = new ArrayList<>();
        for (final PaperCard card : cards) {
            final CardEdition edition = editions.get(card.getEdition());
            final Calendar date = Calendar.getInstance();
            if (edition != null) {
                date.setTime(edition.getDate());
            }
            out.add(new Printing(card.getName(), card.getEdition(), card.getImageKey(false),
                    edition == null ? card.getEdition() : edition.getName(), edition == null ? 0 : date.get(Calendar.YEAR),
                    edition == null ? null : edition.getSectionForCollectorNumber(card.getCollectorNumber()),
                    pool != null && !pool.getFilterPrinted().test(card) ? Localizer.getInstance().getMessage("lblWebDeckCatalogNotIn", pool.getName()) : null));
        }
        return out;
    }

    private static final class Sets {
        static final ToBrowser.CardSets ALL = build();

        private static ToBrowser.CardSets build() {
            final List<CardEdition> editions = new ArrayList<>();
            for (final CardEdition e : StaticData.instance().getEditions()) {
                if (!e.getAllCardsInSet().isEmpty()) {
                    editions.add(e);
                }
            }
            editions.sort(Comparator.reverseOrder());
            return new ToBrowser.CardSets(editions.stream().map(e -> new ToBrowser.LimitedEdition(e.getCode(), e.getName())).toList());
        }
    }
}
