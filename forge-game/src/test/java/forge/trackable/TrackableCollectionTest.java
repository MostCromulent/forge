package forge.trackable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
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
     * Copying the contents while the engine writes them is as safe as iterating them.
     *
     * <p>Taking a copy is as often written {@code new ArrayList<>(collection)} as it is a
     * loop, and that route goes through {@code toArray}, not the iterator. Covering only
     * iteration would leave the more common spelling reading the live collection.
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
     * Every way of changing the contents is reflected by the next read.
     *
     * <p>Reads are served from a view rebuilt only when the contents change, so a mutator
     * that failed to discard it would serve stale contents indefinitely - a wrong answer
     * rather than a slow one. There is no way to check that generically, so every mutator
     * gets exercised here.
     */
    @Test
    public void everyMutatorIsReflectedInTheNextRead() {
        final TrackableCollection<PlayerView> c = new TrackableCollection<>();
        final PlayerView a = new PlayerView(1, null);
        final PlayerView b = new PlayerView(2, null);
        final PlayerView d = new PlayerView(3, null);

        c.add(a);
        Assert.assertEquals(contents(c), List.of(a), "add");

        c.addAll(List.of(b, d));
        Assert.assertEquals(contents(c), List.of(a, b, d), "addAll");

        c.remove(b);
        Assert.assertEquals(contents(c), List.of(a, d), "remove");

        c.add(1, b);
        Assert.assertEquals(contents(c), List.of(a, b, d), "add at index");

        c.set(0, d);
        Assert.assertEquals(contents(c).get(0), d, "set");

        c.replace(0, a);
        Assert.assertEquals(contents(c).get(0), a, "replace");

        c.remove(0);
        Assert.assertEquals(contents(c), List.of(b, d), "remove at index");

        c.removeIf(p -> p.getId() == 2);
        Assert.assertEquals(contents(c), List.of(d), "removeIf");

        c.addAll(List.of(a, b));
        c.retainAll(List.of(a, d));
        Assert.assertEquals(contents(c), List.of(d, a), "retainAll");

        c.sort(Comparator.comparingInt(PlayerView::getId));
        Assert.assertEquals(contents(c), List.of(a, d), "sort");

        c.clear();
        Assert.assertEquals(contents(c), List.of(), "clear");
    }

    /**
     * A read that races a write does not leave the cached view stale.
     *
     * <p>Reads are served from a view rebuilt on demand, so a reader that starts copying, is
     * overtaken by a write, and then publishes would store contents from before that write -
     * over the very invalidation the write left behind. Nothing rebuilds until the next
     * write, so the collection would serve contents it no longer has for as long as it sits
     * still. Once the writer stops, a read has to show everything it wrote.
     */
    @Test
    public void aReadRacingAWriteDoesNotLeaveTheViewStale() throws Exception {
        final int writes = 60;
        for (int round = 0; round < 300; round++) {
            final TrackableCollection<PlayerView> c = new TrackableCollection<>();
            final AtomicBoolean stop = new AtomicBoolean(false);

            final Thread reader = new Thread(() -> {
                while (!stop.get()) {
                    contents(c);
                }
            });
            reader.start();
            for (int i = 0; i < writes; i++) {
                c.add(new PlayerView(i, null));
            }
            stop.set(true);
            reader.join(5000);

            Assert.assertEquals(contents(c).size(), writes,
                    "round " + round + ": the view kept contents from before the last write");
        }
    }

    /** Reads through the iterator, which is what the cached view serves. */
    private static List<PlayerView> contents(final TrackableCollection<PlayerView> c) {
        final List<PlayerView> seen = new ArrayList<>();
        for (final PlayerView p : c) {
            seen.add(p);
        }
        return seen;
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
