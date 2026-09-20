package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.game.GameType;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Match setup before a game starts: a format and the seats that will play it. Seat 0 is the browser's; the
 * rest are played by the host. Everything here is offline, so a seat is something you add rather than an
 * empty slot waiting to be filled.
 */
final class Lobby {
    static final int MAX_SEATS = 4;
    private static final String AI_NAME = "Forge AI";
    /** The formats the vertical slice covers. Commander is a variant; Constructed is the absence of one. */
    private static final List<GameType> FORMATS = List.of(GameType.Constructed, GameType.Commander);

    private final DeckCatalog catalog = new DeckCatalog();
    private final List<SeatState> seats = new ArrayList<>();
    private GameType format = GameType.Constructed;

    private static final class SeatState {
        String name;
        final boolean ai;
        int avatar;
        int sleeve;
        String deckKey;

        SeatState(final String name, final boolean ai, final int avatar, final int sleeve) {
            this.name = name;
            this.ai = ai;
            this.avatar = avatar;
            this.sleeve = sleeve;
        }
    }

    Lobby() {
        seats.add(new SeatState(FModel.getPreferences().getPref(FPref.PLAYER_NAME), false,
                LocalGame.storedIndex(FPref.UI_AVATARS, 0), LocalGame.storedIndex(FPref.UI_SLEEVES, 0)));
        seats.add(aiSeat(1));
    }

    // Numbered past the first, so three of them are told apart in the seat list and in what blocks Play
    private static SeatState aiSeat(final int index) {
        return new SeatState(index > 1 ? AI_NAME + " " + index : AI_NAME, true,
                LocalGame.storedIndex(FPref.UI_AVATARS, index), LocalGame.storedIndex(FPref.UI_SLEEVES, index));
    }

    GameType format() {
        return format;
    }

    /** Every deck this format can be played with, rebuilt because the pool differs per format. */
    JsonObject decks() {
        final JsonObject m = JsonCodec.message("decks");
        m.add("decks", catalog.refresh(format));
        return m;
    }

    JsonObject deckDetails(final String key) {
        final JsonObject details = catalog.details(key, format);
        if (details == null) {
            return null;
        }
        final JsonObject m = JsonCodec.message("deckDetails");
        m.add("deck", details);
        return m;
    }

    JsonObject state() {
        final JsonObject m = JsonCodec.message("lobby");
        m.addProperty("format", format.name());
        final JsonArray formats = new JsonArray();
        for (final GameType t : FORMATS) {
            final JsonObject f = new JsonObject();
            f.addProperty("id", t.name());
            f.addProperty("name", t.toString());
            formats.add(f);
        }
        m.add("formats", formats);
        m.addProperty("maxSeats", MAX_SEATS);
        final JsonArray list = new JsonArray();
        for (final SeatState s : seats) {
            final Deck deck = catalog.deck(s.deckKey);
            final JsonObject j = new JsonObject();
            j.addProperty("name", s.name);
            j.addProperty("ai", s.ai);
            j.addProperty("avatar", s.avatar);
            j.addProperty("sleeve", s.sleeve);
            j.addProperty("deck", s.deckKey);
            j.addProperty("deckName", deck == null ? null : deck.getName());
            j.addProperty("deckSize", deck == null ? 0 : deck.getMain().countAll());
            j.addProperty("problem", DeckCatalog.problem(deck, format));
            // A deck with a card-art sleeve overrides the numbered one, as it does in every other client
            j.addProperty("sleeveArt", deck == null ? "" : deck.getSleeveArtKey());
            j.addProperty("sleeveOffset", deck == null ? 0 : deck.getSleeveArtOffset());
            list.add(j);
        }
        m.add("seats", list);
        final JsonArray problems = new JsonArray();
        for (final String p : problems()) {
            problems.add(p);
        }
        m.add("problems", problems);
        m.addProperty("canStart", problems.isEmpty());
        return m;
    }

    /** What stops the match starting, in the order the seats appear. */
    List<String> problems() {
        final List<String> out = new ArrayList<>();
        if (seats.get(0).name == null || seats.get(0).name.isBlank()) {
            out.add("Enter a name for yourself.");
        }
        for (int i = 0; i < seats.size(); i++) {
            final SeatState s = seats.get(i);
            final String who = i == 0 ? "You have" : s.name + " has";
            final Deck deck = catalog.deck(s.deckKey);
            if (deck == null) {
                out.add(who + " no deck.");
            } else {
                final String problem = DeckCatalog.problem(deck, format);
                if (problem != null) {
                    out.add(deck.getName() + ": " + problem);
                }
            }
        }
        return out;
    }

    void setFormat(final String id) {
        for (final GameType t : FORMATS) {
            if (t.name().equals(id)) {
                if (t != format) {
                    format = t;
                    // A deck legal in one format is rarely legal in another, and its key is not in the new pool
                    catalog.refresh(format);
                    for (final SeatState s : seats) {
                        s.deckKey = null;
                    }
                }
                return;
            }
        }
    }

    void addSeat() {
        if (seats.size() < MAX_SEATS) {
            seats.add(aiSeat(seats.size()));
        }
    }

    /** Removes one seat. Yours is not removable, and two are needed for a match. */
    void removeSeat(final int index) {
        if (index > 0 && index < seats.size() && seats.size() > 2) {
            seats.remove(index);
        }
    }

    void setName(final int index, final String name) {
        if (index >= 0 && index < seats.size() && name != null) {
            seats.get(index).name = name.trim();
        }
    }

    void setDeck(final int index, final String key) {
        if (index >= 0 && index < seats.size()) {
            seats.get(index).deckKey = key;
        }
    }

    void setAvatar(final int index, final int value) {
        if (index >= 0 && index < seats.size() && value >= 0 && value < SkinSprites.avatarCount()) {
            seats.get(index).avatar = value;
        }
    }

    void setSleeve(final int index, final int value) {
        if (index >= 0 && index < seats.size() && value >= 0 && value < SkinSprites.sleeveCount()) {
            seats.get(index).sleeve = value;
        }
    }

    /** Writes a card-art sleeve onto the seat's deck, where every client reads it from. */
    void setSleeveArt(final int index, final String imageKey, final int offset) {
        if (index >= 0 && index < seats.size()) {
            catalog.saveSleeveArt(seats.get(index).deckKey, imageKey, offset);
        }
    }

    String playerName() {
        return seats.get(0).name;
    }

    /** The seats as the host takes them, in the order they were set up. */
    List<LocalGame.Seat> toSeats() {
        final List<LocalGame.Seat> out = new ArrayList<>();
        for (final SeatState s : seats) {
            out.add(new LocalGame.Seat(s.name, s.ai, s.avatar, s.sleeve, catalog.deck(s.deckKey)));
        }
        return out;
    }

    /** Saves the avatars and sleeves the seats chose, which the desktop lobby shares. */
    void saveLooks() {
        FModel.getPreferences().setPref(FPref.UI_AVATARS, joined(true));
        FModel.getPreferences().setPref(FPref.UI_SLEEVES, joined(false));
        FModel.getPreferences().setPref(FPref.PLAYER_NAME, playerName());
        FModel.getPreferences().save();
    }

    private String joined(final boolean avatars) {
        final StringBuilder sb = new StringBuilder();
        for (final SeatState s : seats) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(avatars ? s.avatar : s.sleeve);
        }
        return sb.toString();
    }
}
