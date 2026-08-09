package forge.trackable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.card.CardType;
import forge.game.card.CardView;
import forge.game.player.PlayerView;

public class TrackableObjectTest {

    /**
     * Storing a property that has not changed does not mark it changed.
     *
     * <p>Whether a property is dirty is decided by comparing the new value against the stored
     * one, so a type that compares only by identity gets the answer backwards in both
     * directions: a fresh copy of an unchanged type reads as changed, and a type mutated in
     * place reads as unchanged because it is compared against itself.
     */
    @Test
    public void restoringAnUnchangedTypeDoesNotMarkItChanged() {
        final Tracker tracker = new Tracker();
        final CardView card = new CardView(1, tracker);

        // Core types and supertypes, not subtypes: subtypes are sanitised against the creature
        // type tables, which a test without card data loaded does not have.
        card.set(TrackableProperty.Type, new CardType(List.of("Creature"), false));

        final long afterFirst = tracker.getChangeCount();
        card.set(TrackableProperty.Type, new CardType(List.of("Creature"), false));
        Assert.assertEquals(tracker.getChangeCount(), afterFirst,
                "an unchanged type was recorded as a change");

        card.set(TrackableProperty.Type, new CardType(List.of("Legendary", "Creature"), false));
        Assert.assertTrue(tracker.getChangeCount() > afterFirst,
                "a changed type was not recorded as a change");
    }

    /**
     * A collection stored as a property is copied, so the engine can keep its own.
     *
     * <p>Several of these are handed over still owned by the engine - counters are stored as
     * the engine's live Multiset, which it goes on mutating - which leaves a reader on any
     * other thread iterating something being written. Copying as it is stored costs the
     * thread that owns the value, where nothing is racing it.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void storingACollectionCopiesIt() {
        final PlayerView view = new PlayerView(1, null);
        final List<String> engineOwned = new ArrayList<>();
        engineOwned.add("first");

        view.set(TrackableProperty.NotedTypes, engineOwned);
        engineOwned.add("added after the store");

        final List<String> stored =
                (List<String>) view.getPropsCopy().get(TrackableProperty.NotedTypes);
        Assert.assertEquals(stored, List.of("first"),
                "the view followed the engine's later change to a collection it had stored");
    }

    /**
     * Reading all of an object's properties while the engine writes them neither throws nor
     * reports a property as absent that is not.
     *
     * <p>The properties live in an EnumMap, which does not check for concurrent modification.
     * Iterating the live one during a write does not throw a concurrent modification
     * exception - it reads values back as null, or runs off the end with a
     * NoSuchElementException. Either looks like state that was never set rather than like a
     * race, which is why this needs pinning rather than trusting the absence of crashes.
     */
    @Test
    public void readingAllPropertiesToleratesConcurrentWrites() throws Exception {
        final PlayerView view = new PlayerView(1, null);
        final Map<TrackableProperty, Object> live = view.getProps();
        final TrackableProperty[] keys = TrackableProperty.values();
        for (int i = 0; i < 16; i++) {
            live.put(keys[i], "v" + i);
        }

        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicLong reads = new AtomicLong();
        final AtomicLong writes = new AtomicLong();
        final AtomicLong absentValues = new AtomicLong();
        final AtomicLong thrown = new AtomicLong();

        final Thread writer = new Thread(() -> {
            int n = 0;
            while (!stop.get()) {
                final TrackableProperty key = keys[(n++) & 15];
                live.remove(key);
                live.put(key, "v" + key);
                writes.incrementAndGet();
            }
        });
        final Thread reader = new Thread(() -> {
            while (!stop.get()) {
                try {
                    for (final Map.Entry<TrackableProperty, Object> e : view.getPropsCopy().entrySet()) {
                        if (e.getValue() == null) {
                            absentValues.incrementAndGet();
                        }
                    }
                    reads.incrementAndGet();
                } catch (final Throwable t) {
                    thrown.incrementAndGet();
                }
            }
        });

        writer.start();
        reader.start();
        Thread.sleep(2000);
        stop.set(true);
        writer.join(5000);
        reader.join(5000);

        Assert.assertEquals(thrown.get(), 0L, "reading the property copy failed under concurrent writes");
        Assert.assertEquals(absentValues.get(), 0L, "the property copy reported a value as null");
        Assert.assertTrue(reads.get() > 0 && writes.get() > 0, "neither thread ran, so nothing was tested");
    }
}
