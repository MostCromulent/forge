package forge.trackable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.player.PlayerView;

public class TrackableCollectionTest {

    /**
     * Reading one of these while the engine writes it neither throws nor yields a null.
     *
     * <p>View collections are read from threads that never own the game: the UI painting a
     * zone, the network layer building a packet. Plain iteration used to hand those readers
     * the live backing list, so a write during a repaint threw a concurrent modification
     * exception. Guarding at the reader was the previous remedy and it only ever covered the
     * call sites someone remembered.
     */
    @Test
    public void iterationToleratesConcurrentWrites() throws Exception {
        assertReadsCleanlyUnderWrites(collection -> {
            for (final PlayerView p : collection) {
                if (p == null) {
                    throw new AssertionError("null element");
                }
            }
        });
    }

    /**
     * Taking a copy is as often written {@code new ArrayList<>(collection)} as it is a loop,
     * and that route goes through {@code toArray} rather than the iterator.
     */
    @Test
    public void copyingToleratesConcurrentWrites() throws Exception {
        assertReadsCleanlyUnderWrites(collection -> {
            for (final PlayerView p : new ArrayList<>(collection)) {
                if (p == null) {
                    throw new AssertionError("null element");
                }
            }
        });
    }

    private void assertReadsCleanlyUnderWrites(final Consumer<TrackableCollection<PlayerView>> read)
            throws Exception {
        final TrackableCollection<PlayerView> collection = new TrackableCollection<>();
        for (int i = 0; i < 40; i++) {
            collection.add(new PlayerView(i, null));
        }

        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicLong reads = new AtomicLong();
        final AtomicLong writes = new AtomicLong();
        final AtomicLong thrown = new AtomicLong();

        final Thread writer = new Thread(() -> {
            int n = 0;
            while (!stop.get()) {
                final PlayerView p = new PlayerView(1000 + (n++ & 0xFF), null);
                collection.add(p);
                collection.remove(p);
                writes.incrementAndGet();
            }
        });
        final Thread reader = new Thread(() -> {
            while (!stop.get()) {
                try {
                    read.accept(collection);
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

        Assert.assertEquals(thrown.get(), 0L, "reading a view collection failed under concurrent writes");
        Assert.assertTrue(reads.get() > 0 && writes.get() > 0, "neither thread ran, so nothing was tested");
    }

    /**
     * Iteration hands out a copy, so removing through it would edit something the caller is
     * about to discard. It refuses instead.
     */
    @Test(expectedExceptions = UnsupportedOperationException.class)
    public void removingThroughTheIteratorIsRefused() {
        final TrackableCollection<PlayerView> collection = new TrackableCollection<>();
        collection.add(new PlayerView(1, null));

        final Iterator<PlayerView> it = collection.iterator();
        it.next();
        it.remove();
    }
}
