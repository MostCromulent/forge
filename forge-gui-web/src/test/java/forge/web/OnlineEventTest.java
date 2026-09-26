package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.net.draft.BoosterDraftHost;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.StaticData;
import java.util.UUID;
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

    /** Stores a 40-Forest deck as a pool of a made-up sealed event, and answers the event's id. */
    private String storedEventDeck(final String name) {
        final String id = UUID.randomUUID().toString();
        final Deck deck = new Deck(name + " " + id.substring(0, 8));
        deck.getMain().add(StaticData.instance().getCommonCards().getCard("Forest"), 40);
        deck.getTags().add("eventId:" + id);
        deck.getTags().add("eventFormat:SEALED");
        deck.getTags().add("eventDate:2026-09-26 10:00");
        FModel.getDecks().getNetworkEventDecks().add(deck);
        events.add(id);
        return id;
    }

    private static JsonObject hostAgain(final String eventId) {
        final JsonObject m = JsonCodec.message("eventHostAgain");
        m.addProperty("eventId", eventId);
        return m;
    }

    private static JsonObject decksOnly(final boolean on) {
        final JsonObject m = JsonCodec.message("eventDecksOnly");
        m.addProperty("on", on);
        return m;
    }

    private static List<String> deckNames(final JsonObject decks) {
        final List<String> out = new ArrayList<>();
        decks.getAsJsonArray("decks").forEach(d -> out.add(d.getAsJsonObject().get("name").getAsString()));
        return out;
    }

    /** The key the finder lists a deck under, waiting for a list that holds it. */
    private static String keyOf(final Recorder browser, final String deckName) throws InterruptedException {
        final JsonObject decks = browser.awaitMatching("decks", d -> deckNames(d).stream().anyMatch(n -> n.startsWith(deckName)));
        Assert.assertNotNull(decks, "the finder never listed " + deckName + ": " + browser.got.stream()
                .filter(m -> "decks".equals(m.get("t").getAsString())).map(OnlineEventTest::deckNames).toList() + " " + browser.latestTable());
        for (final var d : decks.getAsJsonArray("decks")) {
            if (d.getAsJsonObject().get("name").getAsString().startsWith(deckName)) {
                return d.getAsJsonObject().get("key").getAsString();
            }
        }
        return null;
    }

    private static JsonObject setSeatDeck(final int index, final String key) {
        final JsonObject m = JsonCodec.message("setSeat");
        m.addProperty("index", index);
        m.addProperty("deck", key);
        return m;
    }

    private static JsonObject bench(final int index, final boolean on) {
        final JsonObject m = JsonCodec.message("benchSeat");
        m.addProperty("index", index);
        m.addProperty("benched", on);
        return m;
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

    // Fails if the finder at a Limited table ignores the event-decks switch: on, only the table's event's decks; off, every event's
    @Test(timeOut = 180_000)
    public void eventDecksOnlyFilters() throws Exception {
        final String mine = storedEventDeck("Web test ours");
        storedEventDeck("Web test theirs");
        final Recorder host = hostAt("lobby", "sealed");
        sessions.onMessage(host, hostAgain(mine));
        Assert.assertNotNull(host.awaitLobby(l -> mine.equals(l.getAsJsonObject("limited").has("activeEventId")
                ? l.getAsJsonObject("limited").get("activeEventId").getAsString() : null)), "the past event was never hosted again");
        host.forget();
        sessions.onMessage(host, JsonCodec.message("decks"));
        final JsonObject only = host.awaitMatching("decks", d -> true);
        Assert.assertTrue(deckNames(only).stream().anyMatch(n -> n.startsWith("Web test ours")), "the event's deck was not listed");
        Assert.assertTrue(deckNames(only).stream().noneMatch(n -> n.startsWith("Web test theirs")), "another event's deck was listed");
        host.forget();
        sessions.onMessage(host, decksOnly(false));
        sessions.onMessage(host, JsonCodec.message("decks"));
        final JsonObject all = host.awaitMatching("decks", d -> deckNames(d).stream().anyMatch(n -> n.startsWith("Web test theirs")));
        Assert.assertNotNull(all, "with the switch off, another event's deck was still left out");
    }

    // Fails if closing a table leaves its draft host running, whose packs would then reach the next table's seats
    @Test(timeOut = 180_000)
    public void endMatchStopsTheDraftHost() throws Exception {
        final Recorder host = hostAt("lobby", "draft");
        sessions.onMessage(host, eventSetup(LimitedPoolType.Full.name(), 2));
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonObject("limited").has("product")), "the draft was never set up");
        sessions.onMessage(host, ready(true));
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonArray("seats").asList().stream()
                .allMatch(s -> s.getAsJsonObject().get("ready").getAsBoolean())), "the host never showed as ready");
        final ServerGameLobby table = sessions.hostLobby();
        sessions.onMessage(host, JsonCodec.message("eventStart"));
        Assert.assertNotNull(host.awaitMatching("draft", d -> d.getAsJsonArray("cards").size() > 0), "the host was never shown a pack");
        final BoosterDraftHost draftHost = table.getDraftHost();
        Assert.assertNotNull(draftHost);
        sessions.onMessage(host, JsonCodec.message("leaveLobby"));
        Assert.assertNotNull(host.awaitMatching("hello", h -> !h.get("inLobby").getAsBoolean()), "the host never left the table");
        // The browser is told it left before the table is taken down
        for (int i = 0; i < 250 && !draftHost.isFinished(); i++) {
            Thread.sleep(20);
        }
        Assert.assertTrue(draftHost.isFinished(), "the draft host outlived its table");
    }

    // Fails if a Limited match seats more than four, or seats a benched player
    @Test(timeOut = 300_000)
    public void onlyFourPlay() throws Exception {
        WebTestSupport.skipUnlessStress();
        final String id = storedEventDeck("Web test forests");
        final Recorder host = hostAt("lobby", "sealed");
        sessions.onMessage(host, hostAgain(id));
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonObject("limited").has("activeEventId")), "the past event was never hosted again");
        for (int i = 0; i < 3; i++) {
            final int before = host.latestTable().getAsJsonArray("seats").size();
            sessions.onMessage(host, JsonCodec.message("addSeat"));
            Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonArray("seats").size() == before + 1), "a seat was refused");
        }
        sessions.onMessage(host, JsonCodec.message("decks"));
        final String key = keyOf(host, "Web test forests");
        for (int i = 0; i < 5; i++) {
            sessions.onMessage(host, setSeatDeck(i, key));
        }
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonArray("problems").toString().contains("bench 1 more")),
                "five players were let into one match");
        sessions.onMessage(host, bench(4, true));
        final JsonObject ok = host.awaitLobby(l -> l.get("canStart").getAsBoolean());
        Assert.assertNotNull(ok, "the table could not start with one seat benched: " + host.latestTable().getAsJsonArray("problems"));
        final ServerGameLobby table = sessions.hostLobby();
        final JsonObject start = JsonCodec.message("start");
        sessions.onMessage(host, start);
        Assert.assertNotNull(host.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean()), "the match never started");
        for (int i = 0; i < 500 && (table.getHostedMatch() == null || table.getHostedMatch().getGame() == null); i++) {
            Thread.sleep(20);
        }
        Assert.assertEquals(table.getHostedMatch().getGame().getPlayers().size(), 4, "the benched seat played");
        final String benched = table.getSlot(4).getName();
        Assert.assertTrue(table.getHostedMatch().getGame().getPlayers().stream().noneMatch(p -> p.getName().equals(benched)),
                "the benched seat played");
        sessions.onMessage(host, JsonCodec.message("concede"));
    }

    // Fails if leaving a Limited match brings the host back to a Constructed table, or to one that has forgotten its event
    @Test(timeOut = 300_000)
    public void theEventOutlivesAMatch() throws Exception {
        WebTestSupport.skipUnlessStress();
        final String id = storedEventDeck("Web test later");
        final Recorder host = hostAt("lobby", "sealed");
        sessions.onMessage(host, hostAgain(id));
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonObject("limited").has("activeEventId")), "the past event was never hosted again");
        sessions.onMessage(host, JsonCodec.message("decks"));
        final String key = keyOf(host, "Web test later");
        sessions.onMessage(host, setSeatDeck(0, key));
        sessions.onMessage(host, setSeatDeck(1, key));
        Assert.assertNotNull(host.awaitLobby(l -> l.get("canStart").getAsBoolean()), "the match could not start");
        sessions.onMessage(host, JsonCodec.message("start"));
        Assert.assertNotNull(host.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean()), "the match never started");
        host.forget();
        sessions.onMessage(host, JsonCodec.message("leave"));
        final JsonObject back = host.awaitLobby(l -> l.has("limited") && l.getAsJsonObject("limited").has("activeEventId"));
        Assert.assertNotNull(back, "leaving the match lost the Limited table");
        Assert.assertEquals(back.getAsJsonObject("limited").get("activeEventId").getAsString(), id);
        sessions.onMessage(host, JsonCodec.message("decks"));
        Assert.assertNotNull(keyOf(host, "Web test later"), "the event's deck was not in the finder after the match");
    }

    // Fails if a guest that built its deck from the pool is sent the pool as dealt when it comes back, which its browser
    // would keep in place of the build
    @Test(timeOut = 300_000)
    public void aBuiltPoolIsNotReplacedByTheDealtOne() throws Exception {
        final Recorder[] both = startedEvent("sealed", eventSetup(LimitedPoolType.Full.name(), null, 6));
        final Recorder guest = both[1];
        final JsonObject dealt = guest.awaitMatching("deviceDeck", d -> d.get("id").getAsString().startsWith("event-"));
        Assert.assertNotNull(dealt, "the guest was never sent its pool");
        final String id = dealt.get("id").getAsString();
        events.add(id.substring("event-".length()));
        final String text = dealt.get("text").getAsString();
        final String line = text.substring(text.indexOf("[Sideboard]")).split("\\r?\\n")[1];
        final String card = line.substring(line.indexOf(' ') + 1).split("\\|")[0];

        final JsonObject move = JsonCodec.message("editorEdit");
        move.addProperty("op", "move");
        move.addProperty("name", card);
        move.addProperty("from", "Sideboard");
        move.addProperty("to", "Main");
        move.addProperty("count", 1);
        sessions.onMessage(guest, move);
        final JsonObject built = guest.awaitMatching("deviceDeck", d -> d.get("id").getAsString().equals(id) && !d.get("text").equals(dealt.get("text")));
        Assert.assertNotNull(built, "the edit never reached the guest's browser");
        sessions.onMessage(guest, JsonCodec.message("editorClose"));
        Assert.assertNotNull(guest.awaitMatching("editor", m -> !m.has("state")), "the pool's editor never closed");

        sessions.disconnected(guest);
        final Recorder again = connect("guest");
        Assert.assertNotNull(again.awaitMatching("hello", h -> true), "the guest was never greeted");
        Thread.sleep(1_000);
        Assert.assertTrue(again.got.stream().noneMatch(m -> "deviceDeck".equals(m.get("t").getAsString())
                && m.get("id").getAsString().equals(id) && m.get("text").equals(dealt.get("text"))),
                "the guest was sent the pool as dealt, over its build");
        // A page load confirms what the browser keeps, which ends the resending before the next test's guest arrives
        final JsonObject kept = new JsonObject();
        kept.addProperty("id", id);
        kept.add("text", built.get("text"));
        kept.add("format", built.get("format"));
        sessions.onMessage(again, deviceDecks(kept));
    }

    // Fails if a seat benched for an event's match stays benched at the Constructed table that follows, where nothing shows or clears it
    @Test(timeOut = 180_000)
    public void theBenchEndsWithTheEvent() throws Exception {
        final String id = storedEventDeck("Web test bench");
        final Recorder host = hostAt("lobby", "sealed");
        sessions.onMessage(host, hostAgain(id));
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonObject("limited").has("activeEventId")), "the past event was never hosted again");
        sessions.onMessage(host, bench(1, true));
        Assert.assertNotNull(host.awaitLobby(l -> benched(l, 1)),
                "the seat was never benched: " + host.got.stream().filter(m -> "error".equals(m.get("t").getAsString())).toList()
                + " " + host.latestTable());
        sessions.onMessage(host, setLimited(null));
        final JsonObject constructed = host.awaitLobby(l -> !l.has("limited"));
        Assert.assertNotNull(constructed, "the table never went back to Constructed");
        Assert.assertFalse(benched(constructed, 1),
                "the seat stayed benched at a Constructed table");
        sessions.onMessage(host, bench(1, true));
        Thread.sleep(500);
        Assert.assertFalse(benched(host.latestTable(), 1),
                "a Constructed table benched a seat");
    }

    private static boolean benched(final JsonObject table, final int seat) {
        final JsonObject s = table.getAsJsonArray("seats").get(seat).getAsJsonObject();
        return s.has("benched") && s.get("benched").getAsBoolean();
    }

    // Fails if a player who closes their pool's editor still has to find their deck in the finder to sit with it
    @Test(timeOut = 300_000)
    public void aPoolTakesItsPlayersSeat() throws Exception {
        final Recorder[] both = startedEvent("sealed", eventSetup(LimitedPoolType.Full.name(), null, 6));
        for (final Recorder browser : both) {
            Assert.assertNotNull(browser.awaitMatching("editor", m -> m.has("state")), "the pool's editor never opened");
            sessions.onMessage(browser, JsonCodec.message("editorClose"));
        }
        final JsonObject table = both[0].awaitLobby(l -> l.getAsJsonArray("seats").asList().stream()
                .allMatch(s -> s.getAsJsonObject().has("deckName")));
        Assert.assertNotNull(table, "a closed pool was not put on its player's seat: " + both[0].latestTable());
        final JsonObject dealt = both[1].awaitMatching("deviceDeck", d -> d.get("id").getAsString().startsWith("event-"));
        events.add(dealt.get("id").getAsString().substring("event-".length()));
        sessions.onMessage(both[1], deviceDecks(dealt));
    }
}
