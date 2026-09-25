package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.gamemodes.limited.LimitedPoolType;
import forge.model.FModel;
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

    /** The events a test started, whose pools went into the player's network event decks and come out again after it. */
    private final List<String> events = new CopyOnWriteArrayList<>();

    @AfterMethod(alwaysRun = true)
    public void disconnectBrowsers() {
        for (final Recorder browser : browsers) {
            sessions.disconnected(browser);
        }
        browsers.clear();
        final var stored = FModel.getDecks().getNetworkEventDecks();
        for (final Deck d : stored.stream().toList()) {
            if (events.contains(eventIdOf(d))) {
                stored.delete(d.getName());
            }
        }
        events.clear();
    }

    private static String eventIdOf(final Deck deck) {
        return deck.getTags().stream().filter(t -> t.startsWith("eventId:")).map(t -> t.substring("eventId:".length()))
                .findFirst().orElse(null);
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
        // The table an earlier test left can still speak until the new one starts opening
        final JsonObject opening = host.awaitMatching("hello", h -> h.get("joining").getAsBoolean());
        Assert.assertNotNull(opening, "the host's table never started opening");
        Assert.assertNotNull(host.awaitMatching("lobby", l -> host.got.indexOf(l) > host.got.indexOf(opening) && l.has("table")
                && l.getAsJsonObject("table").get("mySeat").getAsInt() >= 0), "the host never sat at its table");
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

    private static JsonObject eventSetup(final String product, final int podSize) {
        final JsonObject m = eventSetup(product, null, 3);
        m.addProperty("podSize", podSize);
        m.addProperty("pickRule", "NEVER");
        return m;
    }

    private static JsonObject draftPick(final int step, final int index) {
        final JsonObject m = JsonCodec.message("draftPick");
        m.addProperty("step", step);
        m.addProperty("index", index);
        return m;
    }

    private static JsonObject deviceDecks(final JsonObject... decks) {
        final JsonObject m = JsonCodec.message("deviceDecks");
        final JsonArray list = new JsonArray();
        for (final JsonObject d : decks) {
            list.add(d);
        }
        m.add("decks", list);
        return m;
    }

    /** A host and a guest at a Limited table of kind, both ready, with the event setup describes set up and started. */
    private Recorder[] startedEvent(final String kind, final JsonObject setup) throws InterruptedException {
        final Recorder host = hostAt("invite", kind);
        final Recorder guest = connect("guest");
        sessions.onMessage(guest, named("Guest"));
        Assert.assertNotNull(host.awaitLobby(l -> seatNames(l).contains("Guest")), "the guest never sat down");
        sessions.onMessage(host, setup);
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonObject("limited").has("product")), "the event was never set up");
        sessions.onMessage(host, ready(true));
        sessions.onMessage(guest, ready(true));
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonArray("seats").asList().stream()
                .allMatch(s -> s.getAsJsonObject().get("ready").getAsBoolean())), "the seats never showed as ready");
        sessions.onMessage(host, JsonCodec.message("eventStart"));
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonObject("limited").has("phase")
                && !"LOBBY_GATHER".equals(l.getAsJsonObject("limited").get("phase").getAsString())), "the event never started: "
                + host.got.stream().filter(m -> "error".equals(m.get("t").getAsString())).toList() + " " + host.latestTable());
        return new Recorder[] {host, guest};
    }

    private static int picks(final JsonObject state) {
        return state.getAsJsonArray("picks").size();
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
        final int hostSeat = host.latestTable().get("mySeat").getAsInt();
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonArray("seats").get(hostSeat).getAsJsonObject().get("ready").getAsBoolean()),
                "the host never showed as ready");

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

    // Fails if the draft handler is not registered, so no pack reaches a seat; if it is registered twice, so one pick
    // counts twice; if a pick made on a pack that has moved on is sent; or if the other seat never sees the pick
    @Test(timeOut = 300_000)
    public void aGuestPicksOnlyItsOwnPacks() throws Exception {
        WebTestSupport.skipUnlessStress();
        final Recorder[] both = startedEvent("draft", eventSetup(LimitedPoolType.Full.name(), 2));
        final Recorder host = both[0];
        final Recorder guest = both[1];
        final JsonObject first = guest.awaitMatching("draft", d -> d.getAsJsonArray("cards").size() > 0);
        Assert.assertNotNull(first, "the guest was never shown a pack");
        final JsonObject hostFirst = host.awaitMatching("draft", d -> d.getAsJsonArray("cards").size() > 0);
        Assert.assertNotNull(hostFirst, "the host was never shown a pack");
        final int step = first.get("step").getAsInt();

        sessions.onMessage(guest, draftPick(step - 1, 0));
        sessions.onMessage(guest, draftPick(step, 0));
        Assert.assertNotNull(guest.awaitMatching("draft", d -> picks(d) == 1), "the guest's pick never counted");
        Thread.sleep(1_000);
        Assert.assertTrue(guest.got.stream().filter(m -> "draft".equals(m.get("t").getAsString())).allMatch(d -> picks(d) <= 1),
                "one pick counted twice");
        final String before = hostFirst.getAsJsonArray("seats").toString();
        Assert.assertNotNull(host.awaitMatching("draft", d -> !d.getAsJsonArray("seats").toString().equals(before)),
                "the host never saw the guest's pick");
    }

    // Fails if a guest whose tab closed as its pool arrived loses the pool, or is sent it again once its browser keeps it.
    // A live tab confirms what it keeps only on its next load, since the page sends its decks once per load.
    @Test(timeOut = 300_000)
    public void aGuestsPoolSurvivesAClosedTab() throws Exception {
        final Recorder[] both = startedEvent("sealed", eventSetup(LimitedPoolType.Full.name(), null, 6));
        final Recorder guest = both[1];
        final JsonObject pool = guest.awaitMatching("deviceDeck", d -> d.get("id").getAsString().startsWith("event-"));
        Assert.assertNotNull(pool, "the guest was never sent its pool");
        events.add(pool.get("id").getAsString().substring("event-".length()));

        sessions.disconnected(guest);
        final Recorder again = connect("guest");
        Assert.assertNotNull(again.awaitMatching("deviceDeck", d -> d.get("id").equals(pool.get("id"))),
                "a guest that reloaded before keeping its pool lost it");
        final JsonObject kept = new JsonObject();
        kept.add("id", pool.get("id"));
        kept.add("text", pool.get("text"));
        kept.add("format", pool.get("format"));
        sessions.onMessage(again, deviceDecks(kept));
        // An open editor sends its deck to a guest that comes back, whatever the deck, so it is closed first
        sessions.onMessage(again, JsonCodec.message("editorClose"));
        Assert.assertNotNull(again.awaitMatching("editor", m -> !m.has("state")), "the pool's editor never closed");

        sessions.disconnected(again);
        final Recorder third = connect("guest");
        Assert.assertNotNull(third.awaitMatching("hello", h -> true), "the guest was never greeted");
        Thread.sleep(1_000);
        Assert.assertTrue(third.got.stream().noneMatch(m -> "deviceDeck".equals(m.get("t").getAsString())
                && m.get("id").equals(pool.get("id"))), "a pool the browser already keeps was sent again");
    }

    // Fails if the draft host is never told a closed tab's seat has gone, or never told it came back
    @Test(timeOut = 300_000)
    public void aClosedTabIsHeldThenReturns() throws Exception {
        WebTestSupport.skipUnlessStress();
        sessions.draftHoldMillis = 500;
        final Recorder[] both = startedEvent("draft", eventSetup(LimitedPoolType.Full.name(), 2));
        final Recorder host = both[0];
        final Recorder guest = both[1];
        Assert.assertNotNull(guest.awaitMatching("draft", d -> d.getAsJsonArray("cards").size() > 0), "the guest was never shown a pack");
        sessions.disconnected(guest);
        Assert.assertNotNull(host.awaitMatching("draft", d -> d.getAsJsonArray("seats").asList().stream()
                .anyMatch(s -> s.getAsJsonObject().get("held").getAsBoolean())), "the guest's seat was never held");
        host.forget();
        connect("guest");
        Assert.assertNotNull(host.awaitMatching("draft", d -> d.getAsJsonArray("seats").asList().stream()
                .noneMatch(s -> s.getAsJsonObject().get("held").getAsBoolean())), "the guest's seat stayed held after it came back");
    }

    // Fails if the host's pool is not kept as an event deck of its event, or the table is not set to play it
    @Test(timeOut = 300_000)
    public void theHostsPoolIsAnEventDeck() throws Exception {
        final Recorder[] both = startedEvent("sealed", eventSetup(LimitedPoolType.Full.name(), null, 6));
        final JsonObject table = both[0].awaitLobby(l -> l.getAsJsonObject("limited").has("activeEventId"));
        Assert.assertNotNull(table, "the table was never set to play the event's decks");
        final String id = table.getAsJsonObject("limited").get("activeEventId").getAsString();
        events.add(id);
        Assert.assertTrue(FModel.getDecks().getNetworkEventDecks().stream().anyMatch(d -> id.equals(eventIdOf(d))),
                "the host's pool was not kept among the event decks");
    }

    // Fails if a guest that reloads mid-draft is not put back in the draft with its pack
    @Test(timeOut = 300_000)
    public void aReloadReturnsToTheOnlineDraft() throws Exception {
        WebTestSupport.skipUnlessStress();
        final Recorder[] both = startedEvent("draft", eventSetup(LimitedPoolType.Full.name(), 2));
        final Recorder guest = both[1];
        final JsonObject shown = guest.awaitMatching("draft", d -> d.getAsJsonArray("cards").size() > 0);
        Assert.assertNotNull(shown, "the guest was never shown a pack");
        sessions.disconnected(guest);
        final Recorder again = connect("guest");
        Assert.assertNotNull(again.awaitMatching("hello", h -> h.get("drafting").getAsBoolean()), "the reload was not told it is drafting");
        Assert.assertNotNull(again.awaitMatching("draft", d -> d.get("step").equals(shown.get("step"))
                && d.getAsJsonArray("cards").equals(shown.getAsJsonArray("cards"))), "the reload was not shown its pack again");
    }
}
