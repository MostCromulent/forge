package forge.web;

import com.google.gson.JsonObject;
import forge.web.OnlineEventTest.Recorder;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The table's player count: seats added and taken away at the end, and never a seat a person holds. */
public class TableSizeTest {
    private WebSessions sessions;
    private final List<Recorder> browsers = new CopyOnWriteArrayList<>();

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
        sessions = new WebSessions(new WebGuiBase(), 120_000, () -> { });
    }

    @AfterMethod(alwaysRun = true)
    public void disconnectBrowsers() {
        for (final Recorder browser : browsers) {
            sessions.disconnected(browser);
        }
        browsers.clear();
    }

    @AfterClass
    public void tearDown() {
        if (sessions != null) {
            sessions.shutdown();
            sessions = null;
        }
    }

    private Recorder connect(final String id) {
        final Recorder browser = new Recorder();
        browsers.add(browser);
        sessions.connected(browser, id, "host".equals(id));
        return browser;
    }

    private static JsonObject message(final String type, final String key, final Object value) {
        final JsonObject m = JsonCodec.message(type);
        if (value instanceof Number n) {
            m.addProperty(key, n);
        } else {
            m.addProperty(key, (String) value);
        }
        return m;
    }

    private Recorder hostAt(final String open) throws InterruptedException {
        final Recorder host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        sessions.onMessage(host, message("setName", "name", "Host"));
        host.forget();
        sessions.onMessage(host, JsonCodec.message(open));
        final JsonObject opening = host.awaitMatching("hello", h -> h.get("joining").getAsBoolean());
        Assert.assertNotNull(opening, "the host's table never started opening");
        Assert.assertNotNull(host.awaitMatching("lobby", l -> host.got.indexOf(l) > host.got.indexOf(opening) && l.has("table")
                && l.getAsJsonObject("table").get("mySeat").getAsInt() >= 0), "the host never sat at its table");
        return host;
    }

    private static List<String> kinds(final JsonObject table) {
        final List<String> out = new ArrayList<>();
        table.getAsJsonArray("seats").forEach(s -> out.add(s.getAsJsonObject().get("type").getAsString()));
        return out;
    }

    private static List<String> names(final JsonObject table) {
        final List<String> out = new ArrayList<>();
        table.getAsJsonArray("seats").forEach(s -> out.add(s.getAsJsonObject().has("name") ? s.getAsJsonObject().get("name").getAsString() : null));
        return out;
    }

    // Fails if a count adds seats other than computers at the end, or lowering it takes a seat other than the last
    @Test(timeOut = 120_000)
    public void seatsComeAndGoAtTheEnd() throws Exception {
        final Recorder host = hostAt("lobby");
        sessions.onMessage(host, message("setPlayerCount", "count", 4));
        final JsonObject four = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 4);
        Assert.assertNotNull(four, "the table never grew to four");
        Assert.assertEquals(kinds(four).subList(1, 4), List.of("AI", "AI", "AI"), "the new seats were not computers");
        final List<String> before = names(four);

        sessions.onMessage(host, message("setPlayerCount", "count", 3));
        final JsonObject three = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 3);
        Assert.assertNotNull(three, "the table never shrank to three");
        Assert.assertEquals(names(three), before.subList(0, 3), "a seat other than the last one went");
    }

    // Fails if lowering the count removes a seat a person holds, or keeps an open seat over a computer's
    @Test(timeOut = 120_000)
    public void aPersonKeepsTheirSeat() throws Exception {
        final Recorder host = hostAt("invite");
        sessions.onMessage(host, message("setPlayerCount", "count", 4));
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 4), "the table never grew to four");
        final Recorder guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest"));
        Assert.assertNotNull(host.awaitLobby(l -> names(l).contains("Guest")), "the guest never sat down");

        sessions.onMessage(host, message("setPlayerCount", "count", 2));
        final JsonObject two = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 2);
        Assert.assertNotNull(two, "the table never shrank to two");
        Assert.assertTrue(names(two).contains("Guest"), "the guest lost their seat: " + names(two));

        sessions.onMessage(host, message("setPlayerCount", "count", 1));
        Thread.sleep(500);
        Assert.assertEquals(host.latestTable().getAsJsonArray("seats").size(), 2, "a table went below two players");
    }
}
