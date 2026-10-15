package forge.web.campaign;

import forge.game.GameView;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.match.PreparedMatch;
import forge.web.deck.DeckSession;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.FromBrowser.CatalogueQuery;
import forge.web.protocol.FromBrowser.TradePick;
import forge.web.protocol.ToBrowser.CampaignPrefs;
import forge.web.protocol.ToBrowser.CampaignResult;
import forge.web.protocol.ToBrowser.CampaignStats;
import forge.web.protocol.ToBrowser.CataloguePage;
import forge.web.protocol.ToBrowser.Reward;
import forge.web.protocol.ToBrowser.Trading;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

/**
 * A campaign mode as a session sees it. One per session and mode, over Forge's one model of that mode.
 * Only the host's session opens a campaign, and it makes every call on its campaign thread ({@code WebSession.onCampaign}),
 * so nothing here takes a lock. {@link #current} only reads a preference and may be called from any thread.
 */
public interface Campaign {
    /** What a mode's own pages need of the session. */
    interface Host {
        Record error(String text);

        DeckSession decks();

        /** The campaign made a save and opened it, so the session's place follows. */
        void opened(String save);

        /** Starts a match the campaign built. failed undoes what preparing it did. */
        void startMatch(Supplier<PreparedMatch> prepare, Runnable failed);

        /** Opens a draft whose packs are already dealt, as a quest tournament's are. */
        void startDraft(BoosterDraft draft);
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
    Reward reward();

    /** The reward has been shown, and the campaign goes on to whatever it leads to. */
    void claim(Host host);

    /** A game of the campaign's match ended: recorded and rewarded here. hostGame is the host's own view, which alone knows the match. */
    CampaignResult gameOver(GameView hostGame);

    /** The game's result has been sent, so what the mode does next, such as asking about a reward, comes after the result screen. */
    default void resultSent() {
    }

    /** The result of the game just ended, while its match is still open, or null. */
    CampaignResult result();

    /** The match is left: its result goes with it. */
    void left();

    CataloguePage cards(CatalogueQuery q);

    /** The lists whose cards are traded, by size. */
    Trading trading();

    /** Trades picks out of the list of that source. Answers why it cannot, or null when it is done. */
    String trade(String source, List<TradePick> picks);

    CampaignStats stats(String scope);

    /** The mode's preferences. problem is why the last change was refused, if it was. */
    CampaignPrefs prefs(String problem);

    /** Sets a preference and saves. Answers why it cannot, or null when it is set. */
    String setPref(String key, String value);

    void resetPrefs();

    /** A command of the mode's own. save is null on the list of saved games. */
    void handle(BrowserChannel channel, Record command, String save, Host host);

    /** Whether file is the saves folder's own child of exactly that name, however its path was written. Save names come from the browser, so one with a path in it must not reach a file to open, rename or delete outside the saves. */
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
