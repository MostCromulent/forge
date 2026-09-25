package forge.web;

import com.google.gson.JsonObject;
import forge.gamemodes.limited.LimitedPoolType;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** Draft and sealed events at a web table, set up and started by the host with guests seated, as desktop's lobby runs them. */
public class OnlineEventTest {
    private static final int WAIT_MILLIS = 60_000;

    static final class Recorder implements BrowserChannel {
        final List<JsonObject> got = new CopyOnWriteArrayList<>();

        @Override
        public void send(final JsonObject message) {
            got.add(message);
        }

        JsonObject awaitMatching(final String type, final Predicate<JsonObject> wanted) throws InterruptedException {
            for (int i = 0; i < WAIT_MILLIS / 20; i++) {
                for (final JsonObject m : got) {
                    if (type.equals(m.get("t").getAsString()) && wanted.test(m)) {
                        return m;
                    }
                }
                Thread.sleep(20);
            }
            return null;
        }

        /** Waits until the latest table is the one wanted; an earlier one can describe a table that has moved on. */
        JsonObject awaitLobby(final Predicate<JsonObject> wanted) throws InterruptedException {
            for (int i = 0; i < WAIT_MILLIS / 20; i++) {
                final JsonObject latest = latestTable();
                if (latest != null && wanted.test(latest)) {
                    return latest;
                }
                Thread.sleep(20);
            }
            return null;
        }

        JsonObject latestTable() {
            JsonObject latest = null;
            for (final JsonObject m : got) {
                if ("lobby".equals(m.get("t").getAsString()) && m.has("table")) {
                    latest = m.getAsJsonObject("table");
                }
            }
            return latest;
        }

        void forget() {
            got.clear();
        }
    }

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

    private static JsonObject named(final String name) {
        final JsonObject m = JsonCodec.message("setName");
        m.addProperty("name", name);
        return m;
    }

    /** The host at a fresh table of its own, invited or not, with the Limited switch set to kind. */
    private Recorder hostAt(final String open, final String kind) throws InterruptedException {
        final Recorder host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        sessions.onMessage(host, named("Host"));
        host.forget();
        sessions.onMessage(host, JsonCodec.message(open));
        Assert.assertNotNull(host.awaitLobby(l -> l.get("mySeat").getAsInt() >= 0), "the host never sat at its table");
        sessions.onMessage(host, setLimited(kind));
        Assert.assertNotNull(host.awaitLobby(l -> l.has("limited") && kind.equals(l.getAsJsonObject("limited").get("kind").getAsString())),
                "the table never became a " + kind + " table");
        return host;
    }

    private static JsonObject setLimited(final String kind) {
        final JsonObject m = JsonCodec.message("setLimited");
        if (kind != null) {
            m.addProperty("kind", kind);
        }
        return m;
    }

    private static JsonObject eventSetup(final String product, final String cube, final int packs) {
        final JsonObject m = JsonCodec.message("eventSetup");
        m.addProperty("product", product);
        if (cube != null) {
            m.addProperty("cube", cube);
        }
        m.addProperty("packs", packs);
        m.addProperty("podSize", 8);
        m.addProperty("timer", 0);
        m.addProperty("grace", 0);
        return m;
    }

    private static JsonObject ready(final boolean on) {
        final JsonObject m = JsonCodec.message("ready");
        m.addProperty("ready", on);
        return m;
    }

    private static List<String> seatNames(final JsonObject table) {
        final List<String> out = new ArrayList<>();
        table.getAsJsonArray("seats").forEach(s -> out.add(s.getAsJsonObject().has("name")
                ? s.getAsJsonObject().get("name").getAsString() : null));
        return out;
    }

    // Fails if a Limited table keeps Constructed's four-seat cap, if an add at the cap disturbs a seat, or if the table
    // can go back to Constructed with more players than a Constructed match seats
    @Test(timeOut = 180_000)
    public void aLimitedTableSeatsEight() throws Exception {
        final Recorder host = hostAt("lobby", "sealed");
        for (int i = 0; i < 6; i++) {
            final int before = host.latestTable().getAsJsonArray("seats").size();
            sessions.onMessage(host, JsonCodec.message("addSeat"));
            Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonArray("seats").size() == before + 1), "seat " + (before + 1) + " was refused");
        }
        final JsonObject full = host.latestTable();
        Assert.assertEquals(full.get("maxSeats").getAsInt(), 8);
        host.forget();
        sessions.onMessage(host, JsonCodec.message("addSeat"));
        final JsonObject after = host.awaitLobby(l -> true);
        Assert.assertEquals(seatNames(after), seatNames(full), "an add at the cap changed the table");

        sessions.onMessage(host, setLimited(null));
        Assert.assertNotNull(host.awaitMatching("error", e -> true), "going back to Constructed with eight seated was not refused");
        Assert.assertTrue(host.latestTable().has("limited"), "the table left Limited with eight seated");
    }

    // Fails if an event starts while a seat is not ready, or does not start once every seat is
    @Test(timeOut = 180_000)
    public void startWaitsForReady() throws Exception {
        final Recorder host = hostAt("invite", "sealed");
        final Recorder guest = connect("guest");
        sessions.onMessage(guest, named("Guest"));
        Assert.assertNotNull(host.awaitLobby(l -> seatNames(l).contains("Guest")), "the guest never sat down");
        sessions.onMessage(host, eventSetup(LimitedPoolType.Full.name(), null, 6));
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonObject("limited").has("product")), "the sealed event was never set up");
        sessions.onMessage(host, ready(true));

        host.forget();
        sessions.onMessage(host, JsonCodec.message("eventStart"));
        Assert.assertNotNull(host.awaitMatching("error", e -> e.get("message").getAsString().contains("Guest")),
                "the event started with the guest not ready");

        sessions.onMessage(guest, ready(true));
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonArray("seats").asList().stream().allMatch(s -> s.getAsJsonObject().get("ready").getAsBoolean())),
                "the guest never showed as ready");
        sessions.onMessage(host, JsonCodec.message("eventStart"));
        Assert.assertNotNull(host.awaitLobby(l -> "POOL_DISTRIBUTION".equals(l.getAsJsonObject("limited").has("phase")
                ? l.getAsJsonObject("limited").get("phase").getAsString() : null)), "the sealed event never started");
    }

    // Fails if setting the event up again keeps the first product, which is how Edit event changes one
    @Test(timeOut = 300_000)
    public void anEditedEventReplacesTheOld() throws Exception {
        final Recorder host = hostAt("lobby", "draft");
        sessions.onMessage(host, eventSetup(LimitedPoolType.Full.name(), null, 3));
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonObject("limited").has("product")), "the draft was never set up");
        final String cube = OfflineEvents.options().cubes().get(0);
        sessions.onMessage(host, eventSetup(LimitedPoolType.Custom.name(), cube, 3));
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonObject("limited").has("product")
                && l.getAsJsonObject("limited").get("product").getAsString().startsWith(LimitedPoolType.Custom.toString())),
                "the edited event kept its first product");
    }
}
