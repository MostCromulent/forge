package forge.web;

import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.player.Player;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.GuiBase;
import forge.player.PlayerControllerHuman;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One server serves the whole class, because stopping and restarting it between tests races with its own shutdown. */
abstract class SessionsTest {
    WebSessions sessions;
    final List<Recorder> browsers = new CopyOnWriteArrayList<>();

    @BeforeClass
    public void startSessions() {
        if (slow()) {
            WebTestSupport.skipUnlessStress();
        }
        WebTestSupport.initModel();
        sessions = new WebSessions(gui(), 120_000, () -> { });
    }

    /** Whether the class plays whole sessions over netplay, which is slow enough to run only when asked for. */
    boolean slow() {
        return false;
    }

    /** The GUI the sessions run on: their own, unless a test needs the browser asked what core asks. */
    WebGuiBase gui() {
        return new WebGuiBase();
    }

    @AfterMethod(alwaysRun = true)
    public void disconnectBrowsers() {
        for (final Recorder browser : browsers) {
            sessions.disconnected(browser);
        }
        browsers.clear();
        afterDisconnecting();
    }

    /** What a test leaves behind besides its browsers, cleared once they are gone. */
    void afterDisconnecting() {
    }

    @AfterClass(alwaysRun = true)
    public void stopSessions() {
        if (sessions != null) {
            sessions.shutdown();
            sessions = null;
        }
    }

    /** A browser on the host's link as "host", and on a guest's link otherwise. */
    Recorder connect(final String id) {
        final Recorder browser = new Recorder();
        browsers.add(browser);
        sessions.connected(browser, id, "host".equals(id));
        return browser;
    }

    /** Sends a browser's message with what it was told before forgotten, so a wait that follows is answered by what this brings. */
    void send(final Recorder browser, final JsonObject message) {
        browser.forget();
        sessions.onMessage(browser, message);
    }

    /** The host, named Host, at a fresh table of its own that open ("lobby" or "invite") starts. */
    Recorder hostAt(final String open) throws InterruptedException {
        final Recorder host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        sessions.onMessage(host, message("setName", "name", "Host"));
        send(host, JsonCodec.message(open));
        // The table an earlier test left can still speak until the new one is open, and only then is a command taken
        final JsonObject opening = host.awaitMatching("hello", h -> h.get("joining").getAsBoolean(), "the host's table never started opening");
        final JsonObject opened = host.awaitMatching("hello", h -> host.got.indexOf(h) > host.got.indexOf(opening)
                && h.get("inLobby").getAsBoolean(), "the host's table never opened");
        host.awaitMatching("lobby", l -> host.got.indexOf(l) > host.got.indexOf(opened) && l.has("table")
                && l.getAsJsonObject("table").get("mySeat").getAsInt() >= 0, "the host never sat at its table");
        return host;
    }

    static void onUi(final Runnable r) {
        GuiBase.getInterface().invokeInEdtAndWait(r);
    }

    /** The game's thread deals the zones after the match has started, so what it sets up is waited for. */
    static void awaitTrue(final BooleanSupplier wanted, final String why) {
        awaitTrue(wanted, () -> why);
    }

    static void awaitTrue(final BooleanSupplier wanted, final Supplier<String> why) {
        for (int i = 0; i < 2000 && !wanted.getAsBoolean(); i++) {
            try {
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assert.assertTrue(wanted.getAsBoolean(), why.get());
    }

    static String str(final JsonObject o, final String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : null;
    }

    /** Wins the game for the web seat with dev mode's cheat, as a player checking a reward by hand would. */
    void computerLoses(final Recorder host) {
        awaitPriority(host);
        sessions.onMessage(host, message("dev", "action", "winGame"));
    }

    /** By message and not by id: a restarted match numbers its questions from the start again. */
    final Set<JsonObject> answered = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Gives every question on the way its default answer, since only at first priority is the game past dealing its zones and safe to end. */
    void awaitPriority(final Recorder host) {
        final String[] seen = { "no game" };
        // A press that lands before the input is ready is lost, so it is made again
        final long[] lastPress = { 0 };
        awaitTrue(() -> {
            final ServerGameLobby lobby = sessions.hostLobby();
            final Game game = lobby == null || lobby.getHostedMatch() == null ? null : lobby.getHostedMatch().getGame();
            if (game == null) {
                return false;
            }
            for (final JsonObject m : host.got) {
                if ("request".equals(m.get("t").getAsString()) && answered.add(m)) {
                    sessions.onMessage(host, FakeBrowser.reply(m.get("id").getAsInt(), m.get("default")));
                }
            }
            for (final Player p : game.getPlayers()) {
                if (p.getController() instanceof PlayerControllerHuman human) {
                    final Object input = human.getInputQueue().getInput();
                    seen[0] = (input == null ? "no input" : input.getClass().getSimpleName()) + ", messages "
                            + host.got.stream().map(m -> m.get("t").getAsString() + (m.has("kind") ? ":" + m.get("kind").getAsString() : "")).distinct().toList()
                            + ", turn " + game.getPhaseHandler().getTurn() + " " + game.getPhaseHandler().getPhase() + ", over " + game.isGameOver();
                    if (input instanceof InputPassPriority) {
                        return true;
                    }
                    // Keeping the opening hand is an input of its own, answered with OK
                    if (input != null && System.currentTimeMillis() - lastPress[0] > 500) {
                        lastPress[0] = System.currentTimeMillis();
                        sessions.onMessage(host, JsonCodec.message("ok"));
                    }
                }
            }
            return false;
        }, () -> "the web seat never got priority: " + seen[0]);
    }

    static List<String> buttons(final JsonObject result) {
        final List<String> actions = new ArrayList<>();
        result.getAsJsonArray("buttons").forEach(b -> actions.add(b.getAsJsonObject().get("action").getAsString()));
        return actions;
    }

    /** The names at a table's seats, null for a seat nobody has named. */
    static List<String> seatNames(final JsonObject table) {
        final List<String> out = new ArrayList<>();
        table.getAsJsonArray("seats").forEach(s -> out.add(s.getAsJsonObject().has("name")
                ? s.getAsJsonObject().get("name").getAsString() : null));
        return out;
    }

    /** A message of a type with its fields given as name, value pairs; a null value is left out. */
    static JsonObject message(final String type, final Object... fields) {
        final JsonObject m = JsonCodec.message(type);
        for (int i = 0; i < fields.length; i += 2) {
            final Object value = fields[i + 1];
            if (value instanceof Number n) {
                m.addProperty((String) fields[i], n);
            } else if (value instanceof Boolean b) {
                m.addProperty((String) fields[i], b);
            } else if (value != null) {
                m.addProperty((String) fields[i], (String) value);
            }
        }
        return m;
    }
}
