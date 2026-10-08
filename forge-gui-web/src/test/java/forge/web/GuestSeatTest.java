package forge.web;

import com.google.gson.JsonObject;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.gamemodes.match.HostedMatch;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** A second browser taking a seat in the host's game, with both in this process and reaching the same loopback server. */
public class GuestSeatTest extends SessionsTest {
    @Override
    boolean slow() {
        return true;
    }

    /** Fails if a guest cannot reach the host's game, in one game because restarting the loopback server mid-test races its shutdown. */
    @Test(timeOut = 120_000)
    public void aGuestSitsDownWithTheHostAndLeavesWithTheGame() throws Exception {
        final TestBrowser hostBrowser = connect("host");
        sessions.onMessage(hostBrowser, JsonCodec.message("claimHost"));
        hostBrowser.awaitMatching("hello", h -> h.get("host").getAsBoolean(),
                "asking for the host's seat did not take it");
        // Another test may have left the host at a table, which a reconnect is shown again; only the new one counts
        send(hostBrowser, message("setName", "name", "Host"));
        sessions.onMessage(hostBrowser, JsonCodec.message("invite"));
        final JsonObject hosted = hostBrowser.awaitLobbyWithSeat();
        Assert.assertNotNull(hosted, "the host never got a seat in its own game");
        Assert.assertTrue(hosted.get("shareable").getAsBoolean(), "an invited game offered no link");

        // Every browser shares the server's preferences, so a guest has no name until it chooses one
        final TestBrowser guestBrowser = connect("guest");
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
        final TestBrowser reloaded = connect("guest");
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
        // The table that shows the guest's deck is the one to read, because the host's copy can trail the server's by an update
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
        final TestBrowser host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        send(host, JsonCodec.message("invite"));
        final JsonObject hosted = host.awaitLobbyWithSeat();
        Assert.assertNotNull(hosted, "the host never got a seat in its own game");

        final TestBrowser guest = connect("player");
        sessions.onMessage(guest, message("setName", "name", "Player"));
        final JsonObject seated = guest.awaitLobbyWithSeat();
        Assert.assertNotNull(seated, "the guest never took a seat" + diagnosis(host, guest));

        sessions.onMessage(host, JsonCodec.message("decks"));
        final String deck = legalDeck(host.awaitNewest("decks"));
        for (final TestBrowser browser : List.of(host, guest)) {
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

    /** Fails if the host cannot give a disconnected guest's seat at the table to another browser, the browser that left gets it back, a reload loses it, or taking it away leaves the seat or the name held. */
    @Test(timeOut = 120_000)
    public void theHostSettlesADisconnectedSeatAtTheTable() throws Exception {
        final TestBrowser host = hostAt("invite");
        final TestBrowser first = connect("first");
        sessions.onMessage(first, message("setName", "name", "Ann"));
        final int seat = first.awaitLobbyWithSeat().get("mySeat").getAsInt();
        sessions.disconnected(first);
        host.awaitNewest("presence", p -> disconnected(p, "Ann"), "the host was not shown the guest as disconnected");

        final TestBrowser second = connect("second");
        sessions.onMessage(second, message("setName", "name", "Bob"));
        second.awaitMatching("hello", h -> "Bob".equals(str(h, "playerName")), "the second guest was not named");
        send(host, message("giveSeat", "from", "Ann", "to", "Bob"));
        final JsonObject given = second.awaitLobby(l -> l.get("mySeat").getAsInt() == seat, "the second guest was not given the seat");
        Assert.assertEquals(given.getAsJsonArray("seats").get(seat).getAsJsonObject().get("name").getAsString(), "Ann");
        host.awaitNewest("presence", p -> named(p, "Ann") && !disconnected(p, "Ann") && !named(p, "Bob"),
                "the seat's player still reads as disconnected, or the browser that took it is listed twice");

        final TestBrowser back = connect("first");
        final JsonObject greeted = back.awaitNewest("hello", "the browser that left was never greeted");
        Assert.assertFalse(greeted.has("playerName") || greeted.get("inLobby").getAsBoolean(), "the browser that left came back to the seat it had lost");
        sessions.disconnected(back);

        sessions.disconnected(second);
        final TestBrowser reloaded = connect("second");
        reloaded.awaitLobby(l -> l.get("mySeat").getAsInt() == seat, "the browser given the seat lost it on a reload");
        sessions.disconnected(reloaded);
        host.awaitNewest("presence", p -> disconnected(p, "Ann"), "the host was not shown the seat as disconnected again");
        send(host, message("dropPlayer", "name", "Ann"));
        host.awaitLobby(l -> "OPEN".equals(l.getAsJsonArray("seats").get(seat).getAsJsonObject().get("type").getAsString()),
                "the seat did not open when its player was removed");
        final TestBrowser third = connect("third");
        sessions.onMessage(third, message("setName", "name", "Ann"));
        Assert.assertNotNull(third.awaitLobbyWithSeat(), "the removed player's name or seat was still held");
        // A host that comes back to a table is sent its decks once, before the next test can ask for them
        send(host, JsonCodec.message("leaveLobby"));
        host.awaitMatching("hello", h -> !h.get("inLobby").getAsBoolean(), "the host did not leave its table");
    }

    /** Fails if a guest can empty another guest's seat, or the host emptying one leaves its guest at the table, the seat held, or the table a seat short. */
    @Test(timeOut = 120_000)
    public void onlyTheHostEmptiesAGuestsSeat() throws Exception {
        final TestBrowser host = hostAt("invite");
        send(host, message("setPlayerCount", "count", 3));
        send(host, message("openSeat", "index", 2));
        final TestBrowser ann = connect("ann");
        sessions.onMessage(ann, message("setName", "name", "Ann"));
        final int annSeat = ann.awaitLobbyWithSeat().get("mySeat").getAsInt();
        final TestBrowser bob = connect("bob");
        sessions.onMessage(bob, message("setName", "name", "Bob"));
        Assert.assertNotNull(bob.awaitLobbyWithSeat(), "the second guest never took a seat");

        // A command is handled before onMessage returns, so a seat that was going to be emptied already has been
        ann.forget();
        sessions.onMessage(bob, message("openSeat", "index", annSeat));
        sessions.onMessage(bob, message("removeSeat", "index", annSeat));
        Assert.assertTrue(ann.got.stream().noneMatch(m -> "hello".equals(m.get("t").getAsString()) && !m.get("inLobby").getAsBoolean()),
                "a guest sent another guest away from the table");

        send(host, message("removeSeat", "index", annSeat));
        ann.awaitMatching("hello", h -> !h.get("inLobby").getAsBoolean(), "the guest was left at the table when the host emptied its seat");
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 3
                        && "OPEN".equals(l.getAsJsonArray("seats").get(annSeat).getAsJsonObject().get("type").getAsString()),
                "emptying a guest's seat did not leave it open, or took the seat away with the guest");
        send(host, JsonCodec.message("leaveLobby"));
        host.awaitMatching("hello", h -> !h.get("inLobby").getAsBoolean(), "the host did not leave its table");
    }

    /** Fails if a seat given away mid-match does not show its new browser the game, or one taken away is not played by the AI. */
    @Test(timeOut = 120_000)
    public void theHostSettlesADisconnectedSeatInAMatch() throws Exception {
        final TestBrowser host = hostAt("invite");
        final JsonObject hosted = host.awaitLobbyWithSeat();
        final TestBrowser guest = connect("player");
        sessions.onMessage(guest, message("setName", "name", "Player"));
        final JsonObject seated = guest.awaitLobbyWithSeat();
        Assert.assertNotNull(seated, "the guest never took a seat");
        sessions.onMessage(host, JsonCodec.message("decks"));
        final String deck = legalDeck(host.awaitNewest("decks"));
        for (final TestBrowser browser : List.of(host, guest)) {
            final JsonObject choose = message("setSeat", "index", (browser == host ? hosted : seated).get("mySeat").getAsInt());
            choose.addProperty("deck", deck);
            sessions.onMessage(browser, choose);
            sessions.onMessage(browser, message("ready", "ready", true));
        }
        host.awaitLobby(l -> l.get("canStart").getAsBoolean(), "the host could not start");
        sessions.onMessage(host, message("start", "spectate", false));
        guest.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean(), "the guest was not taken into the match");

        sessions.disconnected(guest);
        final TestBrowser other = connect("other");
        sessions.onMessage(other, message("setName", "name", "Sub"));
        other.awaitMatching("hello", h -> "Sub".equals(str(h, "playerName")), "the second guest was not named");
        send(host, message("giveSeat", "from", "Player", "to", "Sub"));
        other.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean(), "the browser given the seat was not taken into the match");
        other.awaitMatching("state", m -> m.get("full").getAsBoolean(), "the browser given the seat was never shown the table");

        sessions.disconnected(other);
        host.awaitNewest("presence", p -> disconnected(p, "Player"), "the host was not shown the seat as disconnected");
        send(host, message("dropPlayer", "name", "Player"));
        final Game game = sessions.hostLobby().getHostedMatch().getGame();
        awaitTrue(() -> game.getPlayers().stream().anyMatch(p -> "Player".equals(p.getName()) && p.getController() instanceof PlayerControllerAi),
                "the removed player's seat was not handed to the AI");
        host.awaitNewest("presence", p -> !named(p, "Player"), "the removed player is still listed");
    }

    /** Fails if a match waits for a next-game choice from a seat the AI took over, or the next game gives that seat back to a human. */
    @Test(timeOut = 120_000)
    public void theMatchGoesOnAfterASeatIsHandedToTheAi() throws Exception {
        final TestBrowser host = hostAt("invite");
        final JsonObject hosted = host.awaitLobbyWithSeat();
        final TestBrowser guest = connect("player");
        sessions.onMessage(guest, message("setName", "name", "Player"));
        final JsonObject seated = guest.awaitLobbyWithSeat();
        Assert.assertNotNull(seated, "the guest never took a seat");
        sessions.onMessage(host, JsonCodec.message("decks"));
        final String deck = legalDeck(host.awaitNewest("decks"));
        for (final TestBrowser browser : List.of(host, guest)) {
            final JsonObject choose = message("setSeat", "index", (browser == host ? hosted : seated).get("mySeat").getAsInt());
            choose.addProperty("deck", deck);
            sessions.onMessage(browser, choose);
            sessions.onMessage(browser, message("ready", "ready", true));
        }
        host.awaitLobby(l -> l.get("canStart").getAsBoolean(), "the host could not start");
        sessions.onMessage(host, message("start", "spectate", false));
        guest.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean(), "the guest was not taken into the match");
        final HostedMatch match = sessions.hostLobby().getHostedMatch();
        // A game is safe to end only once it asks a player something, which is after its zones are dealt
        awaitTrue(() -> match.getGame() != null && match.getHumanControllers().stream().anyMatch(c -> c.getInputQueue().getInput() != null),
                "the first game never asked a player anything");
        final Game first = match.getGame();

        sessions.disconnected(guest);
        host.awaitNewest("presence", p -> disconnected(p, "Player"), "the host was not shown the seat as disconnected");
        send(host, message("dropPlayer", "name", "Player"));
        awaitTrue(() -> first.getPlayers().stream().anyMatch(p -> "Player".equals(p.getName()) && p.getController() instanceof PlayerControllerAi),
                "the removed player's seat was not handed to the AI");
        send(host, JsonCodec.message("concede"));
        host.awaitMatching("gameOver", g -> true, "the host was not shown the first game's result");
        Assert.assertFalse(first.getMatch().isMatchOver(), "one game decided a match of three");

        send(host, message("nextGame", "decision", "CONTINUE"));
        awaitTrue(() -> match.getGame() != null && match.getGame() != first, "the second game never started");
        Assert.assertTrue(match.getGame().getRegisteredPlayers().stream()
                .anyMatch(p -> "Player".equals(p.getName()) && p.getController() instanceof PlayerControllerAi), "the AI's seat went back to a human in the second game");
    }

    /** Fails if a guest is left in match setup when the match starts after the host removed a seat below the guest's. */
    @Test(timeOut = 120_000)
    public void aGuestFollowsTheHostIntoTheMatchAfterASeatBelowIsRemoved() throws Exception {
        final TestBrowser host = hostAt("invite");
        sessions.onMessage(host, JsonCodec.message("decks"));
        final String deck = legalDeck(host.awaitNewest("decks"));
        send(host, message("setPlayerCount", "count", 3));
        send(host, message("aiSeat", "index", 1));
        send(host, message("openSeat", "index", 2));
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 3
                && "OPEN".equals(l.getAsJsonArray("seats").get(2).getAsJsonObject().get("type").getAsString()), "the third seat never opened");
        final TestBrowser guest = connect("player");
        sessions.onMessage(guest, message("setName", "name", "Player"));
        Assert.assertEquals(guest.awaitLobbyWithSeat().get("mySeat").getAsInt(), 2, "the guest did not take the third seat");

        for (final TestBrowser browser : List.of(host, guest)) {
            final JsonObject choose = message("setSeat", "index", browser.latestTable().get("mySeat").getAsInt());
            choose.addProperty("deck", deck);
            sessions.onMessage(browser, choose);
            sessions.onMessage(browser, message("ready", "ready", true));
        }
        host.awaitLobby(l -> l.getAsJsonArray("seats").get(2).getAsJsonObject().get("ready").getAsBoolean(), "the guest never readied");

        send(host, message("removeSeat", "index", 1));
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 2 && l.get("canStart").getAsBoolean(),
                "the host could not start once the computer's seat was removed");
        guest.awaitLobby(l -> l.get("mySeat").getAsInt() == 1, "the guest was not told its seat had moved down");
        guest.forget();
        sessions.onMessage(host, message("start", "spectate", false));
        guest.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean(),
                "the guest was left in match setup when the match started" + diagnosis(host, guest));
    }

    private static boolean named(final JsonObject presence, final String name) {
        for (final var p : presence.getAsJsonArray("people")) {
            if (name.equals(p.getAsJsonObject().get("name").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean disconnected(final JsonObject presence, final String name) {
        for (final var p : presence.getAsJsonArray("people")) {
            final JsonObject person = p.getAsJsonObject();
            if (name.equals(person.get("name").getAsString())) {
                return person.has("disconnected") && person.get("disconnected").getAsBoolean();
            }
        }
        return false;
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
    private static String diagnosis(final TestBrowser... browsers) {
        final StringBuilder out = new StringBuilder();
        for (final TestBrowser b : browsers) {
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
        final TestBrowser host = hostAt("invite");
        sessions.onMessage(host, message("setPlayerCount", "count", 4));
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 4, "the table never grew to four");
        final TestBrowser guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest"));
        host.awaitLobby(l -> seatNames(l).contains("Guest"), "the guest never sat down");

        sessions.onMessage(host, message("setPlayerCount", "count", 2));
        final JsonObject two = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 2, "the table never shrank to two");
        Assert.assertTrue(seatNames(two).contains("Guest"), "the guest lost their seat: " + seatNames(two));

        sessions.onMessage(host, message("setPlayerCount", "count", 1));
        Thread.sleep(500);
        Assert.assertEquals(host.latestTable().getAsJsonArray("seats").size(), 2, "a table went below two players");
    }
    // Fails if a table stops short of eight seats, or takes a ninth
    @Test(timeOut = 120_000)
    public void aTableTakesEightSeats() throws Exception {
        final TestBrowser host = hostAt("invite");
        sessions.onMessage(host, message("setPlayerCount", "count", 9));
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 8, "the table never grew to eight");
        Thread.sleep(500);
        Assert.assertEquals(host.latestTable().getAsJsonArray("seats").size(), 8, "a table went above eight players");
    }

    // Fails if two seats can end with the same portrait, by a seat choosing a taken one or a guest arriving with one
    @Test(timeOut = 120_000)
    public void noTwoSeatsShareAPortrait() throws Exception {
        final TestBrowser host = hostAt("invite");
        sessions.onMessage(host, message("setPlayerCount", "count", 3));
        final JsonObject three = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 3, "the table never grew to three");
        final int mine = three.get("mySeat").getAsInt();
        final int taken = seatAvatars(three).get(mine == 0 ? 1 : 0);
        sessions.onMessage(host, message("setSeat", "index", mine, "avatar", taken));
        // The seat count changes after the portrait was asked for, so a table of four has the answer in it
        sessions.onMessage(host, message("setPlayerCount", "count", 4));
        final JsonObject four = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 4, "the table never grew to four");
        Assert.assertEquals(new HashSet<>(seatAvatars(four)).size(), 4, "a seat took a portrait another holds: " + seatAvatars(four));

        final TestBrowser guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest", "avatar", seatAvatars(four).get(mine)));
        final JsonObject seated = host.awaitLobby(l -> seatNames(l).contains("Guest"), "the guest never sat down");
        Assert.assertEquals(new HashSet<>(seatAvatars(seated)).size(), 4, "a guest kept a portrait another holds: " + seatAvatars(seated));
    }

    private static List<Integer> seatAvatars(final JsonObject table) {
        final List<Integer> out = new ArrayList<>();
        table.getAsJsonArray("seats").forEach(s -> out.add(s.getAsJsonObject().get("avatar").getAsInt()));
        return out;
    }
}
