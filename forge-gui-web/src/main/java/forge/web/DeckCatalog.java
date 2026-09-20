package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import forge.model.FModel;
import forge.util.SleeveArt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The decks a seat may choose from, and what the browser shows about each one. Sources are the ones the
 * vertical slice covers: your own saved decks and the preconstructed ones, for Constructed and Commander.
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
    private record Entry(DeckProxy proxy, String source, boolean generated, Deck built) {
        Entry(final DeckProxy proxy, final String source) {
            this(proxy, source, false, null);
        }
    }

    /** The sanctioned formats a deck can be filtered by, which is a card-pool question and not the game type. */
    static JsonArray cardFormats() {
        final JsonArray out = new JsonArray();
        for (final GameFormat f : FModel.getFormats().getSanctionedList()) {
            out.add(f.getName());
        }
        return out;
    }

    /** Which of those formats this deck's cards are all legal in. */
    private static JsonArray legalIn(final Deck deck) {
        final JsonArray out = new JsonArray();
        for (final GameFormat f : FModel.getFormats().getSanctionedList()) {
            if (f.isDeckLegal(deck)) {
                out.add(f.getName());
            }
        }
        return out;
    }

    /** Categories the browser has asked for and core has downloaded, kept so a refresh does not lose them. */
    private final List<NetDeckCategory> netCategories = new ArrayList<>();

    /** Asks core for a net deck category. Core picks one through the browser and downloads it. */
    void loadNetDecks(final GameType format) {
        final NetDeckCategory category = NetDeckCategory.selectAndLoad(format);
        if (category != null && !netCategories.contains(category)) {
            netCategories.add(category);
        }
    }

    /** Rebuilds the catalogue for a format and returns every deck in it. */
    JsonArray refresh(final GameType format) {
        byKey.clear();
        final JsonArray out = new JsonArray();
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

    Deck deck(final String key) {
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
            byKey.put(key, new Entry(e.proxy(), e.source(), true, deck));
        }
        return deck;
    }

    /** Builds the colour generator behind a "gen:color:" key, whose suffix names what the engine expects. */
    private static Deck buildColours(final String key) {
        final List<String> selection = List.of(key.substring(key.lastIndexOf(':') + 1));
        return DeckgenUtil.colorCheck(selection) ? DeckgenUtil.buildColorDeck(selection, null, false) : null;
    }

    /** Writes a card-art sleeve onto a deck and saves it, the way the desktop lobby does. */
    boolean saveSleeveArt(final String key, final String imageKey, final int offset) {
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

    /** The chosen deck's card list, grouped the way a decklist is read, for the panel beside the results. */
    JsonObject details(final String key, final GameType format) {
        final Deck deck = deck(key);
        if (deck == null) {
            return null;
        }
        final JsonObject out = new JsonObject();
        out.addProperty("key", key);
        out.addProperty("name", deck.getName());
        out.addProperty("problem", problem(deck, format));
        out.addProperty("colors", colors(deck));
        out.add("stats", stats(deck));
        out.add("main", groups(deck.get(DeckSection.Main)));
        out.add("sideboard", cards(deck.get(DeckSection.Sideboard)));
        out.addProperty("sleeveArt", deck.getSleeveArtKey());
        out.addProperty("sleeveOffset", deck.getSleeveArtOffset());
        return out;
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
    private static JsonObject stats(final Deck deck) {
        final CardPool main = deck.get(DeckSection.Main);
        final JsonObject out = new JsonObject();
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
        out.addProperty("main", count(main));
        out.addProperty("sideboard", count(deck.get(DeckSection.Sideboard)));
        out.addProperty("lands", lands);
        out.addProperty("averageMana", spells == 0 ? 0 : Math.round((totalMana * 100f) / spells) / 100f);
        final JsonArray buckets = new JsonArray();
        for (final int n : curve) {
            buckets.add(n);
        }
        out.add("curve", buckets);
        final JsonArray typeCounts = new JsonArray();
        for (final Map.Entry<String, Integer> e : types.entrySet()) {
            final JsonObject t = new JsonObject();
            t.addProperty("name", e.getKey());
            t.addProperty("count", e.getValue());
            typeCounts.add(t);
        }
        out.add("types", typeCounts);
        return out;
    }

    /** Sources that build a deck when you pick one. They are listed by name only: there is nothing to
     *  measure, and asking each for a deck just to fill a row would build hundreds of them. */
    private void addGenerators(final JsonArray out) {
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
            byKey.put(key, new Entry(null, GENERATED, true, null));
            generated(out, key, c.getValue(), "Built when you pick it", COLOUR_LETTERS.getOrDefault(c.getKey(), ""));
        }
        for (final DeckProxy theme : DeckProxy.getAllThemeDecks()) {
            byKey.put("gen:theme:" + theme.getName(), new Entry(theme, GENERATED, true, null));
            generated(out, "gen:theme:" + theme.getName(), theme.getName(), "Theme deck", "");
        }
        if (FModel.isdeckGenMatrixLoaded()) {
            for (final GameFormat f : FModel.getFormats().getSanctionedList()) {
                for (final DeckProxy archetype : ArchetypeDeckGenerator.getMatrixDecks(f, false)) {
                    final String key = "gen:archetype:" + f.getName() + ":" + archetype.getName();
                    byKey.put(key, new Entry(archetype, GENERATED, true, null));
                    generated(out, key, archetype.getName(), f.getName() + " archetype", "");
                }
            }
        }
    }

    private static void generated(final JsonArray out, final String key, final String name, final String note,
            final String colours) {
        final JsonObject d = new JsonObject();
        d.addProperty("key", key);
        d.addProperty("name", name);
        d.addProperty("source", GENERATED);
        d.addProperty("generated", true);
        d.addProperty("note", note);
        // Empty where the colours are not known until the deck is built, which a colour filter treats as no match
        d.addProperty("colors", colours);
        out.add(d);
    }

    private void add(final JsonArray out, final GameType format, final Iterable<DeckProxy> source, final String tag) {
        for (final DeckProxy proxy : source) {
            final String key = tag + ":" + proxy.getPath() + "/" + proxy.getName();
            byKey.put(key, new Entry(proxy, tag));
            final Deck deck = proxy.getDeck();
            final JsonObject d = new JsonObject();
            d.addProperty("key", key);
            d.addProperty("name", proxy.getName());
            d.addProperty("source", tag);
            d.addProperty("main", count(deck.get(DeckSection.Main)));
            d.addProperty("sideboard", count(deck.get(DeckSection.Sideboard)));
            // An illegal deck is shown and marked rather than hidden, so nobody hunts for a deck that is there
            d.addProperty("problem", problem(deck, format));
            d.addProperty("colors", colors(deck));
            d.add("legalIn", legalIn(deck));
            // The same wording the desktop chooser puts in its format column
            d.addProperty("formats", proxy.getFormatsString());
            d.addProperty("sleeveArt", deck.getSleeveArtKey());
            d.addProperty("sleeveOffset", deck.getSleeveArtOffset());
            out.add(d);
        }
    }

    /** Why this deck cannot be played in this format, or null when it can. */
    static String problem(final Deck deck, final GameType format) {
        if (deck == null) {
            return "No deck chosen.";
        }
        if (!FModel.getPreferences().getPrefBoolean(forge.localinstance.properties.ForgePreferences.FPref.ENFORCE_DECK_LEGALITY)) {
            return null;
        }
        return format.getDeckFormat().getDeckConformanceProblem(deck);
    }

    private static int count(final CardPool pool) {
        return pool == null ? 0 : pool.countAll();
    }

    private static JsonArray cards(final CardPool pool) {
        final JsonArray out = new JsonArray();
        if (pool == null) {
            return out;
        }
        for (final Map.Entry<PaperCard, Integer> e : pool) {
            final JsonObject c = new JsonObject();
            c.addProperty("name", e.getKey().getName());
            c.addProperty("count", e.getValue());
            c.addProperty("image", e.getKey().getImageKey(false));
            out.add(c);
        }
        return out;
    }

    /** Main-deck cards under the headings a decklist normally carries. */
    private static JsonArray groups(final CardPool pool) {
        final Map<String, JsonArray> sections = new LinkedHashMap<>();
        for (final String heading : List.of("Creatures", "Planeswalkers", "Instants", "Sorceries",
                "Artifacts", "Enchantments", "Battles", "Lands")) {
            sections.put(heading, new JsonArray());
        }
        if (pool != null) {
            for (final Map.Entry<PaperCard, Integer> e : pool) {
                final JsonObject c = new JsonObject();
                c.addProperty("name", e.getKey().getName());
                c.addProperty("count", e.getValue());
                c.addProperty("image", e.getKey().getImageKey(false));
                sections.get(heading(e.getKey())).add(c);
            }
        }
        final JsonArray out = new JsonArray();
        for (final Map.Entry<String, JsonArray> e : sections.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            final JsonObject g = new JsonObject();
            g.addProperty("heading", e.getKey());
            g.add("cards", e.getValue());
            out.add(g);
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
    static JsonArray savedSleeveArt() {
        final JsonArray out = new JsonArray();
        final Map<String, Integer> library = SleeveArt.parseLibrary(
                FModel.getPreferences().getPref(forge.localinstance.properties.ForgePreferences.FPref.UI_SLEEVE_ART_LIBRARY));
        for (final Map.Entry<String, Integer> e : library.entrySet()) {
            final JsonObject s = new JsonObject();
            s.addProperty("key", e.getKey());
            s.addProperty("offset", e.getValue());
            out.add(s);
        }
        return out;
    }

    /** Remembers a card-art sleeve alongside the ones desktop has saved, newest last. */
    static void rememberSleeveArt(final String imageKey, final int offset) {
        if (imageKey == null || imageKey.isEmpty()) {
            return;
        }
        final var prefs = FModel.getPreferences();
        final var pref = forge.localinstance.properties.ForgePreferences.FPref.UI_SLEEVE_ART_LIBRARY;
        final Map<String, Integer> library = new LinkedHashMap<>(SleeveArt.parseLibrary(prefs.getPref(pref)));
        library.remove(imageKey);
        library.put(imageKey, SleeveArt.clampOffset(offset));
        prefs.setPref(pref, SleeveArt.formatLibrary(library));
        prefs.save();
    }

    /** Card names matching what has been typed, for the card-art sleeve picker. */
    static JsonArray searchCardNames(final String query, final int limit) {
        final JsonArray out = new JsonArray();
        final String needle = query == null ? "" : query.trim().toLowerCase();
        if (needle.isEmpty()) {
            return out;
        }
        for (final PaperCard card : forge.StaticData.instance().getCommonCards().getUniqueCards()) {
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
    static JsonArray printings(final String name) {
        final JsonArray out = new JsonArray();
        final List<PaperCard> prints = new ArrayList<>(
                forge.StaticData.instance().getCommonCards().getAllCardsNoAlt(name));
        for (final PaperCard card : prints) {
            final JsonObject p = new JsonObject();
            p.addProperty("name", card.getName());
            p.addProperty("edition", card.getEdition());
            p.addProperty("key", card.getImageKey(false));
            out.add(p);
        }
        return out;
    }
}
