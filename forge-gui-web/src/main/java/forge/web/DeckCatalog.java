package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckProxy;
import forge.deck.DeckSection;
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

    private final Map<String, Entry> byKey = new ConcurrentHashMap<>();

    private record Entry(DeckProxy proxy, String source) {
    }

    /** Rebuilds the catalogue for a format and returns every deck in it. */
    JsonArray refresh(final GameType format) {
        byKey.clear();
        final JsonArray out = new JsonArray();
        final boolean commander = format == GameType.Commander;
        add(out, format, commander ? DeckProxy.getAllCommanderDecks() : DeckProxy.getAllConstructedDecks(), MINE);
        add(out, format, commander ? DeckProxy.getAllCommanderPreconDecks()
                : DeckProxy.getAllPreconstructedDecks(QuestController.getPrecons()), PRECON);
        return out;
    }

    Deck deck(final String key) {
        final Entry e = key == null ? null : byKey.get(key);
        return e == null ? null : e.proxy().getDeck();
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
        out.add("main", groups(deck.get(DeckSection.Main)));
        out.add("sideboard", cards(deck.get(DeckSection.Sideboard)));
        out.addProperty("sleeveArt", deck.getSleeveArtKey());
        out.addProperty("sleeveOffset", deck.getSleeveArtOffset());
        return out;
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
