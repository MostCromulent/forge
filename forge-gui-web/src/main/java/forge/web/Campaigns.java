package forge.web;

import forge.game.GameView;
import forge.gamemodes.limited.BoosterDraft;
import forge.web.WebSession.InCampaign;
import forge.web.WebSession.Playing;
import forge.web.WebSession.Stage;
import forge.web.deck.DeckSession;
import forge.web.limited.DraftOwner;
import forge.web.match.LocalGame;
import forge.web.match.WebGuiGame;
import forge.web.protocol.BrowserChannel;

import java.util.Map;
import java.util.function.Consumer;

/**
 * One session's campaign modes, which live in forge.web.campaign so that a build can leave them out. The session makes
 * them in its constructor; without them it uses {@link #NONE}, which takes no command and shows nothing.
 */
public interface Campaigns {
    Campaigns NONE = new Campaigns() { };

    /** What the campaign modes need of the session they belong to. */
    interface Host {
        boolean hosting();

        Stage stage();

        boolean move(Stage from, Stage next);

        void tell(Record message);

        Record hello();

        BrowserChannel browser();

        DeckSession decks();

        WebGuiBase ui();

        String playerName();

        int avatarIndex();

        LocalGame local();

        /**
         * Starts a match a campaign builds, from back, which leaving it returns to. start builds it on the GUI the match is played
         * through, and throws if it cannot. gameOver hears each game's end with the host's view of it. failed undoes what preparing it did.
         */
        void startMatch(InCampaign back, Consumer<WebGuiGame> start, Runnable failed, Consumer<GameView> gameOver);

        /** Opens a draft against the computer whose packs are already dealt, played for owner. */
        void startDraft(BoosterDraft draft, DraftOwner owner);
    }

    default boolean handles(final Record command) {
        return false;
    }

    default void run(final BrowserChannel channel, final Record command) {
    }

    /** A deck command, which the campaigns take when it is about their own cards or made on their pages. Answers whether they took it. */
    default boolean deckCommand(final BrowserChannel channel, final Record command) {
        return false;
    }

    /** The browser came back to now, so what a campaign shows there is sent to it again. */
    default void reconnected(final BrowserChannel channel, final Stage now) {
    }

    /** The save each mode would resume, by mode, which the start page offers. */
    default Map<String, String> resume() {
        return Map.of();
    }

    /** The session took the host's seat, which is the only one that opens a campaign. */
    default void becameHost() {
    }

    /** The match started from back was left. */
    default void left(final Playing from, final InCampaign back) {
    }

    /** The campaign stage is showing again, so its page is sent. */
    default void show() {
    }
}
