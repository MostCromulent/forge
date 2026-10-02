package forge.web;

import com.google.gson.JsonObject;
import forge.game.GameView;
import forge.gamemodes.match.PreparedMatch;
import forge.web.FromBrowser.CatalogueQuery;
import forge.web.ToBrowser.CataloguePage;

import java.util.List;
import java.util.function.Supplier;

/** A campaign mode as a session sees it. One per session and mode, over Forge's one model of that mode. */
interface Campaign {
    /** What a mode's own pages need of the session. */
    interface Host {
        Record error(String text);

        DeckSession decks();

        /** The campaign made a save and opened it, so the session's place follows. */
        void opened(String save);

        /** Starts a match the campaign built. failed undoes what preparing it did. */
        void startMatch(Supplier<PreparedMatch> prepare, Runnable failed);
    }

    /** The saved games, as the mode's own message. Reading them is slow. */
    Record saves();

    /** The save played last, or null. */
    String current();

    /** Makes a save the one Forge has open, and answers its name; null when there is none of that name or it cannot be read. */
    String open(String save);

    /** Why a save cannot be renamed, or null when it is done. */
    String rename(String name, String to);

    void delete(String name);

    /** What an open campaign's pages are drawn from, sent whenever they may have changed. */
    List<Record> page();

    /** What the last match gave and has yet to be shown, or null. */
    Record reward();

    /** A game of the campaign's match ended: recorded and rewarded here. hostGame is the host's own view, which alone knows the match. */
    Record gameOver(GameView hostGame);

    /** The result of the game just ended, while its match is still open, or null. */
    Record result();

    /** The match is left: its result goes with it. */
    void left();

    CataloguePage cards(CatalogueQuery q);

    /** A message of the mode's own. save is null on the list of saved games. */
    void handle(BrowserChannel channel, JsonObject msg, String save, Host host);
}
