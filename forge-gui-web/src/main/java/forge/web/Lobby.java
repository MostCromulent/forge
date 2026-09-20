package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.game.GameType;
import forge.gamemodes.match.GameLobby;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Match setup, read from the lobby the engine keeps. The browser always shows its own client's view of it, so
 * one screen serves a game you host and one you have joined; what differs is only what you may change. Your
 * own seat travels as an update from a client, and the rest of the table is the host's to set.
 */
final class Lobby {
    static final int MAX_SEATS = 4;
    private static final String AI_NAME = "Forge AI";
    /** The formats the vertical slice covers. Commander is a variant; Constructed is the absence of one. */
    private static final List<GameType> FORMATS = List.of(GameType.Constructed, GameType.Commander);

    private final DeckCatalog catalog = new DeckCatalog();
    private final LocalGame local;
    /** Which deck each seat was given, by catalogue key: the lobby slot holds the deck, this holds the choice. */
    private final List<String> deckKeys = new ArrayList<>();
    /** True once the game was opened for others to join, which is when a link is worth showing. */
    private boolean shareable;

    Lobby(final LocalGame local) {
        this.local = local;
    }

    void setShareable(final boolean value) {
        shareable = value;
    }

    private GameLobby view() {
        return local.clientLobby();
    }

    private ServerGameLobby host() {
        return local.hostedLobby();
    }

    /**
     * The format, read from the variants the lobby carries rather than from its game type. A lobby's game type
     * is a plain field that its serialised data leaves out, so on a client it never moves off Constructed.
     */
    GameType format() {
        final GameLobby lobby = view();
        if (lobby != null) {
            for (final GameType variant : FORMATS) {
                if (variant != GameType.Constructed && lobby.hasVariant(variant)) {
                    return variant;
                }
            }
        }
        return GameType.Constructed;
    }

    /** Every deck this format can be played with, rebuilt because the pool differs per format. */
    JsonObject decks() {
        final JsonObject m = JsonCodec.message("decks");
        m.add("decks", catalog.refresh(format()));
        m.add("cardFormats", DeckCatalog.cardFormats());
        return m;
    }

    /** Downloads a net deck category and adds it to the catalogue. Core asks which one through the browser. */
    JsonObject loadNetDecks() {
        catalog.loadNetDecks(format());
        return decks();
    }

    JsonObject deckDetails(final String key) {
        final JsonObject details = catalog.details(key, format());
        if (details == null) {
            return null;
        }
        final JsonObject m = JsonCodec.message("deckDetails");
        m.add("deck", details);
        return m;
    }

    JsonObject state() {
        final JsonObject m = JsonCodec.message("lobby");
        final GameLobby lobby = view();
        m.addProperty("open", lobby != null);
        if (lobby == null) {
            return m;
        }
        m.addProperty("host", local.isHost());
        m.addProperty("mySeat", local.webSeat());
        m.addProperty("shareable", shareable);
        m.addProperty("format", format().name());
        final JsonArray formats = new JsonArray();
        for (final GameType t : FORMATS) {
            final JsonObject f = new JsonObject();
            f.addProperty("id", t.name());
            f.addProperty("name", t.toString());
            formats.add(f);
        }
        m.add("formats", formats);
        m.addProperty("maxSeats", MAX_SEATS);
        final JsonArray seats = new JsonArray();
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            seats.add(seat(lobby, i));
        }
        m.add("seats", seats);
        final JsonArray problems = new JsonArray();
        for (final String p : problems()) {
            problems.add(p);
        }
        m.add("problems", problems);
        // Only the machine running the game can start it; everyone else waits on the host
        m.addProperty("canStart", local.isHost() && problems.isEmpty());
        return m;
    }

    private JsonObject seat(final GameLobby lobby, final int index) {
        final LobbySlot slot = lobby.getSlot(index);
        final Deck deck = deckAt(index);
        final JsonObject j = new JsonObject();
        j.addProperty("name", slot.getName());
        j.addProperty("type", slot.getType().name());
        j.addProperty("mine", index == local.webSeat());
        // Your own seat wherever you are, and the computer's seats if you run the game. Another player's is theirs.
        j.addProperty("mayEdit",
                index == local.webSeat() || (local.isHost() && slot.getType() == LobbySlotType.AI));
        j.addProperty("ready", slot.isReady());
        j.addProperty("avatar", slot.getAvatarIndex());
        j.addProperty("sleeve", slot.getSleeveIndex());
        j.addProperty("deck", key(index));
        j.addProperty("deckName", deck == null ? null : deck.getName());
        j.addProperty("deckSize", deck == null ? 0 : deck.getMain().countAll());
        j.addProperty("colors", deck == null ? "" : DeckCatalog.colors(deck));
        j.addProperty("problem", deck == null ? null : DeckCatalog.problem(deck, format()));
        // A deck with a card-art sleeve overrides the numbered one, as it does in every other client
        j.addProperty("sleeveArt", deck == null ? "" : deck.getSleeveArtKey());
        j.addProperty("sleeveOffset", deck == null ? 0 : deck.getSleeveArtOffset());
        return j;
    }

    private String key(final int index) {
        return index >= 0 && index < deckKeys.size() ? deckKeys.get(index) : null;
    }

    private Deck deckAt(final int index) {
        return catalog.deck(key(index));
    }

    /** What stops the match starting, in the order the seats appear. */
    List<String> problems() {
        final List<String> out = new ArrayList<>();
        final GameLobby lobby = view();
        if (lobby == null) {
            return out;
        }
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            final LobbySlot slot = lobby.getSlot(i);
            if (slot.getType() == LobbySlotType.OPEN) {
                out.add("A seat is still open.");
                continue;
            }
            final String who = i == local.webSeat() ? "You have" : slot.getName() + " has";
            final Deck deck = deckAt(i);
            if (deck == null) {
                out.add(who + " no deck.");
                continue;
            }
            final String problem = DeckCatalog.problem(deck, format());
            if (problem != null) {
                out.add(deck.getName() + ": " + problem);
            }
            if (!slot.isReady()) {
                out.add(slot.getName() + " is not ready.");
            }
        }
        return out;
    }

    /** Drops the deck choices, because a new lobby's slots hold none and the two must not disagree. */
    void forget() {
        deckKeys.clear();
    }

    /** The format belongs to the game, so only the host sets it. */
    void setFormat(final String id) {
        final ServerGameLobby lobby = host();
        if (lobby == null) {
            return;
        }
        for (final GameType wanted : FORMATS) {
            if (!wanted.name().equals(id) || wanted == format()) {
                continue;
            }
            // Constructed is the absence of a format variant rather than one of its own
            for (final GameType other : FORMATS) {
                if (other != GameType.Constructed) {
                    lobby.removeVariant(other);
                }
            }
            if (wanted != GameType.Constructed) {
                lobby.applyVariant(wanted);
            }
            // A deck legal in one format is rarely legal in another, and its key is not in the new pool
            deckKeys.clear();
            for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
                lobby.getSlot(i).setDeck(null);
            }
            local.pushLobby();
            return;
        }
    }

    void addSeat() {
        final ServerGameLobby lobby = host();
        if (lobby != null && lobby.getNumberOfSlots() < MAX_SEATS) {
            lobby.addSlot();
            aiSeat(lobby.getNumberOfSlots() - 1);
        }
    }

    /** Leaves a seat for someone to join, rather than filling it with an AI. */
    void openSeat(final int index) {
        final ServerGameLobby lobby = host();
        if (lobby != null && index != local.webSeat() && index < lobby.getNumberOfSlots()) {
            final LobbySlot slot = lobby.getSlot(index);
            slot.setType(LobbySlotType.OPEN);
            slot.setName(null);
            slot.setIsReady(false);
            local.pushLobby();
        }
    }

    void aiSeat(final int index) {
        final ServerGameLobby lobby = host();
        if (lobby != null && index != local.webSeat() && index < lobby.getNumberOfSlots()) {
            final LobbySlot slot = lobby.getSlot(index);
            slot.setType(LobbySlotType.AI);
            slot.setName(index > 1 ? AI_NAME + " " + index : AI_NAME);
            slot.setIsReady(true);
            local.pushLobby();
        }
    }

    void removeSeat(final int index) {
        final ServerGameLobby lobby = host();
        if (lobby != null && index != local.webSeat() && lobby.getNumberOfSlots() > 2) {
            lobby.removeSlot(index);
            if (index < deckKeys.size()) {
                deckKeys.remove(index);
            }
        }
    }

    void setName(final int index, final String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (index == local.webSeat()) {
            local.updateOwnSeat(UpdateLobbyPlayerEvent.nameUpdate(name.trim()));
        } else if (host() != null) {
            host().getSlot(index).setName(name.trim());
            local.pushLobby();
        }
    }

    void setDeck(final int index, final String key) {
        while (deckKeys.size() <= index) {
            deckKeys.add(null);
        }
        deckKeys.set(index, key);
        final Deck deck = catalog.deck(key);
        if (index == local.webSeat()) {
            local.updateOwnSeat(UpdateLobbyPlayerEvent.deckUpdate(deck));
            // A seat with a deck has said all it needs to, so readiness follows the deck rather than a button
            local.updateOwnSeat(UpdateLobbyPlayerEvent.isReadyUpdate(deck != null));
        } else if (host() != null) {
            host().getSlot(index).setDeck(deck);
            local.pushLobby();
        }
    }

    void setAvatar(final int index, final int value) {
        if (value < 0 || value >= SkinSprites.avatarCount()) {
            return;
        }
        if (index == local.webSeat()) {
            local.updateOwnSeat(UpdateLobbyPlayerEvent.avatarUpdate(value));
        } else if (host() != null) {
            host().getSlot(index).setAvatarIndex(value);
            local.pushLobby();
        }
    }

    void setSleeve(final int index, final int value) {
        if (value < 0 || value >= SkinSprites.sleeveCount()) {
            return;
        }
        if (index == local.webSeat()) {
            local.updateOwnSeat(UpdateLobbyPlayerEvent.sleeveUpdate(value));
        } else if (host() != null) {
            host().getSlot(index).setSleeveIndex(value);
            local.pushLobby();
        }
    }

    void setReady(final boolean ready) {
        local.updateOwnSeat(UpdateLobbyPlayerEvent.isReadyUpdate(ready));
    }

    /** Writes a card-art sleeve onto the seat's deck, where every client reads it from. */
    void setSleeveArt(final int index, final String imageKey, final int offset) {
        catalog.saveSleeveArt(key(index), imageKey, offset);
    }

    /** Saves the avatars and sleeves the seats chose, which the desktop lobby shares. */
    void saveLooks() {
        final GameLobby lobby = view();
        // Several browsers share one set of preferences, so only the machine running the game writes them
        if (lobby == null || !local.isHost()) {
            return;
        }
        final StringBuilder avatars = new StringBuilder();
        final StringBuilder sleeves = new StringBuilder();
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            if (i > 0) {
                avatars.append(',');
                sleeves.append(',');
            }
            avatars.append(lobby.getSlot(i).getAvatarIndex());
            sleeves.append(lobby.getSlot(i).getSleeveIndex());
        }
        FModel.getPreferences().setPref(FPref.UI_AVATARS, avatars.toString());
        FModel.getPreferences().setPref(FPref.UI_SLEEVES, sleeves.toString());
        final int mine = local.webSeat();
        if (mine >= 0 && lobby.getSlot(mine).getName() != null) {
            FModel.getPreferences().setPref(FPref.PLAYER_NAME, lobby.getSlot(mine).getName());
        }
        FModel.getPreferences().save();
    }
}
