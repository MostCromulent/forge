package forge.web;

import com.google.gson.JsonObject;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.net.draft.BoosterDraftHost;
import forge.gamemodes.net.server.ServerGameLobby;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Draft and sealed events at a web table, set up and started by the host with guests seated, as desktop's lobby runs them. */
public class OnlineEventTest extends SessionsTest {
    @Override
    boolean slow() {
        return true;
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

    /** A browser must see the table's event before it readies, because learning of the event deals the seat afresh and unreadies it. */
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

        send(host, JsonCodec.message("eventStart"));
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

    // Fails if no pack reaches a seat, one pick counts twice, a pick on a pack that has moved on counts, or the other seat never sees the pick
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
}
