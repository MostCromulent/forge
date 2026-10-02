package forge.web;

import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * A second browser taking a seat in the host's game. Both live in this process and reach the same loopback
 * server, which is the whole of link-only multiplayer: nothing but the web port is ever exposed.
 */
public class GuestSeatTest extends SessionsTest {
    @Override
    boolean slow() {
        return true;
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
        hostBrowser.awaitMatching("hello", h -> h.get("host").getAsBoolean(),
                "asking for the host's seat did not take it");
        // Another test may have left the host at a table, which a reconnect is shown again; only the new one counts
        send(hostBrowser, message("setName", "name", "Host"));
        sessions.onMessage(hostBrowser, JsonCodec.message("invite"));
        final JsonObject hosted = hostBrowser.awaitLobbyWithSeat();
        Assert.assertNotNull(hosted, "the host never got a seat in its own game");
        Assert.assertTrue(hosted.get("shareable").getAsBoolean(), "an invited game offered no link");

        // Every browser shares the server's preferences, so a guest has no name until it chooses one, and two
        // players of one name cannot share a game
        final Recorder guestBrowser = connect("guest");
        final JsonObject greeted = guestBrowser.awaitNewest("hello", "the guest was never greeted");
        Assert.assertFalse(greeted.has("playerName"), "the guest was given a name it never chose");
        sessions.onMessage(guestBrowser, message("setName", "name", "host"));
        guestBrowser.awaitMatching("error", e -> e.get("message").getAsString().contains("already called"),
                "the guest was let play under the host's name");
        Assert.assertTrue(guestBrowser.got.stream().noneMatch(m -> "lobby".equals(m.get("t").getAsString())),
                "the guest took a seat before it had a name");
        sessions.onMessage(guestBrowser, message("setName", "name", "Guest"));
        final JsonObject seated = guestBrowser.awaitLobbyWithSeat();
        Assert.assertNotNull(seated, "the guest never took a seat");
        Assert.assertFalse(seated.get("host").getAsBoolean(), "the guest was treated as the host");
        Assert.assertNotEquals(seated.get("mySeat").getAsInt(), hosted.get("mySeat").getAsInt(),
                "the guest was given the host's seat");

        // The host's table is pushed on every change, so the arrival has to show up there without being asked
        final int guestSeat = seated.get("mySeat").getAsInt();
        hostBrowser.awaitLobby(l -> l.getAsJsonArray("seats").size() > guestSeat
                        && "REMOTE".equals(l.getAsJsonArray("seats").get(guestSeat).getAsJsonObject()
                        .get("type").getAsString()),
                "the host's table never showed the guest arriving");

        // A reload lands back at the same table, which the browser cannot draw until it is sent again
        sessions.disconnected(guestBrowser);
        final Recorder reloaded = connect("guest");
        final JsonObject again = reloaded.awaitLobbyWithSeat();
        Assert.assertNotNull(again, "a guest that reloaded in match setup was never shown the table again");
        Assert.assertEquals(again.get("mySeat").getAsInt(), guestSeat, "a guest that reloaded lost its seat");
        sessions.disconnected(reloaded);
        sessions.connected(guestBrowser, "guest", false);

        // The guest's deck is theirs, chosen from their own list, and the host has to see it or cannot start
        sessions.onMessage(guestBrowser, JsonCodec.message("decks"));
        final String deck = legalDeck(guestBrowser.awaitNewest("decks"));
        final JsonObject choose = message("setSeat", "index", guestSeat);
        choose.addProperty("deck", deck);
        sessions.onMessage(guestBrowser, choose);
        final JsonObject table = hostBrowser.awaitLobby(l -> l.getAsJsonArray("seats").size() > guestSeat
                && l.getAsJsonArray("seats").get(guestSeat).getAsJsonObject().has("deckName"), "the host's table never showed the guest's deck");
        // The host's own seat has no deck yet, and is named as "You". The table that shows the guest's deck is the
        // one to read, because the host's copy of the table can trail the server's by an update.
        for (final var problem : table.getAsJsonArray("problems")) {
            Assert.assertFalse(problem.getAsString().endsWith(" has no deck."),
                    "the host still counted the guest as having no deck: " + problem.getAsString());
        }

        // The hello sent before the guest sat down also says inLobby false, so only what follows counts
        guestBrowser.forget();
        sessions.onMessage(hostBrowser, JsonCodec.message("leaveLobby"));
        guestBrowser.awaitMatching("hello", h -> !h.get("inLobby").getAsBoolean(),
                "the guest was left in a lobby the host had closed");
    }

    /** Fails if the guest's browser stays in match setup when the host starts the match, or is never shown the table. */
    @Test(timeOut = 120_000)
    public void aGuestFollowsTheHostIntoTheMatch() throws Exception {
        final Recorder host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        send(host, JsonCodec.message("invite"));
        final JsonObject hosted = host.awaitLobbyWithSeat();
        Assert.assertNotNull(hosted, "the host never got a seat in its own game");

        final Recorder guest = connect("player");
        sessions.onMessage(guest, message("setName", "name", "Player"));
        final JsonObject seated = guest.awaitLobbyWithSeat();
        Assert.assertNotNull(seated, "the guest never took a seat" + diagnosis(host, guest));

        sessions.onMessage(host, JsonCodec.message("decks"));
        final String deck = legalDeck(host.awaitNewest("decks"));
        for (final Recorder browser : List.of(host, guest)) {
            final JsonObject choose = message("setSeat", "index", (browser == host ? hosted : seated).get("mySeat").getAsInt());
            choose.addProperty("deck", deck);
            sessions.onMessage(browser, choose);
            sessions.onMessage(browser, message("ready", "ready", true));
        }
        host.awaitLobby(l -> l.get("canStart").getAsBoolean(),
                "the host could not start once both seats had a deck and were ready");

        // Stops set in match setup are the player's, and the match opens with them rather than correcting them later
        final JsonObject stops = JsonCodec.message("setStops");
        stops.addProperty("mine", true);
        final com.google.gson.JsonArray phases = new com.google.gson.JsonArray();
        phases.add("MAIN2");
        stops.add("phases", phases);
        sessions.onMessage(guest, stops);

        guest.forget();
        sessions.onMessage(host, message("start", "spectate", false));
        Assert.assertNotNull(guest.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean()),
                "the guest was left in match setup when the host started the match" + diagnosis(host, guest));
        guest.awaitMatching("state", m -> m.get("full").getAsBoolean(),
                "the guest was taken into the match but never shown the table");
        final JsonObject controls = guest.awaitNewest("controls", "the guest was never sent its controls");
        Assert.assertEquals(controls.get("myStops").toString(), "[\"MAIN2\"]",
                "the match did not open with the stops the guest set in match setup");
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

    // Fails if lowering the count removes a seat a person holds, or keeps an open seat over a computer's
    @Test(timeOut = 120_000)
    public void aPersonKeepsTheirSeat() throws Exception {
        final Recorder host = hostAt("invite");
        sessions.onMessage(host, message("setPlayerCount", "count", 4));
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 4, "the table never grew to four");
        final Recorder guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest"));
        host.awaitLobby(l -> seatNames(l).contains("Guest"), "the guest never sat down");

        sessions.onMessage(host, message("setPlayerCount", "count", 2));
        final JsonObject two = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 2, "the table never shrank to two");
        Assert.assertTrue(seatNames(two).contains("Guest"), "the guest lost their seat: " + seatNames(two));

        sessions.onMessage(host, message("setPlayerCount", "count", 1));
        Thread.sleep(500);
        Assert.assertEquals(host.latestTable().getAsJsonArray("seats").size(), 2, "a table went below two players");
    }
}
