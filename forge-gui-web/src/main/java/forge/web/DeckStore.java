package forge.web;

import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.game.GameType;
import forge.model.FModel;
import forge.util.storage.IStorage;

import java.util.Collection;

/** Where the host keeps each format's decks, and the rules a deck's name has to follow to be saved there. */
final class DeckStore {
    static final int MAX_NAME = 60;
    private static final String FORBIDDEN = "\\/:*?\"<>|";

    private DeckStore() {
    }

    /** The storage a format's decks are kept in. Tests pass their own, so they never touch the player's decks. */
    interface Storages {
        IStorage<Deck> of(GameType format);
    }

    static Storages real() {
        return format -> switch (family(format)) {
            case Commander -> FModel.getDecks().getCommander();
            case Oathbreaker -> FModel.getDecks().getOathbreaker();
            case Brawl -> FModel.getDecks().getBrawl();
            case TinyLeaders -> FModel.getDecks().getTinyLeaders();
            default -> FModel.getDecks().getConstructed();
        };
    }

    /**
     * The format whose decks a deck is listed with: each commander format has its own, limited decks are Draft's, and
     * everything else is Constructed's. Limited decks are stored as their pools are, not in a folder of their own.
     */
    static GameType family(final GameType format) {
        return switch (format) {
            case Commander, Oathbreaker, Brawl, TinyLeaders -> format;
            case Draft, Sealed -> GameType.Draft;
            default -> GameType.Constructed;
        };
    }

    /** The same, for the format a deck file records. */
    static GameType family(final DeckFormat format) {
        return switch (format) {
            case Commander -> GameType.Commander;
            case Oathbreaker -> GameType.Oathbreaker;
            case Brawl -> GameType.Brawl;
            case TinyLeaders -> GameType.TinyLeaders;
            case Limited -> GameType.Draft;
            default -> GameType.Constructed;
        };
    }

    /** Why a name can't be a deck's, or null when it can. */
    static String nameProblem(final String name) {
        if (name == null || name.isBlank()) {
            return "Give the deck a name.";
        }
        if (name.trim().length() > MAX_NAME) {
            return "Keep the name to " + MAX_NAME + " characters.";
        }
        for (final char c : FORBIDDEN.toCharArray()) {
            if (name.indexOf(c) >= 0) {
                return "A deck's name can't contain \\ / : * ? \" < > |.";
            }
        }
        return null;
    }

    /** Whether two names save to the same file. Windows ignores case in file names, so this does too. */
    static boolean sameFile(final String a, final String b) {
        return new Deck(a).getBestFileName().equalsIgnoreCase(new Deck(b).getBestFileName());
    }

    /** The name of the deck already saved where this name would go, or null. */
    static String taken(final IStorage<Deck> storage, final String name) {
        return taken(storage.getItemNames(), name);
    }

    private static String taken(final Collection<String> names, final String name) {
        for (final String existing : names) {
            if (sameFile(existing, name)) {
                return existing;
            }
        }
        return null;
    }

    /**
     * The wanted name, or "wanted (2)", "wanted (3)" and so on, whichever saves to a file no other deck uses. mine is the
     * name the caller already saves under, which it may keep; null when it saves nothing yet.
     */
    static String freeName(final IStorage<Deck> storage, final String wanted, final String mine) {
        return freeName(storage.getItemNames(), wanted, mine);
    }

    /** As above, among any names: a guest's decks are kept in its browser, not in storage. */
    static String freeName(final Collection<String> names, final String wanted, final String mine) {
        String candidate = wanted;
        for (int n = 2; ; n++) {
            final String existing = taken(names, candidate);
            if (existing == null || (mine != null && sameFile(existing, mine))) {
                return candidate;
            }
            candidate = wanted + " (" + n + ")";
        }
    }
}
