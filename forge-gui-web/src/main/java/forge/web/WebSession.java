package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
    /** True for the browser on the machine running the game, which is the only one that may set the table. */
    private final boolean isHost;
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

    WebSession(final WebGuiBase ui, final WebSessions sessions, final boolean isHost, final Runnable onQuit) {
        this.ui = ui;
        this.sessions = sessions;
        this.isHost = isHost;
        this.lobby = new Lobby(local);
        this.onQuit = onQuit;
        // Host dialogs belong to the machine running the game
        if (isHost) {
            ui.setNoticeSink(notice -> {
                final BrowserChannel b = browser;
                if (b != null) {
                    b.send(notice);
                }
            });
        }
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
                lobby.setReady(msg.get("ready").getAsBoolean());
                channel.send(lobby.state());
            }
            case "openSeat" -> {
                lobby.openSeat(msg.get("index").getAsInt());
                channel.send(lobby.state());
            }
            case "aiSeat" -> {
                lobby.aiSeat(msg.get("index").getAsInt());
                channel.send(lobby.state());
            }
            case "chat" -> local.sendChat(msg.get("text").getAsString());
            case "setFormat" -> {
                lobby.setFormat(msg.get("format").getAsString());
                channel.send(lobby.decks());
                channel.send(lobby.state());
            }
            case "addSeat" -> {
                lobby.addSeat();
                channel.send(lobby.state());
            }
            case "removeSeat" -> {
                lobby.removeSeat(msg.get("index").getAsInt());
                channel.send(lobby.state());
            }
            case "setSeat" -> {
                applySeat(msg);
                channel.send(lobby.state());
            }
            case "deckDetails" -> {
                final JsonObject details = lobby.deckDetails(msg.get("key").getAsString());
                if (details != null) {
                    channel.send(details);
                }
            }
            // One set of host questions serves the process, so only the host's browser may answer them
            case "hostChoice" -> {
                if (isHost) {
                    ui.hostRequests().answer(msg.get("id").getAsInt(), msg.get("value"));
                }
            }
            // Core asks which category through a host question, and blocks on it, so not on the socket thread
            case "netDecks" -> {
                if (isHost) {
                    ui.runBackgroundTask("Net decks", () -> channel.send(lobby.loadNetDecks()));
                }
            }
            // Finding the external address is a web request, so it cannot run on the socket thread
            case "addresses" -> ui.runBackgroundTask("Addresses", () -> {
                final JsonObject m = JsonCodec.message("addresses");
                m.add("list", sessions.inviteUrls());
                channel.send(m);
            });
            case "cardSearch" -> channel.send(cardSearch(msg));
            case "printings" -> channel.send(printings(msg));
            case "sleeveArt" -> {
                lobby.setSleeveArt(msg.get("index").getAsInt(), msg.get("key").getAsString(), msg.get("offset").getAsInt());
                channel.send(lobby.state());
            }
            // LocalGame runs on the host UI thread, so host-side dialogs during setup never block a web server thread
            case "start" -> ui.invokeInEdtLater(() -> start(channel, msg));
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
            final JsonObject m = JsonCodec.message("chat");
            m.addProperty("from", from);
            m.addProperty("text", text);
            b.send(m);
        }
    }

    private void applySeat(final JsonObject msg) {
        final int index = msg.get("index").getAsInt();
        if (msg.has("name")) {
            lobby.setName(index, msg.get("name").getAsString());
        }
        if (msg.has("deck")) {
            lobby.setDeck(index, msg.get("deck").isJsonNull() ? null : msg.get("deck").getAsString());
        }
        if (msg.has("avatar")) {
            lobby.setAvatar(index, msg.get("avatar").getAsInt());
        }
        if (msg.has("sleeve")) {
            lobby.setSleeve(index, msg.get("sleeve").getAsInt());
        }
    }

    private static JsonObject cardSearch(final JsonObject msg) {
        final JsonObject m = JsonCodec.message("cardSearch");
        m.add("names", DeckCatalog.searchCardNames(msg.get("query").getAsString(), CARD_SEARCH_LIMIT));
        return m;
    }

    private static JsonObject printings(final JsonObject msg) {
        final JsonObject m = JsonCodec.message("printings");
        m.addProperty("name", msg.get("name").getAsString());
        m.add("printings", DeckCatalog.printings(msg.get("name").getAsString()));
        return m;
    }

    private JsonObject hello() {
        final JsonObject m = JsonCodec.message("hello");
        m.addProperty("inMatch", match != null);
        m.addProperty("inLobby", inLobby && match == null);
        m.addProperty("spectating", spectating);
        m.addProperty("host", isHost);
        // A game nobody was invited to has nobody to talk to, so the browser leaves the chat out altogether
        m.addProperty("networked", inviting || !isHost);
        m.addProperty("playerName", FModel.getPreferences().getPref(FPref.PLAYER_NAME));
        // Seat 0 is the player and seat 1 the opponent, as in the desktop lobby's saved choices
        m.add("avatars", seatIndices(FPref.UI_AVATARS));
        m.add("sleeves", seatIndices(FPref.UI_SLEEVES));
        m.addProperty("avatarCount", SkinSprites.avatarCount());
        m.add("playmats", Playmats.list());
        m.addProperty("sleeveCount", SkinSprites.sleeveCount());
        m.add("sleeveArt", DeckCatalog.savedSleeveArt());
        return m;
    }

    private static JsonArray seatIndices(final FPref pref) {
        final JsonArray a = new JsonArray();
        a.add(LocalGame.storedIndex(pref, 0));
        a.add(LocalGame.storedIndex(pref, 1));
        return a;
    }

    private static JsonObject error(final String message) {
        final JsonObject m = JsonCodec.message("error");
        m.addProperty("message", message);
        return m;
    }

    private void start(final BrowserChannel channel, final JsonObject msg) {
        if (!isHost) {
            channel.send(error("Only the host can start the match."));
            return;
        }
        spectating = msg.has("spectate") && msg.get("spectate").getAsBoolean();
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
