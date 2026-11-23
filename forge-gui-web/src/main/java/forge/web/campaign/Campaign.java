package forge.web.campaign;

import forge.game.GameView;
import forge.gamemodes.match.PreparedMatch;
import forge.web.Campaigns;
import forge.web.deck.DeckWorkspace;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.CampaignProtocol.*;
import forge.web.protocol.FromBrowser.CatalogueQuery;
import forge.web.protocol.ToBrowser.CataloguePage;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

/**
 * A campaign mode as a session sees it, over Forge's single model of that mode.
 * Only the host's session opens a campaign. It makes every call on its campaign thread ({@code CampaignSession.run}),
 * so nothing here takes a lock, except in a mode that {@link #runsItself}. {@link #current} reads only a preference and the saves folder,
 * so it may be called from any thread, and so may {@link #handles}, {@link #anywhere}, {@link #becameHost}, {@link #result} and {@link #leavingMatch}.
 */
public interface Campaign {
    /** What a mode's own pages need from the session. */
    interface Host {
        Record error(String text);

        DeckWorkspace decks();

        /** The campaign made a save and opened it, so the session moves to that save. */
        void opened(String save);

        /**
         * Starts a match the campaign built, if the browser is still at that mode and save. failed undoes what preparing it did.
         * It runs once, on this thread or the host UI thread, whenever the match does not start.
         */
        void startMatch(String mode, String save, Supplier<PreparedMatch> prepare, Runnable failed);

        Campaigns.Host session();

        /** Runs a job on the campaign thread. */
        void run(Runnable job);

        /** Sends what the campaign stage draws again. */
        void show();
    }

    /** Whether a command is one of the mode's own. */
    boolean handles(Record command);

    /**
     * Whether a command of the mode's own is taken wherever the browser is, and not only on the mode's pages. It is given no save.
     * It runs on the campaign thread, also in a mode that {@link #runsItself}, and the mode checks for itself that the host sent it.
     */
    default boolean anywhere(final Record command) {
        return false;
    }

    /** The session took the host's seat. */
    default void becameHost(final Host host) {
    }

    /**
     * Whether the mode has a thread of its own. If so its own commands, and every deck command made on its pages, go to {@link #handle}
     * on the thread they arrive on, and a reload sends its pages on that thread. None of these may wait.
     */
    default boolean runsItself() {
        return false;
    }

    /** The saved games, as the mode's own message. Reading them is slow. */
    default Record saves() {
        throw new UnsupportedOperationException();
    }

    /** What is drawn while no save is open. */
    default List<Record> home() {
        return List.of(saves());
    }

    /** The save played last, or null. */
    String current();

    /** Asked before the player leaves this mode or its open save, and before this mode opens another save or its home. save is null on the pages shown with no save open. */
    default boolean mayLeave(final String save) {
        return true;
    }

    /** A save, or with null the pages shown with no save open, is about to open. It opens when then is run, which may be later and on another thread. */
    default void opening(final String save, final Runnable then) {
        then.run();
    }

    /** Makes a save the one Forge has open, and returns its name. Returns null when no save has that name or it cannot be read. */
    String open(String save);

    /** Renames a save. Returns why it cannot be renamed, or null when it is done. */
    default String rename(final String name, final String to) {
        return null;
    }

    default void delete(final String name) {
    }

    /** The data an open campaign's pages are drawn from. It is sent whenever it may have changed. */
    List<Record> page();

    /** What the last match gave that has not been shown yet, or null. */
    default Reward reward() {
        return null;
    }

    /** Called when the reward has been shown. The campaign then goes on to whatever comes next. */
    default void claim(final Host host) {
    }

    /** Whether a match of this mode has only one game, which cannot be continued or restarted. */
    default boolean oneGame() {
        return false;
    }

    /** A game of the campaign's match ended. It is recorded and rewarded here. hostGame is the host's own view, the only view that knows the match. */
    CampaignResult gameOver(GameView hostGame);

    /** Called when the game's result has been sent, so whatever the mode does next, such as asking about a reward, comes after the result screen. */
    default void resultSent() {
    }

    /** The result of the game just ended, while its match is still open, or null. */
    default CampaignResult result() {
        return null;
    }

    /** The player is leaving the match, whose table is still open. Called on the thread that closes it. It may be called again before {@link #left}, with the table closed. */
    default void leavingMatch() {
    }

    /**
     * The match was left. Its result is dropped with it. choice is the action of the result's button that was pressed, if it was one of the mode's own, or null.
     * It comes unchecked from the browser, so the mode compares it with the buttons of its own result.
     */
    void left(String choice);

    default CataloguePage cards(final CatalogueQuery q) {
        return null;
    }

    /** The lists whose cards are traded, by size. */
    default Trading trading() {
        return null;
    }

    /** Trades picks out of the list of that source. Returns why it cannot, or null when it is done. */
    default String trade(final String source, final List<TradePick> picks) {
        return null;
    }

    default CampaignStats stats(final String scope) {
        return null;
    }

    /** The mode's preferences. problem is why the last change was refused, if it was. */
    default CampaignPrefs prefs(final String problem) {
        return null;
    }

    /** Sets a preference and saves. Returns why it cannot, or null when it is set. */
    default String setPref(final String key, final String value) {
        return null;
    }

    default void resetPrefs() {
    }

    /** A command of the mode's own. save is null when none is open. */
    void handle(BrowserChannel channel, Record command, String save, Host host);

    /** Whether file is a direct child of the saves folder with exactly that name, however its path was written. Save names come from the browser, so a name with a path in it must not reach a file outside the saves to open, rename or delete. */
    static boolean isSaveNamed(final String savesDir, final File file, final String child) {
        try {
            final File canonical = file.getCanonicalFile();
            return new File(savesDir).getCanonicalFile().equals(canonical.getParentFile()) && canonical.getName().equals(child);
        } catch (final IOException e) {
            return false;
        }
    }

    /** The day a save was last written, as the browser shows it (yyyy-mm-dd). */
    static String savedOn(final File file) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(file.lastModified()), ZoneId.systemDefault()).toString();
    }
}
