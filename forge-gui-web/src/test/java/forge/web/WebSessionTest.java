package forge.web;

import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WebSessionTest {
    private static final class Recorder implements BrowserChannel {
        final List<JsonObject> got = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        @Override public void send(final JsonObject message) { got.add(message); }
        @Override public void close() { closed = true; }
        /** The most recent message of a type, which is what matters when hello is sent more than once. */
        JsonObject awaitLast(final String type) throws InterruptedException {
            JsonObject found = null;
            for (int i = 0; i < 200 && found == null; i++) {
                for (final JsonObject m : got) {
                    if (type.equals(m.get("t").getAsString())) {
                        found = m;
                    }
                }
                if (found == null) {
                    Thread.sleep(10);
                }
            }
            return found;
        }

        JsonObject await(final String type) throws InterruptedException {
            for (int i = 0; i < 200; i++) {
                for (final JsonObject m : got) {
                    if (type.equals(m.get("t").getAsString())) {
                        return m;
                    }
                }
                Thread.sleep(10);
            }
            return null;
        }
    }

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static WebSessions opened() {
        return new WebSessions(new WebGuiBase(), 60_000, () -> { });
    }

    /** Connects a browser and has it take the host's seat, which nobody gets by arriving. */
    private static void connectAsHost(final WebSessions sessions, final Recorder r, final String id) {
        sessions.connected(r, id, true);
        sessions.onMessage(r, JsonCodec.message("claimHost"));
    }

    @Test
    public void connectingSendsHello() throws Exception {
        final WebSessions sessions = opened();
        final Recorder r = new Recorder();
        connectAsHost(sessions, r, "host");
        final JsonObject hello = r.awaitLast("hello");
        Assert.assertFalse(hello.get("inMatch").getAsBoolean());
        Assert.assertTrue(hello.get("host").getAsBoolean());
    }

    @Test
    public void deckListAnswers() throws Exception {
        final WebSessions sessions = opened();
        final Recorder r = new Recorder();
        sessions.connected(r, "host", true);
        sessions.onMessage(r, JsonCodec.message("decks"));
        Assert.assertTrue(r.await("decks").get("decks").isJsonArray());
    }

    /** Fails if the host's seat is handed out by arriving, or if two browsers can both end up holding it. */
    @Test
    public void theFirstBrowserToAskGetsTheHostSeat() throws Exception {
        final WebSessions sessions = opened();
        final Recorder first = new Recorder();
        final Recorder second = new Recorder();
        sessions.connected(first, "first", true);
        sessions.connected(second, "second", true);
        Assert.assertFalse(first.awaitLast("hello").get("host").getAsBoolean(), "a browser hosted by arriving");
        Assert.assertTrue(first.awaitLast("hello").get("canClaimHost").getAsBoolean(), "the seat was not offered");

        sessions.onMessage(first, JsonCodec.message("claimHost"));
        Assert.assertTrue(first.awaitLast("hello").get("host").getAsBoolean(), "asking did not take the seat");

        sessions.onMessage(second, JsonCodec.message("claimHost"));
        Assert.assertFalse(second.awaitLast("hello").get("host").getAsBoolean(), "two browsers took the same seat");
        Assert.assertNotNull(second.await("error"), "the second browser was not told why");
    }

    @Test
    public void startWithUnknownDecksReportsAnError() throws Exception {
        final WebSessions sessions = opened();
        final Recorder r = new Recorder();
        connectAsHost(sessions, r, "host");
        final JsonObject start = JsonCodec.message("start");
        start.addProperty("playerName", "Tester");
        start.addProperty("playerDeck", "nope");
        start.addProperty("aiDeck", "nope");
        sessions.onMessage(r, start);
        Assert.assertNotNull(r.await("error"));
    }

    @Test
    public void aBrowserThatNeverOpensQuits() throws Exception {
        final CountDownLatch quit = new CountDownLatch(1);
        new WebSessions(new WebGuiBase(), 200, quit::countDown);
        Assert.assertTrue(quit.await(2, TimeUnit.SECONDS));
    }

    /** Fails if the host closing their browser ends a game a guest is still playing. */
    @Test
    public void quittingWaitsForTheLastBrowserToGo() throws Exception {
        final CountDownLatch quit = new CountDownLatch(1);
        final WebSessions sessions = new WebSessions(new WebGuiBase(), 200, quit::countDown);
        final Recorder host = new Recorder();
        final Recorder guest = new Recorder();
        sessions.connected(host, "host", true);
        sessions.connected(guest, "guest", true);

        sessions.disconnected(host);
        Assert.assertFalse(quit.await(600, TimeUnit.MILLISECONDS),
                "Forge quit while a guest was still attached");

        sessions.disconnected(guest);
        Assert.assertTrue(quit.await(2, TimeUnit.SECONDS),
                "Forge did not quit once every browser had gone");
    }

    /** Fails if a browser that comes back within the countdown does not stop it. */
    @Test
    public void reconnectingStopsTheCountdown() throws Exception {
        final CountDownLatch quit = new CountDownLatch(1);
        final WebSessions sessions = new WebSessions(new WebGuiBase(), 400, quit::countDown);
        final Recorder first = new Recorder();
        sessions.connected(first, "host", true);
        sessions.disconnected(first);
        sessions.connected(new Recorder(), "host", true);
        Assert.assertFalse(quit.await(900, TimeUnit.MILLISECONDS),
                "Forge quit although a browser had come back");
    }

    /** Fails if a browser on a guest's link can take the host's seat, which could stop the server or set the table. */
    @Test
    public void aGuestLinkCannotTakeTheHostSeat() throws Exception {
        final WebSessions sessions = opened();
        final Recorder guest = new Recorder();
        sessions.connected(guest, "guest", false);
        Assert.assertFalse(guest.awaitLast("hello").get("canClaimHost").getAsBoolean(), "a guest was offered the host's seat");
        sessions.onMessage(guest, JsonCodec.message("claimHost"));
        Assert.assertNotNull(guest.await("error"), "a guest asking for the host's seat was not told no");
        Assert.assertFalse(guest.awaitLast("hello").get("host").getAsBoolean(), "a guest took the host's seat");

        // The same browser id on the host's link is a different browser, and may still take the seat
        final Recorder host = new Recorder();
        connectAsHost(sessions, host, "guest");
        Assert.assertTrue(host.awaitLast("hello").get("host").getAsBoolean());
    }

    /** Fails if anyone with a link can make the server keep track of browsers without end. */
    @Test
    public void browsersBeyondTheLimitAreTurnedAwayUntilOthersLeave() {
        final WebSessions sessions = opened();
        final List<Recorder> attached = new java.util.ArrayList<>();
        for (int i = 0; i < WebSessions.MOST_SESSIONS; i++) {
            final Recorder r = new Recorder();
            sessions.connected(r, "b" + i, false);
            attached.add(r);
        }
        final Recorder oneTooMany = new Recorder();
        sessions.connected(oneTooMany, "extra", false);
        Assert.assertTrue(oneTooMany.closed, "a browser past the limit was let in");
        Assert.assertFalse(attached.get(0).closed);

        // One that has gone and holds nothing makes room
        sessions.disconnected(attached.get(0));
        final Recorder next = new Recorder();
        sessions.connected(next, "extra", false);
        Assert.assertFalse(next.closed, "a browser was turned away although another had left");
        sessions.shutdown();
    }
}
