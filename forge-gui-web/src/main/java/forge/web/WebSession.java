package forge.web;

import com.google.gson.JsonObject;
import forge.web.FromBrowser.AskDeckDetails;
import forge.web.FromBrowser.AskPrintings;
import forge.web.FromBrowser.HostChoiceAnswer;
import forge.web.FromBrowser.Ready;
import forge.web.FromBrowser.Say;
import forge.web.FromBrowser.SearchCards;
import forge.web.FromBrowser.SeatCommand;
import forge.web.FromBrowser.SetFormat;
import forge.web.FromBrowser.SetSeat;
import forge.web.FromBrowser.SleeveArt;
import forge.web.FromBrowser.Start;
import forge.web.ToBrowser.Addresses;
import forge.web.ToBrowser.CardSearch;
import forge.web.ToBrowser.ChatLine;
import forge.web.ToBrowser.ErrorMessage;
import forge.web.ToBrowser.Hello;
import forge.web.ToBrowser.Printings;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.tinylog.Logger;

import java.util.List;

/** One browser: its start page, its seat and its match. The host's session also owns shutting the process down. */
public final class WebSession {
    private static final int CARD_SEARCH_LIMIT = 60;
    private final Lobby lobby;
    private final WebGuiBase ui;
    private final WebSessions sessions;
    /** True for the browser that claimed the host's seat, which is the only one that may set the table. */
    private volatile boolean isHost;
    private final LocalGame local = new LocalGame();
    private final Runnable onQuit;
    private volatile BrowserChannel browser;
    private volatile WebGuiGame match;
    /** The seat's GUI, made when the lobby opens because the client plays through it from then on. */
    private volatile WebGuiGame lobbyGui;
    /** True when an AI plays the web seat and the browser only spectates. */
    private volatile boolean spectating;
    /** True once the browser has opened match setup, so a reconnect lands back on it rather than the menu. */
    private volatile boolean inLobby;
    /** Whether the open game was made to be joined, so leaving a match lands back in the same kind of lobby. */
    private volatile boolean inviting;

    WebSession(final WebGuiBase ui, final WebSessions sessions, final Runnable onQuit) {
        this.ui = ui;
        this.sessions = sessions;
        this.lobby = new Lobby(local);
        this.onQuit = onQuit;
    }

    /** Takes the host's seat. Host dialogs belong to whoever holds it, so they are pointed here. */
    void becomeHost() {
        isHost = true;
        ui.setNoticeSink(notice -> {
            final BrowserChannel b = browser;
            if (b != null) {
                b.send(notice);
            }
        });
    }

    boolean isHost() {
        return isHost;
    }

    /** Whether this session is holding a game open, which is what keeps the host's seat reserved. */
    boolean hasGame() {
        return inLobby || match != null;
    }

    /** The seat came free or was taken, so a browser waiting on it is told again what it may do. */
    void hostSeatChanged() {
        final BrowserChannel b = browser;
        if (b != null && !isHost) {
            b.send(hello());
        }
    }

    /** Whether a browser is attached to this session right now. */
    boolean attached() {
        return browser != null;
    }

    /** The loopback port guests take a seat on. */
    int gamePort() {
        return local.isHost() ? local.port() : -1;
    }

    synchronized void connected(final BrowserChannel channel) {
        final BrowserChannel previous = browser;
        browser = channel;
        final WebGuiGame m = match;
        if (m != null && previous != null) {
            m.detach(previous);
        }
        channel.send(hello());
        if (isHost) {
            ui.hostRequests().replay(channel::send);
        }
        if (m != null) {
            m.attach(channel);
        }
        // A guest that arrives while a game is already open takes a seat without being asked
        if (!isHost && !inLobby) {
            joinHostGame();
        }
    }

    synchronized void disconnected(final BrowserChannel channel) {
        if (browser != channel) {
            return;
        }
        browser = null;
        final WebGuiGame m = match;
        if (m != null) {
            m.detach(channel);
        }
    }

    void onMessage(final BrowserChannel channel, final JsonObject msg) {
        switch (msg.get("t").getAsString()) {
            case "decks" -> channel.send(lobby.decks());
            case "claimHost" -> {
                if (!sessions.claimHost(this)) {
                    channel.send(error("Someone else is already hosting."));
                }
                channel.send(hello());
            }
            // Opening a game connects a client to a server, which the host UI thread owns
            case "lobby" -> ui.invokeInEdtLater(() -> openLobby(channel, false));
            case "invite" -> ui.invokeInEdtLater(() -> openLobby(channel, true));
            case "leaveLobby" -> ui.invokeInEdtLater(() -> {
                inLobby = false;
                local.close();
                channel.send(hello());
                if (isHost) {
                    sessions.hostGameClosed();
                }
            });
            case "ready" -> {
                lobby.setReady(Wire.decode(msg, Ready.class).ready());
                channel.send(lobby.state());
            }
            case "openSeat", "aiSeat", "removeSeat" -> {
                final SeatCommand seat = Wire.decode(msg, SeatCommand.class);
                switch (seat.t()) {
                    case openSeat -> lobby.openSeat(seat.index());
                    case aiSeat -> lobby.aiSeat(seat.index());
                    case removeSeat -> lobby.removeSeat(seat.index());
                }
                channel.send(lobby.state());
            }
            case "chat" -> local.sendChat(Wire.decode(msg, Say.class).text());
            case "setFormat" -> {
                lobby.setFormat(Wire.decode(msg, SetFormat.class).format());
                channel.send(lobby.decks());
                channel.send(lobby.state());
            }
            case "addSeat" -> {
                lobby.addSeat();
                channel.send(lobby.state());
            }
            case "setSeat" -> {
                applySeat(Wire.decode(msg, SetSeat.class));
                channel.send(lobby.state());
            }
            case "deckDetails" -> {
                final ToBrowser.DeckDetailsMessage details = lobby.deckDetails(Wire.decode(msg, AskDeckDetails.class).key());
                if (details != null) {
                    channel.send(details);
                }
            }
            // One set of host questions serves the process, so only the host's browser may answer them
            case "hostChoice" -> {
                if (isHost) {
                    final HostChoiceAnswer answer = Wire.decode(msg, HostChoiceAnswer.class);
                    ui.hostRequests().answer(answer.id(), answer.value());
                }
            }
            // Core asks which category through a host question, and blocks on it, so not on the socket thread
            case "netDecks" -> {
                if (isHost) {
                    ui.runBackgroundTask("Net decks", () -> channel.send(lobby.loadNetDecks()));
                }
            }
            // Finding the external address is a web request, so it cannot run on the socket thread
            case "addresses" -> ui.runBackgroundTask("Addresses", () -> channel.send(new Addresses(sessions.inviteUrls())));
            case "cardSearch" -> channel.send(new CardSearch(
                    DeckCatalog.searchCardNames(Wire.decode(msg, SearchCards.class).query(), CARD_SEARCH_LIMIT)));
            case "printings" -> {
                final String name = Wire.decode(msg, AskPrintings.class).name();
                channel.send(new Printings(name, DeckCatalog.printings(name)));
            }
            case "sleeveArt" -> {
                final SleeveArt art = Wire.decode(msg, SleeveArt.class);
                lobby.setSleeveArt(art.index(), art.key(), art.offset());
                channel.send(lobby.state());
            }
            // LocalGame runs on the host UI thread, so host-side dialogs during setup never block a web server thread
            case "start" -> {
                final Start start = Wire.decode(msg, Start.class);
                ui.invokeInEdtLater(() -> start(channel, start));
            }
            case "leave" -> ui.invokeInEdtLater(this::leave);
            // Quitting stops the process every browser is served from, so it is the host's to do
            case "quit" -> {
                if (isHost) {
                    ui.invokeInEdtLater(this::quit);
                }
            }
            default -> {
                final WebGuiGame m = match;
                if (m != null) {
                    m.onBrowserMessage(msg);
                }
            }
        }
    }

    /** Opens match setup: a game of this machine's own, or a seat in the host's. Tells the browser how it went. */
    private void openLobby(final BrowserChannel channel, final boolean invite) {
        if (!isHost) {
            joinHostGame();
            return;
        }
        final String name = FModel.getPreferences().getPref(FPref.PLAYER_NAME);
        inviting = invite;
        lobby.forget();
        lobby.setShareable(invite);
        try {
            local.openHost(name, seatGui(), this::lobbyChanged, this::chatted);
            if (invite) {
                // Somebody has to be able to sit down, so the second seat is left open rather than filled
                lobby.openSeat(1);
            }
        } catch (final RuntimeException e) {
            Logger.error(e, "Could not open the lobby");
            // hello() clears the browser's last error, so the reason has to follow it
            channel.send(hello());
            channel.send(error("Could not open the lobby: " + e.getMessage()));
            return;
        }
        inLobby = true;
        channel.send(hello());
        channel.send(lobby.decks());
        channel.send(lobby.state());
        sessions.hostGameOpened();
    }

    /** Takes a seat in the host's game. Runs off the host UI thread, because taking one waits on the server. */
    void joinHostGame() {
        final int port = sessions.hostPort();
        final BrowserChannel channel = browser;
        if (isHost || inLobby || port < 0 || channel == null) {
            return;
        }
        ui.runBackgroundTask("Joining", () -> {
            final String name = FModel.getPreferences().getPref(FPref.PLAYER_NAME);
            lobby.forget();
            try {
                local.openGuest(name, seatGui(), port, this::lobbyChanged, this::chatted, this::gameGone);
            } catch (final RuntimeException e) {
                Logger.error(e, "Could not take a seat");
                channel.send(hello());
                channel.send(error("Could not take a seat: " + e.getMessage()));
                return;
            }
            inLobby = true;
            channel.send(hello());
            channel.send(lobby.decks());
            channel.send(lobby.state());
        });
    }

    /** The host's game ended under this guest, so its seat goes and its browser waits for the next one. */
    void gameGone() {
        if (!inLobby && match == null) {
            return;
        }
        inLobby = false;
        closeMatch();
        local.close();
        final BrowserChannel b = browser;
        if (b != null) {
            b.send(hello());
        }
    }

    private void lobbyChanged() {
        final BrowserChannel b = browser;
        if (b != null) {
            b.send(lobby.state());
        }
    }

    /** One GUI serves the lobby and then the match it becomes. */
    private WebGuiGame seatGui() {
        closeMatch();
        lobbyGui = new WebGuiGame();
        return lobbyGui;
    }

    private void chatted(final String from, final String text) {
        final BrowserChannel b = browser;
        if (b != null) {
            b.send(new ChatLine(from, text));
        }
    }

    private void applySeat(final SetSeat seat) {
        if (seat.name() != null) {
            lobby.setName(seat.index(), seat.name());
        }
        if (seat.deck() != null) {
            lobby.setDeck(seat.index(), seat.deck());
        }
        if (seat.avatar() != null) {
            lobby.setAvatar(seat.index(), seat.avatar());
        }
        if (seat.sleeve() != null) {
            lobby.setSleeve(seat.index(), seat.sleeve());
        }
    }

    private Hello hello() {
        return new Hello(match != null, inLobby && match == null, spectating, isHost,
                // Nobody hosts by arriving, so a browser is offered the seat whenever it is free
                !isHost && sessions.hostSeatFree(this),
                // A game nobody was invited to has nobody to talk to, so the browser leaves the chat out altogether
                inviting || !isHost,
                FModel.getPreferences().getPref(FPref.PLAYER_NAME),
                // Seat 0 is the player and seat 1 the opponent, as in the desktop lobby's saved choices
                seatIndices(FPref.UI_AVATARS), seatIndices(FPref.UI_SLEEVES),
                SkinSprites.avatarCount(), SkinSprites.sleeveCount(), Playmats.list(), DeckCatalog.savedSleeveArt());
    }

    private static List<Integer> seatIndices(final FPref pref) {
        return List.of(LocalGame.storedIndex(pref, 0), LocalGame.storedIndex(pref, 1));
    }

    private static ErrorMessage error(final String message) {
        return new ErrorMessage(message);
    }

    private void start(final BrowserChannel channel, final Start msg) {
        if (!isHost) {
            channel.send(error("Only the host can start the match."));
            return;
        }
        spectating = msg.spectate();
        final List<String> problems = lobby.problems();
        if (!problems.isEmpty()) {
            channel.send(error(problems.get(0)));
            return;
        }
        // Saved before the match so HostedMatch never reaches the first-run name prompt
        lobby.saveLooks();
        final WebGuiGame gui = lobbyGui;
        if (gui == null) {
            channel.send(error("No lobby is open."));
            return;
        }
        match = gui;
        final BrowserChannel b = browser;
        if (b != null) {
            b.send(hello());
            gui.attach(b);
        }
        try {
            local.start();
            if (spectating) {
                local.spectate();
            }
        } catch (final RuntimeException e) {
            Logger.error(e, "Could not start the match");
            closeMatch();
            local.endMatch();
            channel.send(hello());
            channel.send(error("Could not start the match: " + e.getMessage()));
        }
    }

    /** Back to match setup after a game, into the same kind of lobby as before it. */
    private void leave() {
        closeMatch();
        final BrowserChannel b = browser;
        if (b == null) {
            return;
        }
        if (isHost) {
            openLobby(b, inviting);
        } else {
            inLobby = false;
            joinHostGame();
        }
    }

    /** Lets go of this browser's match and seat without stopping the process. */
    void shutdown() {
        closeMatch();
        local.shutdown();
    }

    private void quit() {
        sessions.hostGameClosed();
        final WebGuiGame m = match;
        if (m != null) {
            m.concede();
        }
        closeMatch();
        local.shutdown();
        onQuit.run();
    }

    /** Each match holds a thread of its own, so the one being replaced has to let go of it. */
    private void closeMatch() {
        final WebGuiGame m = match;
        match = null;
        lobbyGui = null;
        if (m != null) {
            m.close();
        }
    }
}
