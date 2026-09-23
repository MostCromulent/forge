package forge.web;

import com.google.gson.JsonObject;
import forge.game.card.CardView;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventShuffle;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;
import forge.gamemodes.net.DeltaPacket;
import forge.trackable.Tracker;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** Forge's game events as the browser receives them: by reference to objects in its table, never as instructions. */
public class GameEventsTest {
    private final Tracker tracker = new Tracker();
    private PlayerView player;
    private CardView card;

    @BeforeClass
    public void views() {
        WebTestSupport.initModel();
        player = new PlayerView(2, tracker);
        card = new CardView(5, tracker);
    }

    private static JsonObject json(final Record event) {
        return Wire.toJson(event).getAsJsonObject();
    }

    @Test
    public void aCardChangingZoneNamesTheCardAndBothPlaces() {
        final JsonObject moved = json(BrowserEvents.forwarded(new GameEventCardChangeZone(card,
                new ZoneView(player, ZoneType.Hand), new ZoneView(null, ZoneType.Stack))));
        Assert.assertEquals(moved.get("kind").getAsString(), "cardMoved");
        Assert.assertEquals(moved.getAsJsonObject("card").get("ref").getAsInt(),
                DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, 5));
        Assert.assertEquals(moved.getAsJsonObject("from").get("zone").getAsString(), "Hand");
        Assert.assertEquals(moved.getAsJsonObject("from").getAsJsonObject("player").get("ref").getAsInt(),
                DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_PLAYER_VIEW, 2));
        // The stack is nobody's
        Assert.assertEquals(moved.getAsJsonObject("to").get("zone").getAsString(), "Stack");
        Assert.assertFalse(moved.getAsJsonObject("to").has("player"));
    }

    @Test
    public void aCardThatComesIntoBeingHasNoOrigin() {
        final JsonObject made = json(BrowserEvents.forwarded(new GameEventCardChangeZone(card, null,
                new ZoneView(player, ZoneType.Battlefield))));
        Assert.assertFalse(made.has("from"), "a token has nowhere it came from");
    }

    @Test
    public void aShuffleNamesThePlayer() {
        final JsonObject shuffled = json(BrowserEvents.forwarded(new GameEventShuffle(player)));
        Assert.assertEquals(shuffled.get("kind").getAsString(), "shuffled");
    }

    @Test
    public void anEventTheStateAlreadyShowsIsNotForwarded() {
        Assert.assertNull(BrowserEvents.forwarded(new GameEventGameStarted(null, (PlayerView) null, java.util.List.<PlayerView>of())));
    }
}
