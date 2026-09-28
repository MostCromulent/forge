package forge.web;

import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * Each kind of change a host makes to a table reaches a guest's browser over netplay. The rules behind each change are
 * tested at the table itself, in the {@link TablesTest} classes; this is the wire from them to a guest.
 */
public class GuestTableTest extends SessionsTest {
    // Fails if a change the host makes stays on the host's table, as a slot edited straight on the server once did
    @Test(timeOut = 120_000)
    public void everyTableChangeReachesTheGuest() throws Exception {
        final Recorder host = hostAt("invite");
        // A session of its own, since a name given here would otherwise follow the "guest" id into other tests
        final Recorder guest = connect("follow-guest");
        sessions.onMessage(guest, message("setName", "name", "Follower"));
        Assert.assertNotNull(guest.awaitLobbyWithSeat(), "the guest never sat down");

        reaches(host, guest, message("setCardPool", "cardPool", "Pauper"), t -> "Pauper".equals(text(t, "cardPool")), "card pool");
        reaches(host, guest, message("setVariant", "variant", "Planechase", "on", true),
                t -> t.getAsJsonArray("variantsOn").toString().contains("Planechase"), "variant");
        reaches(host, guest, message("setPlayerCount", "count", 3), t -> t.getAsJsonArray("seats").size() == 3, "player count");
        reaches(host, guest, message("openSeat", "index", 2), t -> "OPEN".equals(seatType(t, 2)), "opened seat");
        reaches(host, guest, message("aiSeat", "index", 2), t -> "AI".equals(seatType(t, 2)), "computer's seat");
        reaches(host, guest, message("setFormat", "format", "Commander"), t -> "Commander".equals(text(t, "format")), "format");
        reaches(host, guest, message("setLimited", "kind", "sealed"), t -> t.has("limited"), "Limited switch");
    }

    private void reaches(final Recorder host, final Recorder guest, final JsonObject change, final Predicate<JsonObject> shown,
            final String what) throws InterruptedException {
        sessions.onMessage(host, change);
        Assert.assertNotNull(guest.awaitLobby(shown), "the guest's table never showed the " + what + ": " + guest.latestTable());
    }

    private static String text(final JsonObject table, final String field) {
        return table.has(field) ? table.get(field).getAsString() : null;
    }

    private static String seatType(final JsonObject table, final int seat) {
        return table.getAsJsonArray("seats").size() > seat
                ? table.getAsJsonArray("seats").get(seat).getAsJsonObject().get("type").getAsString() : null;
    }
}
