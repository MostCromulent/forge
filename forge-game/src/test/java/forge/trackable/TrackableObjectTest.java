package forge.trackable;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.player.PlayerView;

public class TrackableObjectTest {

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
