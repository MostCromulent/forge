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
        @Override public void send(final JsonObject message) { got.add(message); }
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

    /** The host's browser, which is the one on this machine. */
    private static WebSessions opened() {
        return new WebSessions(new WebGuiBase(), 60_000, () -> { });
    }

    @Test
    public void connectingSendsHello() throws Exception {
        final WebSessions sessions = opened();
        final Recorder r = new Recorder();
        sessions.connected(r, "host", true);
        final JsonObject hello = r.await("hello");
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

    /** Fails if a browser off this machine could take the host's place and set the table. */
    @Test
    public void aRemoteBrowserCannotHost() throws Exception {
        final WebSessions sessions = opened();
        final Recorder guest = new Recorder();
        sessions.connected(guest, "guest", false);
        Assert.assertFalse(guest.await("hello").get("host").getAsBoolean());
        // The first browser on this machine takes the host's place, even though a guest was connected first
        final Recorder late = new Recorder();
        sessions.connected(late, "late", true);
        Assert.assertTrue(late.await("hello").get("host").getAsBoolean());
    }

    @Test
    public void startWithUnknownDecksReportsAnError() throws Exception {
        final WebSessions sessions = opened();
        final Recorder r = new Recorder();
        sessions.connected(r, "host", true);
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
        sessions.connected(guest, "guest", false);

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
}
