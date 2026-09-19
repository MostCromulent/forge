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

    @Test
    public void connectingSendsHello() throws Exception {
        final WebSession session = new WebSession(new WebGuiBase(), new LocalGame(), 60_000, () -> { });
        final Recorder r = new Recorder();
        session.connected(r);
        final JsonObject hello = r.await("hello");
        Assert.assertFalse(hello.get("inMatch").getAsBoolean());
    }

    @Test
    public void deckListAnswers() throws Exception {
        final WebSession session = new WebSession(new WebGuiBase(), new LocalGame(), 60_000, () -> { });
        final Recorder r = new Recorder();
        session.connected(r);
        session.onMessage(r, JsonCodec.message("decks"));
        Assert.assertTrue(r.await("decks").get("decks").isJsonArray());
    }

    @Test
    public void startWithUnknownDecksReportsAnError() throws Exception {
        final WebSession session = new WebSession(new WebGuiBase(), new LocalGame(), 60_000, () -> { });
        final Recorder r = new Recorder();
        session.connected(r);
        final JsonObject start = JsonCodec.message("start");
        start.addProperty("playerName", "Tester");
        start.addProperty("playerDeck", "nope");
        start.addProperty("aiDeck", "nope");
        session.onMessage(r, start);
        Assert.assertNotNull(r.await("error"));
    }

    @Test
    public void sessionWithNoBrowserQuits() throws Exception {
        final CountDownLatch quit = new CountDownLatch(1);
        new WebSession(new WebGuiBase(), new LocalGame(), 200, quit::countDown);
        Assert.assertTrue(quit.await(2, TimeUnit.SECONDS));
    }

    @Test
    public void idleSessionQuitsAndReconnectCancelsIt() throws Exception {
        final CountDownLatch quit = new CountDownLatch(1);
        final WebSession session = new WebSession(new WebGuiBase(), new LocalGame(), 200, quit::countDown);
        final Recorder first = new Recorder();
        session.connected(first);
        session.disconnected(first);
        session.connected(new Recorder());
        Assert.assertFalse(quit.await(500, TimeUnit.MILLISECONDS));
        final Recorder second = new Recorder();
        session.connected(second);
        session.disconnected(second);
        Assert.assertTrue(quit.await(2, TimeUnit.SECONDS));
    }
}
