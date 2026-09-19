package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.deck.DeckProxy;
import forge.game.GameType;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.tinylog.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One browser at a time: the start page, the current match, and shutdown when no browser has been connected for a while. */
public final class WebSession implements WebServer.Endpoint {
    private static final String AI_NAME = "Forge AI";
    private final WebGuiBase ui;
    private final LocalGame local;
    private final long idleMillis;
    private final Runnable onQuit;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "WebIdle");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, DeckProxy> decks = new ConcurrentHashMap<>();
    private ScheduledFuture<?> idle;
    private volatile BrowserChannel browser;
    private volatile WebGuiGame match;

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
            case "decks" -> channel.send(deckList());
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

    private JsonObject hello() {
        final JsonObject m = JsonCodec.message("hello");
        m.addProperty("inMatch", match != null);
        m.addProperty("playerName", FModel.getPreferences().getPref(FPref.PLAYER_NAME));
        return m;
    }

    private static JsonObject error(final String message) {
        final JsonObject m = JsonCodec.message("error");
        m.addProperty("message", message);
        return m;
    }

    private JsonObject deckList() {
        decks.clear();
        final JsonArray list = new JsonArray();
        for (final DeckProxy proxy : DeckProxy.getAllConstructedDecks()) {
            final String key = proxy.getPath() + "/" + proxy.getName();
            decks.put(key, proxy);
            final JsonObject d = new JsonObject();
            d.addProperty("key", key);
            d.addProperty("name", proxy.getName());
            d.addProperty("problem", GameType.Constructed.getDeckFormat().getDeckConformanceProblem(proxy.getDeck()));
            list.add(d);
        }
        final JsonObject m = JsonCodec.message("decks");
        m.add("decks", list);
        return m;
    }

    // Checked here so the lobby's deck-legality confirm dialog never opens on the host
    private static String legalityProblem(final DeckProxy... chosen) {
        if (!FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY)) {
            return null;
        }
        for (final DeckProxy d : chosen) {
            final String problem = GameType.Constructed.getDeckFormat().getDeckConformanceProblem(d.getDeck());
            if (problem != null) {
                return d.getName() + ": " + problem;
            }
        }
        return null;
    }

    private void start(final BrowserChannel channel, final JsonObject msg) {
        final String name = msg.get("playerName").getAsString().trim();
        final DeckProxy mine = decks.get(msg.get("playerDeck").getAsString());
        final DeckProxy theirs = decks.get(msg.get("aiDeck").getAsString());
        final String problem = name.isEmpty() ? "Enter a player name."
                : mine == null || theirs == null ? "Choose both decks."
                : legalityProblem(mine, theirs);
        if (problem != null) {
            channel.send(error(problem));
            return;
        }
        // Set before the match so HostedMatch never reaches the first-run name prompt
        FModel.getPreferences().setPref(FPref.PLAYER_NAME, name);
        FModel.getPreferences().save();
        final WebGuiGame gui = new WebGuiGame();
        match = gui;
        final BrowserChannel b = browser;
        if (b != null) {
            b.send(hello());
            gui.attach(b);
        }
        try {
            local.startMatch(name, mine.getDeck(), AI_NAME, theirs.getDeck(), gui);
        } catch (final RuntimeException e) {
            Logger.error(e, "Could not start the match");
            match = null;
            local.endMatch();
            channel.send(error("Could not start the match: " + e.getMessage()));
            channel.send(hello());
        }
    }

    private void leave() {
        match = null;
        local.endMatch();
        final BrowserChannel b = browser;
        if (b != null) {
            b.send(hello());
        }
    }

    private void quit() {
        final WebGuiGame m = match;
        if (m != null) {
            m.onBrowserMessage(JsonCodec.message("concede"));
        }
        match = null;
        local.shutdown();
        onQuit.run();
    }
}
