package forge.web;

import forge.deck.Deck;
import forge.game.GameFormat;
import forge.game.GameType;
import forge.gamemodes.match.GameLobby;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.NameGenerator;
import forge.web.ToBrowser.DeckDetails;
import forge.web.ToBrowser.DeckDetailsMessage;
import forge.web.ToBrowser.Decks;
import forge.web.ToBrowser.Format;
import forge.web.ToBrowser.LegalityGroup;
import forge.web.ToBrowser.LobbyMessage;
import forge.web.ToBrowser.LobbyTable;
import forge.web.ToBrowser.Seat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Match setup, read from the lobby the engine keeps. The browser always shows its own client's view of it, so
 * one screen serves a game you host and one you have joined; what differs is only what you may change. Your
 * own seat travels as an update from a client, and the rest of the table is the host's to set.
 */
final class Lobby {
    static final int MAX_SEATS = 4;
    /** The formats on offer, as desktop orders them. Each but Constructed is a variant; Constructed is the absence of one. */
    private static final List<GameType> FORMATS = List.of(GameType.Constructed, GameType.Commander,
            GameType.Oathbreaker, GameType.Brawl, GameType.TinyLeaders);

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

    /**
     * The table as this browser sees it. The host reads the server's own lobby: its client's copy trails the server
     * by an update, and reading that could show a seat as it was a moment ago, or refuse to start a match because a
     * seat did not look ready yet. A guest has only its copy.
     */
    private GameLobby view() {
        final GameLobby hosted = local.hostedLobby();
        return hosted != null ? hosted : local.clientLobby();
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

    /** The card pool every deck must come from, or null. Only Constructed has one. */
    GameFormat legality() {
        final GameLobby lobby = view();
        final String name = lobby == null ? null : lobby.getCardPool();
        return name == null ? null : FModel.getFormats().getFormat(name);
    }

    /** The Legality belongs to the game, so only the host sets it, and only for Constructed. */
    void setLegality(final String name) {
        final ServerGameLobby lobby = host();
        if (lobby == null || format() != GameType.Constructed) {
            return;
        }
        final boolean known = name != null && FModel.getFormats().getFormat(name) != null;
        lobby.setCardPool(known ? name : null);
    }

    /** The pools on offer, grouped as Forge's format files group them. */
    private static List<LegalityGroup> legalities() {
        final var formats = FModel.getFormats();
        final List<LegalityGroup> out = new ArrayList<>();
        out.add(new LegalityGroup("Sanctioned", names(formats.getSanctionedList())));
        out.add(new LegalityGroup("Casual", names(formats.getCasualList())));
        out.add(new LegalityGroup("Archived", names(formats.getArchivedList())));
        out.add(new LegalityGroup("Block", names(formats.getBlockList())));
        // Which formats load depends on the install, so a heading can come up empty
        out.removeIf(g -> g.formats().isEmpty());
        return out;
    }

    private static List<String> names(final Iterable<GameFormat> formats) {
        final List<String> out = new ArrayList<>();
        for (final GameFormat f : formats) {
            out.add(f.getName());
        }
        return out;
    }

    /** Every deck this format can be played with, rebuilt because the pool differs per format. */
    Decks decks() {
        final Decks out;
        final boolean newPool;
        synchronized (DeckCatalog.DECKS) {
            final GameType format = format();
            final GameFormat legality = legality();
            final String legalityName = legality == null ? null : legality.getName();
            // A deck key names a deck in one format's lists, so it means nothing in another
            if (seenFormat != null && format != seenFormat) {
                deckKeys.clear();
            }
            newPool = format == seenFormat && !Objects.equals(legalityName, seenLegality);
            seenFormat = format;
            seenLegality = legalityName;
            out = new Decks(catalog.refresh(format, legality), DeckCatalog.cardFormats(), legalityName);
        }
        if (newPool) {
            dealGeneratorsAgain();
        }
        return out;
    }

    /**
     * A generated deck was built from the card pool of its day, and its slot still holds that deck. With a new pool,
     * the seats this browser deals for are dealt the same generator again, now built from the new pool.
     */
    private void dealGeneratorsAgain() {
        final GameLobby lobby = view();
        for (int i = 0; lobby != null && i < deckKeys.size() && i < lobby.getNumberOfSlots(); i++) {
            final String key = deckKeys.get(i);
            final boolean mine = i == local.webSeat() || (host() != null && lobby.getSlot(i).getType() == LobbySlotType.AI);
            if (key != null && key.startsWith("gen:") && mine) {
                setDeck(i, key);
            }
        }
    }

    /** The deck behind a catalogue key, for tests in this package. */
    Deck deckForTest(final String key) {
        return catalog.deck(key);
    }

    /** Downloads a net deck category and adds it to the catalogue. Core asks which one through the browser. */
    Decks loadNetDecks() {
        catalog.loadNetDecks(format());
        return decks();
    }

    DeckDetailsMessage deckDetails(final String key) {
        final DeckDetails details = catalog.details(key, format());
        return details == null ? null : new DeckDetailsMessage(details);
    }

    LobbyMessage state() {
        synchronized (DeckCatalog.DECKS) {
            final GameLobby lobby = view();
            if (lobby == null) {
                return new LobbyMessage(null);
            }
            final List<Format> formats = new ArrayList<>();
            for (final GameType t : FORMATS) {
                formats.add(new Format(t.name(), t.toString()));
            }
            final List<Seat> seats = new ArrayList<>();
            for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
                seats.add(seat(lobby, i));
            }
            final List<String> problems = problems();
            final GameFormat legality = legality();
            // Only the machine running the game can start it; everyone else waits on the host
            return new LobbyMessage(new LobbyTable(local.isHost(), local.webSeat(), shareable, format().name(), formats,
                    legality == null ? null : legality.getName(), legalities(),
                    MAX_SEATS, seats, problems, local.isHost() && problems.isEmpty()));
        }
    }

    private Seat seat(final GameLobby lobby, final int index) {
        final LobbySlot slot = lobby.getSlot(index);
        final Deck deck = deckAt(index);
        return new Seat(slot.getName(), slot.getType().name(), index == local.webSeat(),
                // Your own seat wherever you are, and the computer's seats if you run the game. Another player's is theirs.
                index == local.webSeat() || (local.isHost() && slot.getType() == LobbySlotType.AI),
                slot.isReady(), slot.getAvatarIndex(), slot.getSleeveIndex(), key(index),
                deck == null ? null : deck.getName(),
                deck == null ? 0 : deck.getMain().countAll(),
                deck == null ? "" : DeckCatalog.colors(deck),
                deck == null ? null : DeckCatalog.problem(deck, format(), legality()),
                // A deck with a card-art sleeve overrides the numbered one, as it does in every other client
                deck == null ? "" : deck.getSleeveArtKey(),
                deck == null ? 0 : deck.getSleeveArtOffset());
    }

    private String key(final int index) {
        return index >= 0 && index < deckKeys.size() ? deckKeys.get(index) : null;
    }

    /**
     * The deck at a seat. A seat this browser chose for is found by its key; another player's deck arrives
     * with their seat, because their catalog is not this one.
     */
    private Deck deckAt(final int index) {
        final Deck chosen = catalog.deck(key(index));
        if (chosen != null) {
            return chosen;
        }
        final GameLobby lobby = view();
        return lobby != null && index < lobby.getNumberOfSlots() ? lobby.getSlot(index).getDeck() : null;
    }

    /** What stops the match starting, in the order the seats appear. */
    List<String> problems() {
        synchronized (DeckCatalog.DECKS) {
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
                final String problem = DeckCatalog.problem(deck, format(), legality());
                if (problem != null) {
                    out.add(deck.getName() + ": " + problem);
                }
                if (!slot.isReady()) {
                    out.add(slot.getName() + " is not ready.");
                }
            }
            return out;
        }
    }

    /** Drops the deck choices, because a new lobby's slots hold none and the two must not disagree. */
    void forget() {
        deckKeys.clear();
        seenFormat = null;
        seenLegality = null;
    }

    /** The format and Legality this browser's deck list was last built for, recorded by {@link #decks()}. */
    private GameType seenFormat;
    private String seenLegality;

    /** True when the format or Legality differs from the one the last deck list was built for. */
    boolean restrictionsChanged() {
        synchronized (DeckCatalog.DECKS) {
            final GameFormat legality = legality();
            return format() != seenFormat || !Objects.equals(legality == null ? null : legality.getName(), seenLegality);
        }
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
            // Cleared first, so no update ever announces another format still holding a Constructed card pool
            if (wanted != GameType.Constructed) {
                lobby.setCardPool(null);
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
            slot.setName(computerName(lobby));
            slot.setIsReady(true);
            local.pushLobby();
        }
    }

    /** A name for the computer as desktop gives one: random, and not one already at the table. */
    static String computerName(final GameLobby lobby) {
        return NameGenerator.getRandomName("Any", "Any", seatNames(lobby, -1));
    }

    private static List<String> seatNames(final GameLobby lobby, final int except) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            if (i != except && lobby.getSlot(i).getName() != null) {
                out.add(lobby.getSlot(i).getName());
            }
        }
        return out;
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

    /** The names the computer plays under at this table, which a person may not also take. */
    List<String> computerNames() {
        final List<String> out = new ArrayList<>();
        final GameLobby lobby = view();
        if (lobby != null) {
            for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
                final LobbySlot slot = lobby.getSlot(i);
                if (slot.getType() == LobbySlotType.AI && slot.getName() != null) {
                    out.add(slot.getName());
                }
            }
        }
        return out;
    }

    void setName(final int index, final String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (index == local.webSeat()) {
            local.updateOwnSeat(UpdateLobbyPlayerEvent.nameUpdate(name.trim()));
        } else if (host() != null && index < host().getNumberOfSlots()
                && host().getSlot(index).getType() == LobbySlotType.AI
                && seatNames(host(), index).stream().noneMatch(name.trim()::equalsIgnoreCase)) {
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
