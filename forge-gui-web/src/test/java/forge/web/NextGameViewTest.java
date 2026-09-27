package forge.web;

import forge.gamemodes.net.DeltaPacket;
import forge.trackable.TrackableProperty;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The next game of a match has a game view with a new id, which the client folds into the game view it already
 * holds. The browser must hear of it under that view's key, or its turn and phase stay at the last game's.
 */
public class NextGameViewTest {
    private static final int ROOT = DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_GAME_VIEW, 5);
    private static final int NEXT = DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_GAME_VIEW, 6);
    private static final int CARD = DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, 6);

    /** Fails if the next game's view arrives in the browser under its own key rather than the one the browser reads. */
    @Test
    public void nextGamesViewIsGivenTheRootKey() {
        final Map<Integer, Map<TrackableProperty, Object>> packet = new LinkedHashMap<>();
        packet.put(NEXT, Map.of(TrackableProperty.Turn, 3));
        packet.put(CARD, Map.of(TrackableProperty.Tapped, true));

        final Map<Integer, Map<TrackableProperty, Object>> sent = WebGuiGame.asRootGame(packet, ROOT);

        Assert.assertFalse(sent.containsKey(NEXT), "the next game's view kept its own key");
        Assert.assertEquals(sent.get(ROOT), Map.of(TrackableProperty.Turn, 3));
        // A card with the same id is another object entirely and keeps its key
        Assert.assertEquals(sent.get(CARD), Map.of(TrackableProperty.Tapped, true));
    }
}
