package forge.web;

import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * A second browser taking a seat in the host's game. Both live in this process and reach the same loopback
 * server, which is the whole of link-only multiplayer: nothing but the web port is ever exposed.
 */
public class GuestSeatTest {
    private static final int WAIT_MILLIS = 20_000;

    private static final class Recorder implements BrowserChannel {
        private final List<JsonObject> got = new CopyOnWriteArrayList<>();

        @Override
        public void send(final JsonObject message) {
            got.add(message);
        }

        /** The most recent message of a type, or null if none arrived in time. */
        JsonObject await(final String type) throws InterruptedException {
            for (int i = 0; i < WAIT_MILLIS / 20; i++) {
                JsonObject found = null;
                for (final JsonObject m : got) {
                    if (type.equals(m.get("t").getAsString())) {
                        found = m;
                    }
                }
                if (found != null) {
                    return found;
                }
                Thread.sleep(20);
            }
            return null;
        }

        JsonObject awaitLobbyWithSeat() throws InterruptedException {
            return awaitLobby(l -> l.get("open").getAsBoolean() && l.get("mySeat").getAsInt() >= 0);
        }

        /** The lobby is pushed on every change, so a test waits for the one it is after rather than the latest. */
        JsonObject awaitLobby(final Predicate<JsonObject> wanted) throws InterruptedException {
            return awaitMatching("lobby", wanted);
        }

        /** Drops what has been said so far, so a later wait cannot be satisfied by an earlier message. */
        void forget() {
            got.clear();
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
    }

    private WebSessions sessions;

    /** One server for the class: stopping and restarting it between tests races with its own shutdown. */
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
        sessions = new WebSessions(new WebGuiBase(), 120_000, () -> { });
    }

    @AfterClass
    public void tearDown() {
        if (sessions != null) {
            sessions.shutdown();
            sessions = null;
        }
    }

    /**
     * Fails if a guest cannot reach the host's game: if it is handed the host's own seat, if it is left
     * without one, if the host's table never shows it arriving, or if closing the game leaves the guest
     * looking at one that is gone. One game serves all of it, because stopping and restarting the loopback
     * server mid-test races with its own shutdown.
     */
    @Test(timeOut = 120_000)
    public void aGuestSitsDownWithTheHostAndLeavesWithTheGame() throws Exception {
        final Recorder hostBrowser = new Recorder();
        sessions.connected(hostBrowser, "host", true);
        Assert.assertTrue(hostBrowser.await("hello").get("host").getAsBoolean(), "the local browser did not host");

        sessions.onMessage(hostBrowser, JsonCodec.message("invite"));
        final JsonObject hosted = hostBrowser.awaitLobbyWithSeat();
        Assert.assertNotNull(hosted, "the host never got a seat in its own game");
        Assert.assertTrue(hosted.get("shareable").getAsBoolean(), "an invited game offered no link");

        final Recorder guestBrowser = new Recorder();
        sessions.connected(guestBrowser, "guest", false);
        final JsonObject seated = guestBrowser.awaitLobbyWithSeat();
        Assert.assertNotNull(seated, "the guest never took a seat");
        Assert.assertFalse(seated.get("host").getAsBoolean(), "the guest was treated as the host");
        Assert.assertNotEquals(seated.get("mySeat").getAsInt(), hosted.get("mySeat").getAsInt(),
                "the guest was given the host's seat");

        // The host's table is pushed on every change, so the arrival has to show up there without being asked
        final int guestSeat = seated.get("mySeat").getAsInt();
        Assert.assertNotNull(hostBrowser.awaitLobby(l -> l.getAsJsonArray("seats").size() > guestSeat
                        && "REMOTE".equals(l.getAsJsonArray("seats").get(guestSeat).getAsJsonObject()
                        .get("type").getAsString())),
                "the host's table never showed the guest arriving");

        // The hello sent before the guest sat down also says inLobby false, so only what follows counts
        guestBrowser.forget();
        sessions.onMessage(hostBrowser, JsonCodec.message("leaveLobby"));
        Assert.assertNotNull(guestBrowser.awaitMatching("hello", h -> !h.get("inLobby").getAsBoolean()),
                "the guest was left in a lobby the host had closed");
    }

    /**
     * Fails if turning a seat between a computer and an open one does nothing. A slot edited straight on the
     * server does not announce itself, so the browser's copy keeps the old answer unless the table is resent.
     */
    @Test(timeOut = 120_000)
    public void aSeatTurnsBetweenComputerAndOpen() throws Exception {
        final Recorder browser = new Recorder();
        sessions.connected(browser, "host", true);
        browser.forget();
        sessions.onMessage(browser, JsonCodec.message("lobby"));
        // Whichever seat the computer holds, because another browser may be sitting in one of them
        final JsonObject opened = browser.awaitLobby(l -> l.get("open").getAsBoolean() && seatOfType(l, "AI") >= 0);
        Assert.assertNotNull(opened, "no game opened with a seat held by a computer");
        final int seat = seatOfType(opened, "AI");

        browser.forget();
        sessions.onMessage(browser, seatMessage("openSeat", seat));
        Assert.assertNotNull(browser.awaitLobby(l -> "OPEN".equals(typeAt(l, seat))), "the seat never opened");

        browser.forget();
        sessions.onMessage(browser, seatMessage("aiSeat", seat));
        Assert.assertNotNull(browser.awaitLobby(l -> "AI".equals(typeAt(l, seat))),
                "the seat never went back to a computer");
    }

    private static JsonObject seatMessage(final String type, final int index) {
        final JsonObject m = JsonCodec.message(type);
        m.addProperty("index", index);
        return m;
    }

    private static String typeAt(final JsonObject lobby, final int seat) {
        final var seats = lobby.getAsJsonArray("seats");
        return seat < seats.size() ? seats.get(seat).getAsJsonObject().get("type").getAsString() : null;
    }

    private static int seatOfType(final JsonObject lobby, final String type) {
        final var seats = lobby.getAsJsonArray("seats");
        for (int i = 0; i < seats.size(); i++) {
            if (type.equals(seats.get(i).getAsJsonObject().get("type").getAsString())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Fails if picking a format does nothing. A lobby's game type is a plain field its serialised data leaves
     * out, so a client that reads the type instead of the applied variants never leaves Constructed.
     */
    @Test(timeOut = 120_000)
    public void pickingCommanderChangesTheFormat() throws Exception {
        // The same id as the other test, because the host is whichever session claimed that place first
        final Recorder browser = new Recorder();
        sessions.connected(browser, "host", true);
        sessions.onMessage(browser, JsonCodec.message("lobby"));
        Assert.assertNotNull(browser.awaitLobbyWithSeat(), "no game was opened");

        final JsonObject pick = JsonCodec.message("setFormat");
        pick.addProperty("format", "Commander");
        sessions.onMessage(browser, pick);
        Assert.assertNotNull(browser.awaitLobby(l -> "Commander".equals(l.get("format").getAsString())),
                "the lobby stayed on Constructed after Commander was picked");
    }
}
