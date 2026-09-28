package forge.web;

import com.google.gson.JsonObject;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tests that drive the server as browsers do. One server serves the class, since stopping and restarting it between
 * tests races with its own shutdown; every browser a test connects is let go after it, or a guest from one test takes
 * the seat the next test's guest wants.
 */
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

    /** The host, named Host, at a fresh table of its own that open ("lobby" or "invite") starts. */
    Recorder hostAt(final String open) throws InterruptedException {
        final Recorder host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        sessions.onMessage(host, message("setName", "name", "Host"));
        host.forget();
        sessions.onMessage(host, JsonCodec.message(open));
        // The table an earlier test left can still speak until the new one is open, and only then is a command taken
        final JsonObject opening = host.awaitMatching("hello", h -> h.get("joining").getAsBoolean(), "the host's table never started opening");
        final JsonObject opened = host.awaitMatching("hello", h -> host.got.indexOf(h) > host.got.indexOf(opening)
                && h.get("inLobby").getAsBoolean(), "the host's table never opened");
        host.awaitMatching("lobby", l -> host.got.indexOf(l) > host.got.indexOf(opened) && l.has("table")
                && l.getAsJsonObject("table").get("mySeat").getAsInt() >= 0, "the host never sat at its table");
        return host;
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
