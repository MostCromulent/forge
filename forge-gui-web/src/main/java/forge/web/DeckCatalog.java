package forge.web;

import forge.StaticData;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.ArchetypeDeckGenerator;
import forge.deck.DeckProxy;
import forge.deck.DeckgenUtil;
import forge.deck.NetDeckCategory;
import forge.deck.DeckSection;
import forge.card.ColorSet;
import forge.game.GameFormat;
import forge.game.GameType;
import forge.gamemodes.quest.QuestController;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.SleeveArt;
import forge.web.ToBrowser.DeckCard;
import forge.web.ToBrowser.DeckDetails;
import forge.web.ToBrowser.DeckGroup;
import forge.web.ToBrowser.DeckStats;
import forge.web.ToBrowser.DeckSummary;
import forge.web.ToBrowser.Printing;
import forge.web.ToBrowser.SavedSleeveArt;
import forge.web.ToBrowser.TypeCount;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The decks a seat may choose from, and what the browser shows about each one: your own, the preconstructed ones,
 * quest opponents, generated decks and downloaded net decks, for Constructed and Commander.
 */
final class DeckCatalog {
    /** Where a deck came from, which the browser tags each row with. */
    private static final String MINE = "yours";
    private static final String PRECON = "precons";
    private static final String QUEST = "quest";
    /** Sources that make a deck when you pick one, rather than loading a saved deck. */
    private static final String GENERATED = "generated";
    private static final String NET = "net";
    /** A colour generator knows its own colour before it builds anything; the other generators do not. */
    private static final Map<String, String> COLOUR_LETTERS = Map.of(
            "White", "W", "Blue", "U", "Black", "B", "Red", "R", "Green", "G");
    /** Mana values 0 to 5, then everything 6 and above in the last one. */
    private static final int CURVE_BUCKETS = 7;

    private final Map<String, Entry> byKey = new ConcurrentHashMap<>();

    /** A generator's deck is built on demand and kept, so the panel and the match see the same cards. */
    private record Entry(DeckProxy proxy, boolean generated, Deck built) {
        Entry(final DeckProxy proxy) {
            this(proxy, false, null);
        }
    }

    /** The sanctioned formats a deck can be filtered by, which is a card-pool question and not the game type. */
    static List<String> cardFormats() {
        final List<String> out = new ArrayList<>();
        for (final GameFormat f : FModel.getFormats().getSanctionedList()) {
            out.add(f.getName());
        }
        return out;
    }

    /** Which of those formats this deck's cards are all legal in. */
    private static List<String> legalIn(final Deck deck) {
        final List<String> out = new ArrayList<>();
        for (final GameFormat f : FModel.getFormats().getSanctionedList()) {
            if (f.isDeckLegal(deck)) {
                out.add(f.getName());
            }
        }
        return out;
    }

    /** Categories the browser has asked for and core has downloaded, kept so a refresh does not lose them. */
    private final List<NetDeckCategory> netCategories = new ArrayList<>();

    /**
     * Held while anything reads or changes a deck. Every browser's catalogue reads the same decks from Forge's deck
     * storage, and a deck loads its sections the first time it is read, which is not safe from two threads at once:
     * two browsers opening match setup together could each find a deck half loaded. Match setup reads decks too,
     * so it takes the same lock.
     */
    static final Object DECKS = new Object();

    /** Asks core for a net deck category. Core picks one through the browser and downloads it. */
    void loadNetDecks(final GameType format) {
        // Picking waits on the host's answer, so the decks are locked only to record what was picked
        final NetDeckCategory category = NetDeckCategory.selectAndLoad(format);
        synchronized (DECKS) {
            if (category != null && !netCategories.contains(category)) {
                netCategories.add(category);
            }
        }
    }

    /** Rebuilds the catalogue for a format and returns every deck in it. */
    List<DeckSummary> refresh(final GameType format) {
        synchronized (DECKS) {
            byKey.clear();
            final List<DeckSummary> out = new ArrayList<>();
            final boolean commander = format == GameType.Commander;
            add(out, format, commander ? DeckProxy.getAllCommanderDecks() : DeckProxy.getAllConstructedDecks(), MINE);
            add(out, format, commander ? DeckProxy.getAllCommanderPreconDecks()
                    : DeckProxy.getAllPreconstructedDecks(QuestController.getPrecons()), PRECON);
            if (!commander) {
                add(out, format, DeckProxy.getAllQuestEventAndChallenges(), QUEST);
                addGenerators(out);
            }
            for (final NetDeckCategory category : netCategories) {
                add(out, format, DeckProxy.getNetDecks(category), NET + " " + category.getName());
            }
            return out;
        }
    }

    Deck deck(final String key) {
        synchronized (DECKS) {
            final Entry e = key == null ? null : byKey.get(key);
            if (e == null) {
                return null;
            }
            // A generator has no deck until it is asked for one, and builds a different deck each time
            if (e.built() != null) {
                return e.built();
            }
            final Deck deck = e.proxy() == null ? buildColours(key) : e.proxy().getDeck();
            if (e.generated()) {
                byKey.put(key, new Entry(e.proxy(), true, deck));
            }
            return deck;
        }
    }

    /** Builds the colour generator behind a "gen:color:" key, whose suffix names what the engine expects. */
    private static Deck buildColours(final String key) {
        final List<String> selection = List.of(key.substring(key.lastIndexOf(':') + 1));
        return DeckgenUtil.colorCheck(selection) ? DeckgenUtil.buildColorDeck(selection, null, false) : null;
    }

    /** Writes a card-art sleeve onto a deck and saves it, the way the desktop lobby does. */
    boolean saveSleeveArt(final String key, final String imageKey, final int offset) {
        synchronized (DECKS) {
            final Entry e = key == null ? null : byKey.get(key);
            final Deck deck = e == null ? null : e.proxy().getDeck();
            if (deck == null) {
                return false;
            }
            deck.setSleeveArtKey(imageKey);
            deck.setSleeveArtOffset(SleeveArt.clampOffset(offset));
            rememberSleeveArt(imageKey, offset);
            return e.proxy().saveDeck();
        }
    }

    /** The chosen deck's card list, grouped the way a decklist is read, for the panel beside the results. */
    DeckDetails details(final String key, final GameType format) {
        synchronized (DECKS) {
            final Deck deck = deck(key);
            if (deck == null) {
                return null;
            }
            return new DeckDetails(key, deck.getName(), problem(deck, format), colors(deck), stats(deck),
                    groups(deck.get(DeckSection.Main)), cards(deck.get(DeckSection.Sideboard)), deck.getSleeveArtKey(),
                    deck.getSleeveArtOffset());
        }
    }

    /** The deck's colour identity as WUBRG letters, or "C" when it has none. */
    static String colors(final Deck deck) {
        final ColorSet identity = DeckProxy.getColorIdentity(deck);
        final StringBuilder sb = new StringBuilder();
        if (identity.hasWhite()) {
            sb.append('W');
        }
        if (identity.hasBlue()) {
            sb.append('U');
        }
        if (identity.hasBlack()) {
            sb.append('B');
        }
        if (identity.hasRed()) {
            sb.append('R');
        }
        if (identity.hasGreen()) {
            sb.append('G');
        }
        return sb.length() == 0 ? "C" : sb.toString();
    }

    /** What the panel reports about a deck beside its card list. */
    private static DeckStats stats(final Deck deck) {
        final CardPool main = deck.get(DeckSection.Main);
        final int[] curve = new int[CURVE_BUCKETS];
        final Map<String, Integer> types = new LinkedHashMap<>();
        int lands = 0;
        int spells = 0;
        int totalMana = 0;
        if (main != null) {
            for (final Map.Entry<PaperCard, Integer> e : main) {
                final PaperCard card = e.getKey();
                final int n = e.getValue();
                types.merge(heading(card), n, Integer::sum);
                if (card.getRules().getType().isLand()) {
                    lands += n;
                    continue;
                }
                // A land has no mana value worth curving, so the curve and the average are spells only
                final int cmc = card.getRules().getManaCost().getCMC();
                curve[Math.min(cmc, CURVE_BUCKETS - 1)] += n;
                spells += n;
                totalMana += cmc * n;
            }
        }
        final List<Integer> buckets = new ArrayList<>();
        for (final int n : curve) {
            buckets.add(n);
        }
        final List<TypeCount> typeCounts = new ArrayList<>();
        for (final Map.Entry<String, Integer> e : types.entrySet()) {
            typeCounts.add(new TypeCount(e.getKey(), e.getValue()));
        }
        return new DeckStats(count(main), count(deck.get(DeckSection.Sideboard)), lands,
                spells == 0 ? 0 : Math.round((totalMana * 100f) / spells) / 100f, buckets, typeCounts);
    }

    /** Sources that build a deck when you pick one. They are listed by name only: there is nothing to
     *  measure, and asking each for a deck just to fill a row would build hundreds of them. */
    private void addGenerators(final List<DeckSummary> out) {
        // The tokens the colour generator expects, shown under friendlier names
        final Map<String, String> colours = new LinkedHashMap<>();
        colours.put("Random 1", "Random one colour");
        colours.put("Random 2", "Random two colours");
        colours.put("Random 3", "Random three colours");
        for (final String c : List.of("White", "Blue", "Black", "Red", "Green")) {
            colours.put(c, c);
        }
        for (final Map.Entry<String, String> c : colours.entrySet()) {
            final String key = "gen:color:" + c.getKey();
            byKey.put(key, new Entry(null, true, null));
            generated(out, key, c.getValue(), "Built when you pick it", COLOUR_LETTERS.getOrDefault(c.getKey(), ""));
        }
        for (final DeckProxy theme : DeckProxy.getAllThemeDecks()) {
            byKey.put("gen:theme:" + theme.getName(), new Entry(theme, true, null));
            generated(out, "gen:theme:" + theme.getName(), theme.getName(), "Theme deck", "");
        }
        if (FModel.isdeckGenMatrixLoaded()) {
            for (final GameFormat f : FModel.getFormats().getSanctionedList()) {
                for (final DeckProxy archetype : ArchetypeDeckGenerator.getMatrixDecks(f, false)) {
                    final String key = "gen:archetype:" + f.getName() + ":" + archetype.getName();
                    byKey.put(key, new Entry(archetype, true, null));
                    generated(out, key, archetype.getName(), f.getName() + " archetype", "");
                }
            }
        }
    }

    // Colours are empty where they are not known until the deck is built, which a colour filter treats as no match
    private static void generated(final List<DeckSummary> out, final String key, final String name, final String note,
            final String colours) {
        out.add(new DeckSummary(key, name, GENERATED, colours, true, note, null, null, null, null, null, null, null));
    }

    private void add(final List<DeckSummary> out, final GameType format, final Iterable<DeckProxy> source, final String tag) {
        for (final DeckProxy proxy : source) {
            final String key = tag + ":" + proxy.getPath() + "/" + proxy.getName();
            byKey.put(key, new Entry(proxy));
            final Deck deck = proxy.getDeck();
            // An illegal deck is shown and marked rather than hidden, so nobody hunts for a deck that is there.
            // Its formats are the same wording the desktop chooser puts in its format column.
            out.add(new DeckSummary(key, proxy.getName(), tag, colors(deck), null, null, count(deck.get(DeckSection.Main)),
                    count(deck.get(DeckSection.Sideboard)), problem(deck, format), legalIn(deck), proxy.getFormatsString(),
                    deck.getSleeveArtKey(), deck.getSleeveArtOffset()));
        }
    }

    /** Why this deck cannot be played in this format, or null when it can. */
    static String problem(final Deck deck, final GameType format) {
        if (deck == null) {
            return "No deck chosen.";
        }
        if (!FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY)) {
            return null;
        }
        return format.getDeckFormat().getDeckConformanceProblem(deck);
    }

    private static int count(final CardPool pool) {
        return pool == null ? 0 : pool.countAll();
    }

    private static List<DeckCard> cards(final CardPool pool) {
        final List<DeckCard> out = new ArrayList<>();
        if (pool == null) {
            return out;
        }
        for (final Map.Entry<PaperCard, Integer> e : pool) {
            out.add(card(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static DeckCard card(final PaperCard card, final int count) {
        return new DeckCard(card.getName(), count, card.getImageKey(false));
    }

    /** Main-deck cards under the headings a decklist normally carries. */
    private static List<DeckGroup> groups(final CardPool pool) {
        final Map<String, List<DeckCard>> sections = new LinkedHashMap<>();
        for (final String heading : List.of("Creatures", "Planeswalkers", "Instants", "Sorceries",
                "Artifacts", "Enchantments", "Battles", "Lands")) {
            sections.put(heading, new ArrayList<>());
        }
        if (pool != null) {
            for (final Map.Entry<PaperCard, Integer> e : pool) {
                sections.get(heading(e.getKey())).add(card(e.getKey(), e.getValue()));
            }
        }
        final List<DeckGroup> out = new ArrayList<>();
        for (final Map.Entry<String, List<DeckCard>> e : sections.entrySet()) {
            if (!e.getValue().isEmpty()) {
                out.add(new DeckGroup(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    // A card lands under the first heading its type matches, the order a decklist is normally written in
    private static String heading(final PaperCard card) {
        final var type = card.getRules().getType();
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

    /** The card-art sleeves already saved, shared with the desktop client. */
    static List<SavedSleeveArt> savedSleeveArt() {
        final List<SavedSleeveArt> out = new ArrayList<>();
        final Map<String, Integer> library = SleeveArt.parseLibrary(
                FModel.getPreferences().getPref(FPref.UI_SLEEVE_ART_LIBRARY));
        for (final Map.Entry<String, Integer> e : library.entrySet()) {
            out.add(new SavedSleeveArt(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** Remembers a card-art sleeve alongside the ones desktop has saved, newest last. */
    static void rememberSleeveArt(final String imageKey, final int offset) {
        if (imageKey == null || imageKey.isEmpty()) {
            return;
        }
        final var prefs = FModel.getPreferences();
        final FPref pref = FPref.UI_SLEEVE_ART_LIBRARY;
        final Map<String, Integer> library = new LinkedHashMap<>(SleeveArt.parseLibrary(prefs.getPref(pref)));
        library.remove(imageKey);
        library.put(imageKey, SleeveArt.clampOffset(offset));
        prefs.setPref(pref, SleeveArt.formatLibrary(library));
        prefs.save();
    }

    /** Card names matching what has been typed, for the card-art sleeve picker. */
    static List<String> searchCardNames(final String query, final int limit) {
        final List<String> out = new ArrayList<>();
        final String needle = query == null ? "" : query.trim().toLowerCase();
        if (needle.isEmpty()) {
            return out;
        }
        for (final PaperCard card : StaticData.instance().getCommonCards().getUniqueCards()) {
            if (card.getName().toLowerCase().contains(needle)) {
                out.add(card.getName());
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /** Every printing of one card, so a specific art can be picked for a sleeve. */
    static List<Printing> printings(final String name) {
        final List<Printing> out = new ArrayList<>();
        for (final PaperCard card : StaticData.instance().getCommonCards().getAllCardsNoAlt(name)) {
            out.add(new Printing(card.getName(), card.getEdition(), card.getImageKey(false)));
        }
        return out;
    }
}
