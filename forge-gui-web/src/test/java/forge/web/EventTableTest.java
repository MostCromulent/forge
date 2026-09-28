package forge.web;

import forge.gamemodes.limited.LimitedPoolType;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A table's seats and its draft or sealed event, as the host sets them. Only the host and computers sit here; guests at
 * an event, and the event played through, are {@link OnlineEventTest}'s.
 */
public class EventTableTest extends TablesTest {
    private final EventDecks events = new EventDecks();

    @AfterMethod(alwaysRun = true)
    public void deleteEventDecks() {
        events.clear();
    }

    private static ToBrowser.LobbyTable table(final Lobby lobby) {
        return lobby.state().table();
    }

    private static List<String> names(final Lobby lobby) {
        return table(lobby).seats().stream().map(ToBrowser.Seat::name).toList();
    }

    /** What the host's action answered: null, or why not. */
    private static String answer(final java.util.function.Supplier<String> action) {
        final AtomicReference<String> out = new AtomicReference<>();
        onUi(() -> out.set(action.get()));
        return out.get();
    }

    private static FromBrowser.EventSetup setup(final LimitedPoolType product, final String cube) {
        return new FromBrowser.EventSetup(product.name(), null, null, null, null, cube, null, null, 3, 8, null, 0, 0);
    }

    // Fails if a count adds seats other than computers at the end, or lowering it takes a seat other than the last
    @Test(timeOut = 60_000)
    public void seatsComeAndGoAtTheEnd() throws Exception {
        atTable((local, lobby) -> {
            onUi(() -> lobby.setPlayerCount(4));
            final List<String> four = names(lobby);
            Assert.assertEquals(four.size(), 4, "the table never grew to four");
            Assert.assertEquals(table(lobby).seats().subList(1, 4).stream().map(ToBrowser.Seat::type).toList(),
                    List.of("AI", "AI", "AI"), "the new seats were not computers");
            onUi(() -> lobby.setPlayerCount(3));
            Assert.assertEquals(names(lobby), four.subList(0, 3), "a seat other than the last one went");
        });
    }

    // Fails if a Limited table keeps Constructed's four-seat cap, if an add at the cap disturbs a seat, or if the table
    // can go back to Constructed with more players than a Constructed match seats
    @Test(timeOut = 60_000)
    public void aLimitedTableSeatsEight() throws Exception {
        atTable((local, lobby) -> {
            Assert.assertNull(answer(() -> lobby.setLimited("sealed")));
            onUi(() -> lobby.setPlayerCount(8));
            final List<String> full = names(lobby);
            Assert.assertEquals(full.size(), 8, "the table was refused eight seats");
            Assert.assertEquals(table(lobby).maxSeats(), 8);
            onUi(() -> lobby.setPlayerCount(9));
            Assert.assertEquals(names(lobby), full, "asking past the cap changed the table");
            Assert.assertNotNull(answer(() -> lobby.setLimited(null)), "going back to Constructed with eight seated was not refused");
            Assert.assertNotNull(table(lobby).limited(), "the table left Limited with eight seated");
        });
    }

    // Fails if setting the event up again keeps the first product, which is how Edit event changes one
    @Test(timeOut = 120_000)
    public void anEditedEventReplacesTheOld() throws Exception {
        atTable((local, lobby) -> {
            Assert.assertNull(answer(() -> lobby.setLimited("draft")));
            Assert.assertNull(answer(() -> lobby.setUpEvent(setup(LimitedPoolType.Full, null))), "the draft was never set up");
            final String cube = OfflineEvents.options().cubes().get(0);
            Assert.assertNull(answer(() -> lobby.setUpEvent(setup(LimitedPoolType.Custom, cube))));
            Assert.assertTrue(table(lobby).limited().product().startsWith(LimitedPoolType.Custom.toString()),
                    "the edited event kept its first product: " + table(lobby).limited().product());
        });
    }

    // Fails if the finder at a Limited table ignores the event-decks switch: on, only the table's event's decks; off, every event's
    @Test(timeOut = 60_000)
    public void eventDecksOnlyFilters() throws Exception {
        final String mine = events.stored("Web test ours");
        events.stored("Web test theirs");
        atTable((local, lobby) -> {
            Assert.assertNull(answer(() -> lobby.setLimited("sealed")));
            Assert.assertNull(answer(() -> lobby.hostAgain(mine)));
            final List<String> only = lobby.decks().decks().stream().map(ToBrowser.DeckSummary::name).toList();
            Assert.assertTrue(only.stream().anyMatch(n -> n.startsWith("Web test ours"))
                    && only.stream().noneMatch(n -> n.startsWith("Web test theirs")), "the finder listed " + only);
            onUi(() -> lobby.setEventDecksOnly(false));
            Assert.assertTrue(lobby.decks().decks().stream().anyMatch(d -> d.name().startsWith("Web test theirs")),
                    "with the switch off, another event's deck was still left out");
        });
    }

    // Fails if a seat benched for an event's match stays benched at the Constructed table that follows, where nothing
    // shows or clears it
    @Test(timeOut = 60_000)
    public void theBenchEndsWithTheEvent() throws Exception {
        final String id = events.stored("Web test bench");
        atTable((local, lobby) -> {
            Assert.assertNull(answer(() -> lobby.setLimited("sealed")));
            Assert.assertNull(answer(() -> lobby.hostAgain(id)));
            onUi(() -> lobby.benchSeat(1, true));
            Assert.assertTrue(table(lobby).seats().get(1).benched(), "the seat was never benched");
            Assert.assertNull(answer(() -> lobby.setLimited(null)));
            Assert.assertFalse(table(lobby).seats().get(1).benched(), "the seat stayed benched at a Constructed table");
            onUi(() -> lobby.benchSeat(1, true));
            Assert.assertFalse(table(lobby).seats().get(1).benched(), "a Constructed table benched a seat");
        });
    }
}
