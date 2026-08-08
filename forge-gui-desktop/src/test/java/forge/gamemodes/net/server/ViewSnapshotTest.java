package forge.gamemodes.net.server;

import forge.card.CardType;
import forge.card.CardTypeView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableProperty;
import forge.trackable.Tracker;

import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multiset;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Map;

/**
 * Tests for the snapshot and its diff, built by hand with no game running.
 *
 * <p>That is the point of them. The delta a client receives is supposed to be a pure function
 * of two immutable values, so the things most likely to be got wrong about it — what a value
 * turns into, and what counts as a change — should be checkable without an engine, a network
 * or a harness. Both defects these cover cost a full ten-game batch to find.
 */
public class ViewSnapshotTest {

    private static GameView graphWith(final Tracker tracker, final CardView card) {
        final GameView gameView = new GameView(1, tracker);
        final PlayerView player = new PlayerView(0, tracker);
        player.set(TrackableProperty.Battlefield, new TrackableCollection<>(card));
        gameView.set(TrackableProperty.Players, new TrackableCollection<>(player));
        return gameView;
    }

    private static Map<TrackableProperty, Object> propsOf(final ViewSnapshot snapshot, final CardView card) {
        final Map<TrackableProperty, Object> props =
                snapshot.objects().get(forge.gamemodes.net.DeltaPacket.makeDeltaKey(card));
        Assert.assertNotNull(props, "snapshot did not record the card at all");
        return props;
    }

    /**
     * A stored value keeps the type its receiver reads it back as.
     *
     * <p>Counters are read with {@code instanceof Multiset}, so a copy that is merely an
     * equivalent {@code Map} is not an error anywhere — it simply reads as no counters at
     * all, on the client and in the checksum alike.
     */
    @Test
    public void storesValuesAsTheTypeTheReceiverExpects() {
        final Tracker tracker = new Tracker();
        final CardView card = new CardView(100, tracker);
        card.set(TrackableProperty.Counters, ImmutableMultiset.of("P1P1", "P1P1"));
        card.set(TrackableProperty.Type, new CardType(java.util.List.of("Creature"), false));

        final Map<TrackableProperty, Object> props =
                propsOf(ViewSnapshot.build(graphWith(tracker, card)), card);

        Assert.assertTrue(props.get(TrackableProperty.Counters) instanceof Multiset,
                "counters must stay a Multiset, was " + props.get(TrackableProperty.Counters).getClass());
        Assert.assertTrue(props.get(TrackableProperty.Type) instanceof CardTypeView,
                "type must stay a CardTypeView, was " + props.get(TrackableProperty.Type).getClass());
    }

    /** A snapshot shares nothing with the graph it was taken from. */
    @Test
    public void doesNotAliasTheGraph() {
        final Tracker tracker = new Tracker();
        final CardView card = new CardView(101, tracker);
        card.set(TrackableProperty.Counters, ImmutableMultiset.of("P1P1"));
        final GameView gameView = graphWith(tracker, card);

        final ViewSnapshot before = ViewSnapshot.build(gameView);
        card.set(TrackableProperty.Counters, ImmutableMultiset.of("P1P1", "P1P1", "P1P1"));

        final Multiset<?> recorded = (Multiset<?>) propsOf(before, card).get(TrackableProperty.Counters);
        Assert.assertEquals(recorded.size(), 1, "the earlier snapshot changed underneath us");
    }

    /** Nothing changed, nothing sent — otherwise every pass would resend the board. */
    @Test
    public void reportsNoChangeWhenNothingChanged() {
        final Tracker tracker = new Tracker();
        final CardView card = new CardView(102, tracker);
        card.set(TrackableProperty.Tapped, true);
        card.set(TrackableProperty.Type, new CardType(java.util.List.of("Creature"), false));
        final GameView gameView = graphWith(tracker, card);

        final ViewSnapshot.Diff diff =
                ViewSnapshot.diff(ViewSnapshot.build(gameView), ViewSnapshot.build(gameView));

        Assert.assertTrue(diff.objectDeltas().isEmpty(), "unchanged state produced deltas: " + diff.objectDeltas());
        Assert.assertTrue(diff.newObjects().isEmpty(), "unchanged state produced new objects");
    }

    /** A changed property is carried, and only that property. */
    @Test
    public void reportsOnlyWhatChanged() {
        final Tracker tracker = new Tracker();
        final CardView card = new CardView(103, tracker);
        card.set(TrackableProperty.Tapped, false);
        card.set(TrackableProperty.Name, "Grizzly Bears");
        final GameView gameView = graphWith(tracker, card);

        final ViewSnapshot before = ViewSnapshot.build(gameView);
        card.set(TrackableProperty.Tapped, true);
        final ViewSnapshot.Diff diff = ViewSnapshot.diff(before, ViewSnapshot.build(gameView));

        final Map<TrackableProperty, Object> changed =
                diff.objectDeltas().get(forge.gamemodes.net.DeltaPacket.makeDeltaKey(card));
        Assert.assertNotNull(changed, "the changed card is missing from the diff");
        Assert.assertEquals(changed.keySet(), java.util.Set.of(TrackableProperty.Tapped),
                "the diff carried more than the property that changed");
        Assert.assertEquals(changed.get(TrackableProperty.Tapped), Boolean.TRUE);
    }

    /**
     * A property that goes away is carried as an explicit null.
     *
     * <p>Removing one is how a value returns to its default, and the receiver has no way to
     * discover that on its own — silence means unchanged.
     */
    @Test
    public void reportsARemovedPropertyAsNull() {
        final Tracker tracker = new Tracker();
        final CardView card = new CardView(104, tracker);
        card.set(TrackableProperty.Name, "Grizzly Bears");
        final GameView gameView = graphWith(tracker, card);

        final ViewSnapshot before = ViewSnapshot.build(gameView);
        card.set(TrackableProperty.Name, null);
        final ViewSnapshot.Diff diff = ViewSnapshot.diff(before, ViewSnapshot.build(gameView));

        final Map<TrackableProperty, Object> changed =
                diff.objectDeltas().get(forge.gamemodes.net.DeltaPacket.makeDeltaKey(card));
        Assert.assertNotNull(changed, "the cleared property was not reported at all");
        Assert.assertTrue(changed.containsKey(TrackableProperty.Name), "no entry for the cleared property");
        Assert.assertNull(changed.get(TrackableProperty.Name), "a cleared property must arrive as null");
    }

    /** Diffing is a function of its two inputs: same inputs, same answer. */
    @Test
    public void isAFunctionOfItsInputs() {
        final Tracker tracker = new Tracker();
        final CardView card = new CardView(105, tracker);
        card.set(TrackableProperty.Tapped, false);
        final GameView gameView = graphWith(tracker, card);

        final ViewSnapshot before = ViewSnapshot.build(gameView);
        card.set(TrackableProperty.Tapped, true);
        final ViewSnapshot after = ViewSnapshot.build(gameView);

        Assert.assertEquals(ViewSnapshot.diff(before, after).objectDeltas(),
                ViewSnapshot.diff(before, after).objectDeltas());
    }
}
