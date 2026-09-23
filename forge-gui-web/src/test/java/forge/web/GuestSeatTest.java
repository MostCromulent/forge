package forge.web;

import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
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
        final List<JsonObject> got = new CopyOnWriteArrayList<>();

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
            return awaitLobby(l -> l.get("mySeat").getAsInt() >= 0);
        }

        /**
         * Waits until the table as it stands now is the one wanted, and answers it. The lobby is pushed on every
         * change, and one choice can travel as several changes (a deck, then being ready), so an earlier message can
         * describe a table that has already moved on; only the latest counts.
         */
        JsonObject awaitLobby(final Predicate<JsonObject> wanted) throws InterruptedException {
            for (int i = 0; i < WAIT_MILLIS / 20; i++) {
                JsonObject latest = null;
                for (final JsonObject m : got) {
                    if ("lobby".equals(m.get("t").getAsString()) && m.has("table")) {
                        latest = m.getAsJsonObject("table");
                    }
                }
                if (latest != null && wanted.test(latest)) {
                    return latest;
                }
                Thread.sleep(20);
            }
            return null;
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

    /** Every browser a test connects. Each is let go after its test, or a guest from one test takes the seat the next
     *  test's guest wants: a waiting guest takes a seat by itself whenever the host opens a game. */
    private final List<Recorder> browsers = new CopyOnWriteArrayList<>();

    private Recorder connect(final String id) {
        final Recorder browser = new Recorder();
        browsers.add(browser);
        sessions.connected(browser, id);
        return browser;
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

    /**
     * Fails if a guest cannot reach the host's game: if it is handed the host's own seat, if it is left
     * without one, if the host's table never shows it arriving, or if closing the game leaves the guest
     * looking at one that is gone. One game serves all of it, because stopping and restarting the loopback
     * server mid-test races with its own shutdown.
     */
    @Test(timeOut = 120_000)
    public void aGuestSitsDownWithTheHostAndLeavesWithTheGame() throws Exception {
        final Recorder hostBrowser = connect("host");
        sessions.onMessage(hostBrowser, JsonCodec.message("claimHost"));
        Assert.assertNotNull(hostBrowser.awaitMatching("hello", h -> h.get("host").getAsBoolean()),
                "asking for the host's seat did not take it");
        // Another test may have left the host at a table, which a reconnect is shown again; only the new one counts
        hostBrowser.forget();

        sessions.onMessage(hostBrowser, named("Host"));
        sessions.onMessage(hostBrowser, JsonCodec.message("invite"));
        final JsonObject hosted = hostBrowser.awaitLobbyWithSeat();
        Assert.assertNotNull(hosted, "the host never got a seat in its own game");
        Assert.assertTrue(hosted.get("shareable").getAsBoolean(), "an invited game offered no link");

        // Every browser shares the server's preferences, so a guest has no name until it chooses one, and two
        // players of one name cannot share a game
        final Recorder guestBrowser = connect("guest");
        final JsonObject greeted = guestBrowser.await("hello");
        Assert.assertNotNull(greeted, "the guest was never greeted");
        Assert.assertFalse(greeted.has("playerName"), "the guest was given a name it never chose");
        sessions.onMessage(guestBrowser, named("host"));
        Assert.assertNotNull(guestBrowser.awaitMatching("error", e -> e.get("message").getAsString().contains("already called")),
                "the guest was let play under the host's name");
        Assert.assertTrue(guestBrowser.got.stream().noneMatch(m -> "lobby".equals(m.get("t").getAsString())),
                "the guest took a seat before it had a name");
        sessions.onMessage(guestBrowser, named("Guest"));
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

        // A reload lands back at the same table, which the browser cannot draw until it is sent again
        sessions.disconnected(guestBrowser);
        final Recorder reloaded = connect("guest");
        final JsonObject again = reloaded.awaitLobbyWithSeat();
        Assert.assertNotNull(again, "a guest that reloaded in match setup was never shown the table again");
        Assert.assertEquals(again.get("mySeat").getAsInt(), guestSeat, "a guest that reloaded lost its seat");
        sessions.disconnected(reloaded);
        sessions.connected(guestBrowser, "guest");

        // The guest's deck is theirs, chosen from their own list, and the host has to see it or cannot start
        sessions.onMessage(guestBrowser, JsonCodec.message("decks"));
        final String deck = legalDeck(guestBrowser.await("decks"));
        final JsonObject choose = seatMessage("setSeat", guestSeat);
        choose.addProperty("deck", deck);
        sessions.onMessage(guestBrowser, choose);
        final JsonObject table = hostBrowser.awaitLobby(l -> l.getAsJsonArray("seats").size() > guestSeat
                && l.getAsJsonArray("seats").get(guestSeat).getAsJsonObject().has("deckName"));
        Assert.assertNotNull(table, "the host's table never showed the guest's deck");
        // The host's own seat has no deck yet, and is named as "You". The table that shows the guest's deck is the
        // one to read, because the host's copy of the table can trail the server's by an update.
        for (final var problem : table.getAsJsonArray("problems")) {
            Assert.assertFalse(problem.getAsString().endsWith(" has no deck."),
                    "the host still counted the guest as having no deck: " + problem.getAsString());
        }

        // The hello sent before the guest sat down also says inLobby false, so only what follows counts
        guestBrowser.forget();
        sessions.onMessage(hostBrowser, JsonCodec.message("leaveLobby"));
        Assert.assertNotNull(guestBrowser.awaitMatching("hello", h -> !h.get("inLobby").getAsBoolean()),
                "the guest was left in a lobby the host had closed");
    }

    /** Fails if the guest's browser stays in match setup when the host starts the match, or is never shown the table. */
    @Test(timeOut = 120_000)
    public void aGuestFollowsTheHostIntoTheMatch() throws Exception {
        final Recorder host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        host.forget();
        sessions.onMessage(host, JsonCodec.message("invite"));
        final JsonObject hosted = host.awaitLobbyWithSeat();
        Assert.assertNotNull(hosted, "the host never got a seat in its own game");

        final Recorder guest = connect("player");
        sessions.onMessage(guest, named("Player"));
        final JsonObject seated = guest.awaitLobbyWithSeat();
        Assert.assertNotNull(seated, "the guest never took a seat" + diagnosis(host, guest));

        sessions.onMessage(host, JsonCodec.message("decks"));
        final String deck = legalDeck(host.await("decks"));
        for (final Recorder browser : List.of(host, guest)) {
            final JsonObject choose = seatMessage("setSeat", (browser == host ? hosted : seated).get("mySeat").getAsInt());
            choose.addProperty("deck", deck);
            sessions.onMessage(browser, choose);
        }
        Assert.assertNotNull(host.awaitLobby(l -> l.get("canStart").getAsBoolean()),
                "the host could not start once both seats had a deck");

        guest.forget();
        final JsonObject start = JsonCodec.message("start");
        start.addProperty("spectate", false);
        sessions.onMessage(host, start);
        Assert.assertNotNull(guest.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean()),
                "the guest was left in match setup when the host started the match" + diagnosis(host, guest));
        Assert.assertNotNull(guest.awaitMatching("state", m -> m.get("full").getAsBoolean()),
                "the guest was taken into the match but never shown the table");
    }

    /**
     * Fails if turning a seat between a computer and an open one does nothing. A slot edited straight on the
     * server does not announce itself, so the browser's copy keeps the old answer unless the table is resent.
     */
    @Test(timeOut = 120_000)
    public void aSeatTurnsBetweenComputerAndOpen() throws Exception {
        final Recorder browser = connect("host");
        sessions.onMessage(browser, JsonCodec.message("claimHost"));
        browser.forget();
        sessions.onMessage(browser, JsonCodec.message("lobby"));
        // Whichever seat the computer holds, because another browser may be sitting in one of them
        final JsonObject opened = browser.awaitLobby(l -> seatOfType(l, "AI") >= 0);
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

    /** The first deck in a list that is built and legal, rather than generated when the game starts. */
    private static String legalDeck(final JsonObject decks) {
        Assert.assertNotNull(decks, "no deck list arrived");
        for (final var d : decks.getAsJsonArray("decks")) {
            final JsonObject deck = d.getAsJsonObject();
            if (!deck.has("problem") && !(deck.has("generated") && deck.get("generated").getAsBoolean())) {
                return deck.get("key").getAsString();
            }
        }
        throw new AssertionError("no legal deck to choose");
    }

    private static JsonObject named(final String name) {
        final JsonObject m = JsonCodec.message("setName");
        m.addProperty("name", name);
        return m;
    }

    /** What each browser was told last and what every thread is doing, for a wait that ran out. */
    private static String diagnosis(final Recorder... browsers) {
        final StringBuilder out = new StringBuilder();
        for (final Recorder b : browsers) {
            out.append("\n--- last messages:\n");
            final List<JsonObject> got = b.got;
            for (final JsonObject m : got.subList(Math.max(0, got.size() - 4), got.size())) {
                final String text = m.toString();
                out.append(text, 0, Math.min(300, text.length())).append('\n');
            }
        }
        for (final java.lang.management.ThreadInfo t
                : java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            final String name = t.getThreadName();
            if (name.startsWith("Web") || name.startsWith("Game") || name.startsWith("main")) {
                out.append("\n--- ").append(name).append(' ').append(t.getThreadState());
                for (final StackTraceElement e : t.getStackTrace()) {
                    out.append("\n    ").append(e);
                }
            }
        }
        return out.toString();
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
        // The same id as the other test, because the host's seat is held by whichever session claimed it
        final Recorder browser = connect("host");
        sessions.onMessage(browser, JsonCodec.message("claimHost"));
        browser.forget();
        sessions.onMessage(browser, JsonCodec.message("lobby"));
        Assert.assertNotNull(browser.awaitLobbyWithSeat(), "no game was opened" + diagnosis(browser));

        final JsonObject pick = JsonCodec.message("setFormat");
        pick.addProperty("format", "Commander");
        sessions.onMessage(browser, pick);
        Assert.assertNotNull(browser.awaitLobby(l -> l.has("format") && "Commander".equals(l.get("format").getAsString())),
                "the lobby stayed on Constructed after Commander was picked");
    }
}
