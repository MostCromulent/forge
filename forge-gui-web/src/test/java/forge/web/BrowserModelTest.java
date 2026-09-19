package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import forge.gamemodes.net.DeltaPacket;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BrowserModelTest {
    private static final int GAME = DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_GAME_VIEW, 1);
    private static final int PLAYER = DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_PLAYER_VIEW, 2);
    private static final int CARD_A = DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, 10);
    private static final int CARD_B = DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, 11);

    private static JsonObject game() {
        final JsonObject g = new JsonObject();
        final JsonArray players = new JsonArray();
        players.add(JsonCodec.ref(DeltaPacket.TYPE_PLAYER_VIEW, 2));
        g.add("Players", players);
        return g;
    }

    private static JsonObject playerWithHand(final int... cardIds) {
        final JsonObject p = new JsonObject();
        final JsonArray hand = new JsonArray();
        for (final int id : cardIds) {
            hand.add(JsonCodec.ref(DeltaPacket.TYPE_CARD_VIEW, id));
        }
        p.add("Hand", hand);
        p.addProperty("Life", 20);
        return p;
    }

    private static BrowserModel withTwoCardsInHand() {
        final BrowserModel m = new BrowserModel();
        m.reset(GAME);
        final Map<Integer, JsonObject> news = new LinkedHashMap<>();
        news.put(GAME, game());
        news.put(PLAYER, playerWithHand(10, 11));
        final JsonObject a = new JsonObject();
        a.addProperty("Tapped", true);
        news.put(CARD_A, a);
        news.put(CARD_B, new JsonObject());
        m.apply(news, Map.of());
        return m;
    }

    @Test
    public void deltaMergesAndNullRemoves() {
        final BrowserModel m = withTwoCardsInHand();
        final JsonObject delta = new JsonObject();
        delta.add("Tapped", JsonNull.INSTANCE);
        delta.addProperty("Damage", 2);
        m.apply(Map.of(), Map.of(CARD_A, delta));
        final JsonObject a = m.objectsCopy().get(CARD_A);
        Assert.assertFalse(a.has("Tapped"));
        Assert.assertEquals(a.get("Damage").getAsInt(), 2);
    }

    @Test
    public void unreachableObjectsArePruned() {
        final BrowserModel m = withTwoCardsInHand();
        final JsonObject delta = new JsonObject();
        final JsonArray hand = new JsonArray();
        hand.add(JsonCodec.ref(DeltaPacket.TYPE_CARD_VIEW, 10));
        delta.add("Hand", hand);
        m.apply(Map.of(), Map.of(PLAYER, delta));
        Assert.assertTrue(m.objectsCopy().containsKey(CARD_A));
        Assert.assertFalse(m.objectsCopy().containsKey(CARD_B));
    }

    @Test
    public void deltaForUnknownObjectIsIgnored() {
        final BrowserModel m = withTwoCardsInHand();
        m.apply(Map.of(), Map.of(DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, 99), new JsonObject()));
        Assert.assertEquals(m.objectsCopy().size(), 4);
    }

    @Test
    public void fullStateRoundTripsThroughApplyStateMessage() {
        final BrowserModel m = withTwoCardsInHand();
        m.setVisible(List.of(CARD_A));
        final BrowserModel copy = new BrowserModel();
        copy.applyStateMessage(m.fullState());
        Assert.assertEquals(copy.objectsCopy(), m.objectsCopy());
        Assert.assertEquals(copy.fullState(), m.fullState());
    }
}
