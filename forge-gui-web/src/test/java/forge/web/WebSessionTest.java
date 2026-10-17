package forge.web;

import forge.web.protocol.JsonCodec;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;


public class WebSessionTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static WebSessions opened() {
        return new WebSessions(new WebGuiBase(), 60_000, () -> { });
    }

    /** Connects a browser and has it claim the host's seat. No browser gets the seat just by connecting. */
    private static void connectAsHost(final WebSessions sessions, final TestBrowser r, final String id) {
        sessions.connected(r, id, true);
        sessions.onMessage(r, JsonCodec.message("claimHost"));
    }

    /** Fails if a browser gets the host's seat just by connecting, or if two browsers can both hold it. */
    @Test
    public void theFirstBrowserToAskGetsTheHostSeat() throws Exception {
        final WebSessions sessions = opened();
        final TestBrowser first = new TestBrowser();
        final TestBrowser second = new TestBrowser();
        sessions.connected(first, "first", true);
        sessions.connected(second, "second", true);
        Assert.assertFalse(first.awaitNewest("hello").get("host").getAsBoolean(), "a browser hosted by arriving");
        Assert.assertTrue(first.awaitNewest("hello").get("canClaimHost").getAsBoolean(), "the seat was not offered");

        sessions.onMessage(first, JsonCodec.message("claimHost"));
        Assert.assertTrue(first.awaitNewest("hello").get("host").getAsBoolean(), "asking did not take the seat");

        sessions.onMessage(second, JsonCodec.message("claimHost"));
        Assert.assertFalse(second.awaitNewest("hello").get("host").getAsBoolean(), "two browsers took the same seat");
        Assert.assertNotNull(second.awaitMatching("error", m -> true), "the second browser was not told why");
    }

    /** Fails if a browser on a guest's link can take the host's seat. The host's seat can stop the server or set up the table. */
    @Test
    public void aGuestLinkCannotTakeTheHostSeat() throws Exception {
        final WebSessions sessions = opened();
        final TestBrowser guest = new TestBrowser();
        sessions.connected(guest, "guest", false);
        Assert.assertFalse(guest.awaitNewest("hello").get("canClaimHost").getAsBoolean(), "a guest was offered the host's seat");
        sessions.onMessage(guest, JsonCodec.message("claimHost"));
        Assert.assertNotNull(guest.awaitMatching("error", m -> true), "a guest asking for the host's seat was not told no");
        Assert.assertFalse(guest.awaitNewest("hello").get("host").getAsBoolean(), "a guest took the host's seat");

        // The same browser id on the host's link counts as a different browser, and can still take the seat
        final TestBrowser host = new TestBrowser();
        connectAsHost(sessions, host, "guest");
        Assert.assertTrue(host.awaitNewest("hello").get("host").getAsBoolean());
    }

    /** Fails if anyone with a link can make the server track an unlimited number of browsers. */
    @Test
    public void browsersBeyondTheLimitAreTurnedAwayUntilOthersLeave() {
        final WebSessions sessions = opened();
        final List<TestBrowser> attached = new java.util.ArrayList<>();
        for (int i = 0; i < WebSessions.MOST_SESSIONS; i++) {
            final TestBrowser r = new TestBrowser();
            sessions.connected(r, "b" + i, false);
            attached.add(r);
        }
        final TestBrowser oneTooMany = new TestBrowser();
        sessions.connected(oneTooMany, "extra", false);
        Assert.assertTrue(oneTooMany.closed, "a browser past the limit was let in");
        Assert.assertFalse(attached.get(0).closed);

        // A browser that has disconnected and holds nothing frees a place
        sessions.disconnected(attached.get(0));
        final TestBrowser next = new TestBrowser();
        sessions.connected(next, "extra", false);
        Assert.assertFalse(next.closed, "a browser was turned away although another had left");
        sessions.shutdown();
    }
}
