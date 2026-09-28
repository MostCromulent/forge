package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.net.draft.BoosterDraftHost;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

/** Draft and sealed events at a web table, set up and started by the host with guests seated, as desktop's lobby runs them. */
public class OnlineEventTest extends SessionsTest {
    @Override
    boolean slow() {
        return true;
    }

    private final EventDecks events = new EventDecks();

    @Override
    void afterDisconnecting() {
        events.clear();
    }

    /** The host at a fresh table of its own, invited or not, with the Limited switch set to kind. */
    private Recorder hostAt(final String open, final String kind) throws InterruptedException {
        final Recorder host = hostAt(open);
        sessions.onMessage(host, setLimited(kind));
        host.awaitLobby(l -> l.has("limited") && kind.equals(l.getAsJsonObject("limited").get("kind").getAsString()),
                "the table never became a " + kind + " table");
        return host;
    }

    private static JsonObject setLimited(final String kind) {
        return message("setLimited", "kind", kind);
    }

    private static JsonObject eventSetup(final String product, final String cube, final int packs) {
        return message("eventSetup", "product", product, "cube", cube, "packs", packs, "podSize", 8, "timer", 0, "grace", 0);
    }

    private static JsonObject ready(final boolean on) {
        return message("ready", "ready", on);
    }

    private static JsonObject eventSetup(final String product, final int podSize) {
        final JsonObject m = eventSetup(product, null, 3);
        m.addProperty("podSize", podSize);
        m.addProperty("pickRule", "NEVER");
        return m;
    }

    private static JsonObject draftPick(final int step, final int index) {
        return message("draftPick", "step", step, "index", index);
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

    /**
     * Waits for a browser to be shown the table's event, as one must be before it can ready: a seat's copy of the table
     * learning of the event deals the seat afresh, which unreadies a seat readied before it arrived.
     */
    private static void seeEvent(final Recorder browser) throws InterruptedException {
        browser.awaitLobby(l -> l.has("limited") && l.getAsJsonObject("limited").has("product"),
                "the event never reached this browser's table");
    }

    /** A host and a guest at a Limited table of kind, both ready, with the event setup describes set up and started. */
    private Recorder[] startedEvent(final String kind, final JsonObject setup) throws InterruptedException {
        final Recorder host = hostAt("invite", kind);
        final Recorder guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest"));
        host.awaitLobby(l -> seatNames(l).contains("Guest"), "the guest never sat down");
        sessions.onMessage(host, setup);
        host.awaitLobby(l -> l.getAsJsonObject("limited").has("product"), "the event was never set up");
        seeEvent(guest);
        sessions.onMessage(host, ready(true));
        sessions.onMessage(guest, ready(true));
        host.awaitLobby(l -> l.getAsJsonArray("seats").asList().stream()
                .allMatch(s -> s.getAsJsonObject().get("ready").getAsBoolean()), "the seats never showed as ready");
        sessions.onMessage(host, JsonCodec.message("eventStart"));
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonObject("limited").has("phase")
                && !"LOBBY_GATHER".equals(l.getAsJsonObject("limited").get("phase").getAsString())), "the event never started: "
                + host.got.stream().filter(m -> "error".equals(m.get("t").getAsString())).toList() + " " + host.latestTable());
        return new Recorder[] {host, guest};
    }

    private static JsonObject hostAgain(final String eventId) {
        return message("eventHostAgain", "eventId", eventId);
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
        return message("setSeat", "index", index, "deck", key);
    }

    private static JsonObject playerCount(final int count) {
        return message("setPlayerCount", "count", count);
    }

    private static JsonObject bench(final int index, final boolean on) {
        return message("benchSeat", "index", index, "benched", on);
    }

    private static int picks(final JsonObject state) {
        return state.getAsJsonArray("picks").size();
    }

    // Fails if an event starts while a seat is not ready, or does not start once every seat is
    @Test(timeOut = 180_000)
    public void startWaitsForReady() throws Exception {
        final Recorder host = hostAt("invite", "sealed");
        final Recorder guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest"));
        host.awaitLobby(l -> seatNames(l).contains("Guest"), "the guest never sat down");
        sessions.onMessage(host, eventSetup(LimitedPoolType.Full.name(), null, 6));
        host.awaitLobby(l -> l.getAsJsonObject("limited").has("product"), "the sealed event was never set up");
        sessions.onMessage(host, ready(true));
        final int hostSeat = host.latestTable().get("mySeat").getAsInt();
        host.awaitLobby(l -> l.getAsJsonArray("seats").get(hostSeat).getAsJsonObject().get("ready").getAsBoolean(),
                "the host never showed as ready");

        host.forget();
        sessions.onMessage(host, JsonCodec.message("eventStart"));
        host.awaitMatching("error", e -> e.get("message").getAsString().contains("Guest"),
                "the event started with the guest not ready");

        seeEvent(guest);
        sessions.onMessage(guest, ready(true));
        host.awaitLobby(l -> l.getAsJsonArray("seats").asList().stream().allMatch(s -> s.getAsJsonObject().get("ready").getAsBoolean()),
                "the guest never showed as ready");
        sessions.onMessage(host, JsonCodec.message("eventStart"));
        host.awaitLobby(l -> "POOL_DISTRIBUTION".equals(l.getAsJsonObject("limited").has("phase")
                ? l.getAsJsonObject("limited").get("phase").getAsString() : null), "the sealed event never started");
    }

    // Fails if the draft handler is not registered, so no pack reaches a seat; if it is registered twice, so one pick
    // counts twice; if a pick made on a pack that has moved on is sent; or if the other seat never sees the pick
    @Test(timeOut = 300_000)
    public void aGuestPicksOnlyItsOwnPacks() throws Exception {
        WebTestSupport.skipUnlessStress();
        final Recorder[] both = startedEvent("draft", eventSetup(LimitedPoolType.Full.name(), 2));
        final Recorder host = both[0];
        final Recorder guest = both[1];
        final JsonObject first = guest.awaitMatching("draft", d -> d.getAsJsonArray("cards").size() > 0, "the guest was never shown a pack");
        final JsonObject hostFirst = host.awaitMatching("draft", d -> d.getAsJsonArray("cards").size() > 0, "the host was never shown a pack");
        final int step = first.get("step").getAsInt();

        sessions.onMessage(guest, draftPick(step - 1, 0));
        sessions.onMessage(guest, draftPick(step, 0));
        guest.awaitMatching("draft", d -> picks(d) == 1, "the guest's pick never counted");
        Thread.sleep(1_000);
        Assert.assertTrue(guest.got.stream().filter(m -> "draft".equals(m.get("t").getAsString())).allMatch(d -> picks(d) <= 1),
                "one pick counted twice");
        final String before = hostFirst.getAsJsonArray("seats").toString();
        host.awaitMatching("draft", d -> !d.getAsJsonArray("seats").toString().equals(before),
                "the host never saw the guest's pick");
    }

    // Fails if a guest whose tab closed as its pool arrived loses the pool, or is sent it again once its browser keeps it.
    // A live tab confirms what it keeps only on its next load, since the page sends its decks once per load.
    @Test(timeOut = 300_000)
    public void aGuestsPoolSurvivesAClosedTab() throws Exception {
        final Recorder[] both = startedEvent("sealed", eventSetup(LimitedPoolType.Full.name(), null, 6));
        final Recorder guest = both[1];
        final JsonObject pool = guest.awaitMatching("deviceDeck", d -> d.get("id").getAsString().startsWith("event-"), "the guest was never sent its pool");
        events.add(pool.get("id").getAsString().substring("event-".length()));

        sessions.disconnected(guest);
        final Recorder again = connect("guest");
        again.awaitMatching("deviceDeck", d -> d.get("id").equals(pool.get("id")),
                "a guest that reloaded before keeping its pool lost it");
        final JsonObject kept = new JsonObject();
        kept.add("id", pool.get("id"));
        kept.add("text", pool.get("text"));
        kept.add("format", pool.get("format"));
        sessions.onMessage(again, deviceDecks(kept));
        // An open editor sends its deck to a guest that comes back, whatever the deck, so it is closed first
        sessions.onMessage(again, JsonCodec.message("editorClose"));
        again.awaitMatching("editor", m -> !m.has("state"), "the pool's editor never closed");

        sessions.disconnected(again);
        final Recorder third = connect("guest");
        third.awaitMatching("hello", h -> true, "the guest was never greeted");
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
        guest.awaitMatching("draft", d -> d.getAsJsonArray("cards").size() > 0, "the guest was never shown a pack");
        sessions.disconnected(guest);
        host.awaitMatching("draft", d -> d.getAsJsonArray("seats").asList().stream()
                .anyMatch(s -> s.getAsJsonObject().get("held").getAsBoolean()), "the guest's seat was never held");
        host.forget();
        connect("guest");
        host.awaitMatching("draft", d -> d.getAsJsonArray("seats").asList().stream()
                .noneMatch(s -> s.getAsJsonObject().get("held").getAsBoolean()), "the guest's seat stayed held after it came back");
    }

    // Fails if the host's pool is not kept as an event deck of its event, or the table is not set to play it
    @Test(timeOut = 300_000)
    public void theHostsPoolIsAnEventDeck() throws Exception {
        final Recorder[] both = startedEvent("sealed", eventSetup(LimitedPoolType.Full.name(), null, 6));
        final JsonObject table = both[0].awaitLobby(l -> l.getAsJsonObject("limited").has("activeEventId"));
        Assert.assertNotNull(table, "the table was never set to play the event's decks");
        final String id = table.getAsJsonObject("limited").get("activeEventId").getAsString();
        events.add(id);
        Assert.assertTrue(FModel.getDecks().getNetworkEventDecks().stream().anyMatch(d -> id.equals(EventDecks.eventIdOf(d))),
                "the host's pool was not kept among the event decks");
    }

    // Fails if a guest that reloads mid-draft is not put back in the draft with its pack
    @Test(timeOut = 300_000)
    public void aReloadReturnsToTheOnlineDraft() throws Exception {
        WebTestSupport.skipUnlessStress();
        final Recorder[] both = startedEvent("draft", eventSetup(LimitedPoolType.Full.name(), 2));
        final Recorder guest = both[1];
        final JsonObject shown = guest.awaitMatching("draft", d -> d.getAsJsonArray("cards").size() > 0, "the guest was never shown a pack");
        sessions.disconnected(guest);
        final Recorder again = connect("guest");
        again.awaitMatching("hello", h -> h.get("drafting").getAsBoolean(), "the reload was not told it is drafting");
        again.awaitMatching("draft", d -> d.get("step").equals(shown.get("step"))
                && d.getAsJsonArray("cards").equals(shown.getAsJsonArray("cards")), "the reload was not shown its pack again");
    }

    // Fails if closing a table leaves its draft host running, whose packs would then reach the next table's seats
    @Test(timeOut = 180_000)
    public void endMatchStopsTheDraftHost() throws Exception {
        final Recorder host = hostAt("lobby", "draft");
        sessions.onMessage(host, eventSetup(LimitedPoolType.Full.name(), 2));
        host.awaitLobby(l -> l.getAsJsonObject("limited").has("product"), "the draft was never set up");
        sessions.onMessage(host, ready(true));
        host.awaitLobby(l -> l.getAsJsonArray("seats").asList().stream()
                .allMatch(s -> s.getAsJsonObject().get("ready").getAsBoolean()), "the host never showed as ready");
        final ServerGameLobby table = sessions.hostLobby();
        sessions.onMessage(host, JsonCodec.message("eventStart"));
        host.awaitMatching("draft", d -> d.getAsJsonArray("cards").size() > 0, "the host was never shown a pack");
        final BoosterDraftHost draftHost = table.getDraftHost();
        Assert.assertNotNull(draftHost);
        sessions.onMessage(host, JsonCodec.message("leaveLobby"));
        host.awaitMatching("hello", h -> !h.get("inLobby").getAsBoolean(), "the host never left the table");
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
        final String id = events.stored("Web test forests");
        final Recorder host = hostAt("lobby", "sealed");
        sessions.onMessage(host, hostAgain(id));
        host.awaitLobby(l -> l.getAsJsonObject("limited").has("activeEventId"), "the past event was never hosted again");
        sessions.onMessage(host, playerCount(5));
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 5, "the table never grew to five");
        sessions.onMessage(host, JsonCodec.message("decks"));
        final String key = keyOf(host, "Web test forests");
        for (int i = 0; i < 5; i++) {
            sessions.onMessage(host, setSeatDeck(i, key));
        }
        host.awaitLobby(l -> l.getAsJsonArray("problems").toString().contains("bench 1 more"),
                "five players were let into one match");
        sessions.onMessage(host, bench(4, true));
        final JsonObject ok = host.awaitLobby(l -> l.get("canStart").getAsBoolean());
        Assert.assertNotNull(ok, "the table could not start with one seat benched: " + host.latestTable().getAsJsonArray("problems"));
        final ServerGameLobby table = sessions.hostLobby();
        sessions.onMessage(host, JsonCodec.message("start"));
        host.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean(), "the match never started");
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
        final String id = events.stored("Web test later");
        final Recorder host = hostAt("lobby", "sealed");
        sessions.onMessage(host, hostAgain(id));
        host.awaitLobby(l -> l.getAsJsonObject("limited").has("activeEventId"), "the past event was never hosted again");
        sessions.onMessage(host, JsonCodec.message("decks"));
        final String key = keyOf(host, "Web test later");
        sessions.onMessage(host, setSeatDeck(0, key));
        sessions.onMessage(host, setSeatDeck(1, key));
        host.awaitLobby(l -> l.get("canStart").getAsBoolean(), "the match could not start");
        sessions.onMessage(host, JsonCodec.message("start"));
        host.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean(), "the match never started");
        host.forget();
        sessions.onMessage(host, JsonCodec.message("leave"));
        final JsonObject back = host.awaitLobby(l -> l.has("limited") && l.getAsJsonObject("limited").has("activeEventId"), "leaving the match lost the Limited table");
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
        final JsonObject dealt = guest.awaitMatching("deviceDeck", d -> d.get("id").getAsString().startsWith("event-"), "the guest was never sent its pool");
        final String id = dealt.get("id").getAsString();
        events.add(id.substring("event-".length()));
        final String text = dealt.get("text").getAsString();
        final String line = text.substring(text.indexOf("[Sideboard]")).split("\\r?\\n")[1];
        final String card = line.substring(line.indexOf(' ') + 1).split("\\|")[0];

        sessions.onMessage(guest, message("editorEdit", "op", "move", "name", card, "from", "Sideboard", "to", "Main", "count", 1));
        final JsonObject built = guest.awaitMatching("deviceDeck", d -> d.get("id").getAsString().equals(id) && !d.get("text").equals(dealt.get("text")), "the edit never reached the guest's browser");
        sessions.onMessage(guest, JsonCodec.message("editorClose"));
        guest.awaitMatching("editor", m -> !m.has("state"), "the pool's editor never closed");

        sessions.disconnected(guest);
        final Recorder again = connect("guest");
        again.awaitMatching("hello", h -> true, "the guest was never greeted");
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

    // Fails if an event's new decks keep the sleeves their seats sat down in, so two seats can wear the same one
    @Test(timeOut = 300_000)
    public void anEventDeckIsSleevedAfresh() throws Exception {
        final Recorder host = hostAt("invite", "sealed");
        final Recorder guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest"));
        host.awaitLobby(l -> seatNames(l).contains("Guest"), "the guest never sat down");
        sessions.onMessage(host, eventSetup(LimitedPoolType.Full.name(), null, 6));
        // Both seats in the same sleeve, so a sleeve dealt to either is one nobody wore
        sessions.onMessage(host, sleeve(0, 0));
        sessions.onMessage(guest, sleeve(1, 0));
        host.awaitLobby(l -> sleeves(l).equals(List.of(0, 0)), "the seats never wore the same sleeve");
        seeEvent(host);
        seeEvent(guest);
        sessions.onMessage(host, ready(true));
        sessions.onMessage(guest, ready(true));
        host.awaitLobby(l -> l.getAsJsonArray("seats").asList().stream()
                .allMatch(s -> s.getAsJsonObject().get("ready").getAsBoolean()), "the seats never showed as ready");
        sessions.onMessage(host, JsonCodec.message("eventStart"));
        final JsonObject dealt = host.awaitLobby(l -> !sleeves(l).contains(0) && !sleeves(l).get(0).equals(sleeves(l).get(1)));
        Assert.assertNotNull(dealt, "the pools' decks were not each given a sleeve of their own: " + host.latestTable());
        final JsonObject table = host.awaitLobby(l -> l.getAsJsonObject("limited").has("activeEventId"));
        events.add(table.getAsJsonObject("limited").get("activeEventId").getAsString());
    }

    private static JsonObject sleeve(final int seat, final int sleeve) {
        return message("setSeat", "index", seat, "sleeve", sleeve);
    }

    private static List<Integer> sleeves(final JsonObject table) {
        return table.getAsJsonArray("seats").asList().stream().map(s -> s.getAsJsonObject().get("sleeve").getAsInt()).toList();
    }

    // Fails if a player who closes their pool's editor still has to find their deck in the finder to sit with it
    @Test(timeOut = 300_000)
    public void aPoolTakesItsPlayersSeat() throws Exception {
        final Recorder[] both = startedEvent("sealed", eventSetup(LimitedPoolType.Full.name(), null, 6));
        for (final Recorder browser : both) {
            browser.awaitMatching("editor", m -> m.has("state"), "the pool's editor never opened");
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
