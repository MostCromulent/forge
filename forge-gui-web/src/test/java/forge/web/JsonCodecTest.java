package forge.web;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.card.ColorSet;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.zone.ZoneType;
import forge.gamemodes.net.DeltaPacket;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.trackable.TrackableProperty;
import forge.trackable.TrackableTypes.TrackableType;
import forge.trackable.TrackableTypes;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonCodecTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static TrackableProperty ofType(final TrackableType<?> type) {
        for (final TrackableProperty p : TrackableProperty.values()) {
            if (p.getType() == type) {
                return p;
            }
        }
        throw new AssertionError("no property of type " + type);
    }

    private static JsonElement encodeOne(final int ownerKey, final TrackableProperty prop, final Object value) {
        final Map<TrackableProperty, Object> props = new HashMap<>();
        props.put(prop, value);
        final List<String> skipped = new ArrayList<>();
        final JsonObject out = JsonCodec.encodeProps(ownerKey, props, skipped::add);
        Assert.assertTrue(skipped.isEmpty(), "skipped " + skipped);
        return out.get(prop.name());
    }

    private static int cardKey(final int id) {
        return DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, id);
    }

    @Test
    public void cardReferenceBecomesRefMarker() {
        Assert.assertEquals(encodeOne(cardKey(1), ofType(TrackableTypes.CardViewType), 7), JsonCodec.ref(DeltaPacket.TYPE_CARD_VIEW, 7));
    }

    @Test
    public void cardCollectionKeepsOrderAndNullsMissingEntries() {
        final JsonArray arr = encodeOne(cardKey(1), ofType(TrackableTypes.CardViewCollectionType), new ArrayList<>(Arrays.asList(3, -1, 5))).getAsJsonArray();
        Assert.assertEquals(arr.get(0), JsonCodec.ref(DeltaPacket.TYPE_CARD_VIEW, 3));
        Assert.assertTrue(arr.get(1).isJsonNull());
        Assert.assertEquals(arr.get(2), JsonCodec.ref(DeltaPacket.TYPE_CARD_VIEW, 5));
    }

    @Test
    public void cardStateOrdinalBecomesCardStateKeyOfTheOwningCard() {
        Assert.assertEquals(encodeOne(cardKey(12), TrackableProperty.CurrentState, 2), JsonCodec.ref(DeltaPacket.TYPE_CSV, 12 * 16 + 2));
    }

    @Test
    public void attachedToPairPicksCardOrPlayer() {
        Assert.assertEquals(encodeOne(cardKey(1), TrackableProperty.EntityAttachedTo, new int[]{1, 3}), JsonCodec.ref(DeltaPacket.TYPE_PLAYER_VIEW, 3));
        Assert.assertEquals(encodeOne(cardKey(1), TrackableProperty.EntityAttachedTo, new int[]{0, 9}), JsonCodec.ref(DeltaPacket.TYPE_CARD_VIEW, 9));
    }

    @Test
    public void combatDataBecomesBandsOfRefs() {
        final List<List<Integer>> attackers = List.of(List.of(4));
        final List<int[]> defenders = new ArrayList<>();
        defenders.add(new int[]{1, 2});
        final List<List<Integer>> blockers = new ArrayList<>();
        blockers.add(null);
        final List<List<Integer>> planned = new ArrayList<>();
        planned.add(null);
        final DeltaPacket.CombatData data = new DeltaPacket.CombatData(attackers, defenders, blockers, planned);
        final JsonObject band = encodeOne(DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_GAME_VIEW, 1), TrackableProperty.CombatView, data)
                .getAsJsonArray().get(0).getAsJsonObject();
        Assert.assertEquals(band.getAsJsonArray("attackers").get(0), JsonCodec.ref(DeltaPacket.TYPE_CARD_VIEW, 4));
        Assert.assertEquals(band.get("defender"), JsonCodec.ref(DeltaPacket.TYPE_PLAYER_VIEW, 2));
        Assert.assertTrue(band.get("blockers").isJsonNull());
    }

    @Test
    public void plainValuesUseReadableForms() {
        Assert.assertTrue(encodeOne(cardKey(1), TrackableProperty.Tapped, true).getAsBoolean());
        Assert.assertEquals(encodeOne(cardKey(1), TrackableProperty.Zone, ZoneType.Hand).getAsString(), "Hand");
        final ColorSet azorius = ColorSet.fromNames("W", "U");
        Assert.assertEquals(encodeOne(cardKey(1), ofType(TrackableTypes.ColorSetType), azorius).getAsInt(), azorius.getColor());
        final Multiset<CounterType> counters = HashMultiset.create();
        counters.add(CounterEnumType.P1P1, 2);
        final JsonObject c = encodeOne(cardKey(1), TrackableProperty.Counters, counters).getAsJsonObject();
        Assert.assertEquals(c.get(CounterEnumType.P1P1.getName()).getAsInt(), 2);
        final PaperCard island = FModel.getMagicDb().getCommonCards().getCard("Island");
        final JsonObject paper = encodeOne(cardKey(1), ofType(TrackableTypes.IPaperCardType), island).getAsJsonObject();
        Assert.assertEquals(paper.get("name").getAsString(), "Island");
    }

    @Test
    public void nullValueMeansRemoved() {
        final Map<TrackableProperty, Object> props = new HashMap<>();
        props.put(TrackableProperty.Tapped, null);
        Assert.assertTrue(JsonCodec.encodeProps(cardKey(1), props, s -> { }).get("Tapped").isJsonNull());
    }

    @Test
    public void unknownValueTypeIsReportedAndSkipped() {
        final Map<TrackableProperty, Object> props = new LinkedHashMap<>();
        props.put(TrackableProperty.Name, new Object());
        final List<String> skipped = new ArrayList<>();
        final JsonObject out = JsonCodec.encodeProps(cardKey(1), props, skipped::add);
        Assert.assertEquals(skipped, List.of("Name"));
        Assert.assertFalse(out.has("Name"));
    }
}
