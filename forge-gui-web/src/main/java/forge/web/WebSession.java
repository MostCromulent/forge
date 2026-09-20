package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.tinylog.Logger;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One browser at a time: the start page, the current match, and shutdown when no browser has been connected for a while. */
public final class WebSession implements WebServer.Endpoint {
    private static final int CARD_SEARCH_LIMIT = 60;
    private final Lobby lobby = new Lobby();
    private final WebGuiBase ui;
    private final LocalGame local;
    private final long idleMillis;
    private final Runnable onQuit;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "WebIdle");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> idle;
    private volatile BrowserChannel browser;
    private volatile WebGuiGame match;
    /** True when an AI plays the web seat and the browser only spectates. */
    private volatile boolean spectating;
    /** True once the browser has opened match setup, so a reconnect lands back on it rather than the menu. */
    private volatile boolean inLobby;

    public WebSession(final WebGuiBase ui, final LocalGame local, final long idleMillis, final Runnable onQuit) {
        this.ui = ui;
        this.local = local;
        this.idleMillis = idleMillis;
        this.onQuit = onQuit;
        ui.setNoticeSink(notice -> {
            final BrowserChannel b = browser;
            if (b != null) {
                b.send(notice);
            }
        });
        // Also covers a browser that never connects at all
        idle = timer.schedule(this::quitIfStillIdle, idleMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void connected(final BrowserChannel channel) {
        if (idle != null) {
            idle.cancel(false);
            idle = null;
        }
        final BrowserChannel previous = browser;
        browser = channel;
        final WebGuiGame m = match;
        if (m != null && previous != null) {
            m.detach(previous);
        }
        channel.send(hello());
        if (m != null) {
            m.attach(channel);
        }
    }

    @Override
    public synchronized void disconnected(final BrowserChannel channel) {
        if (browser != channel) {
            return;
        }
        browser = null;
        final WebGuiGame m = match;
        if (m != null) {
            m.detach(channel);
        }
        idle = timer.schedule(this::quitIfStillIdle, idleMillis, TimeUnit.MILLISECONDS);
    }

    private synchronized void quitIfStillIdle() {
        if (browser == null) {
            ui.invokeInEdtLater(this::quit);
        }
    }

    @Override
    public void onMessage(final BrowserChannel channel, final JsonObject msg) {
        switch (msg.get("t").getAsString()) {
            case "decks" -> channel.send(lobby.decks());
            case "lobby" -> {
                inLobby = true;
                channel.send(hello());
                channel.send(lobby.decks());
                channel.send(lobby.state());
            }
            case "leaveLobby" -> {
                inLobby = false;
                channel.send(hello());
            }
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
            case "cardSearch" -> channel.send(cardSearch(msg));
            case "printings" -> channel.send(printings(msg));
            case "sleeveArt" -> {
                lobby.setSleeveArt(msg.get("index").getAsInt(), msg.get("key").getAsString(), msg.get("offset").getAsInt());
                channel.send(lobby.state());
            }
            // LocalGame runs on the host UI thread, so host-side dialogs during setup never block a web server thread
            case "start" -> ui.invokeInEdtLater(() -> start(channel, msg));
            case "leave" -> ui.invokeInEdtLater(this::leave);
            case "quit" -> ui.invokeInEdtLater(this::quit);
            default -> {
                final WebGuiGame m = match;
                if (m != null) {
                    m.onBrowserMessage(msg);
                }
            }
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
        spectating = msg.has("spectate") && msg.get("spectate").getAsBoolean();
        final List<String> problems = lobby.problems();
        if (!problems.isEmpty()) {
            channel.send(error(problems.get(0)));
            return;
        }
        // Saved before the match so HostedMatch never reaches the first-run name prompt
        lobby.saveLooks();
        closeMatch();
        final WebGuiGame gui = new WebGuiGame();
        match = gui;
        final BrowserChannel b = browser;
        if (b != null) {
            b.send(hello());
            gui.attach(b);
        }
        try {
            local.startMatch(lobby.toSeats(), lobby.format(), gui);
            if (spectating) {
                local.spectate();
            }
        } catch (final RuntimeException e) {
            Logger.error(e, "Could not start the match");
            closeMatch();
            local.endMatch();
            channel.send(error("Could not start the match: " + e.getMessage()));
            channel.send(hello());
        }
    }

    private void leave() {
        inLobby = true;
        closeMatch();
        local.endMatch();
        final BrowserChannel b = browser;
        if (b != null) {
            b.send(hello());
        }
    }

    private void quit() {
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
        if (m != null) {
            m.close();
        }
    }
}
