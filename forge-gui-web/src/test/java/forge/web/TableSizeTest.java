package forge.web;

import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

/** The table's player count: seats added and taken away at the end, and never a seat a person holds. */
public class TableSizeTest extends SessionsTest {
    private static List<String> kinds(final JsonObject table) {
        final List<String> out = new ArrayList<>();
        table.getAsJsonArray("seats").forEach(s -> out.add(s.getAsJsonObject().get("type").getAsString()));
        return out;
    }

    private static List<String> names(final JsonObject table) {
        final List<String> out = new ArrayList<>();
        table.getAsJsonArray("seats").forEach(s -> out.add(s.getAsJsonObject().has("name") ? s.getAsJsonObject().get("name").getAsString() : null));
        return out;
    }

    // Fails if a count adds seats other than computers at the end, or lowering it takes a seat other than the last
    @Test(timeOut = 120_000)
    public void seatsComeAndGoAtTheEnd() throws Exception {
        final Recorder host = hostAt("lobby");
        sessions.onMessage(host, message("setPlayerCount", "count", 4));
        final JsonObject four = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 4);
        Assert.assertNotNull(four, "the table never grew to four");
        Assert.assertEquals(kinds(four).subList(1, 4), List.of("AI", "AI", "AI"), "the new seats were not computers");
        final List<String> before = names(four);

        sessions.onMessage(host, message("setPlayerCount", "count", 3));
        final JsonObject three = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 3);
        Assert.assertNotNull(three, "the table never shrank to three");
        Assert.assertEquals(names(three), before.subList(0, 3), "a seat other than the last one went");
    }

    // Fails if lowering the count removes a seat a person holds, or keeps an open seat over a computer's
    @Test(timeOut = 120_000)
    public void aPersonKeepsTheirSeat() throws Exception {
        final Recorder host = hostAt("invite");
        sessions.onMessage(host, message("setPlayerCount", "count", 4));
        Assert.assertNotNull(host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 4), "the table never grew to four");
        final Recorder guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest"));
        Assert.assertNotNull(host.awaitLobby(l -> names(l).contains("Guest")), "the guest never sat down");

        sessions.onMessage(host, message("setPlayerCount", "count", 2));
        final JsonObject two = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 2);
        Assert.assertNotNull(two, "the table never shrank to two");
        Assert.assertTrue(names(two).contains("Guest"), "the guest lost their seat: " + names(two));

        sessions.onMessage(host, message("setPlayerCount", "count", 1));
        Thread.sleep(500);
        Assert.assertEquals(host.latestTable().getAsJsonArray("seats").size(), 2, "a table went below two players");
    }
}
