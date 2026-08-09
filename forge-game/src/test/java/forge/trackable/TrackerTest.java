package forge.trackable;

import java.util.concurrent.atomic.AtomicLong;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.player.PlayerView;

public class TrackerTest {

    /**
     * Registering and looking up while another thread does the same loses nothing.
     *
     * <p>A client cannot keep this to one thread. A message is decoded on the network
     * thread, which resolves the ids in it against this table, while the message before it
     * is still being applied on the display thread, which writes to it. An unguarded map
     * loses entries when a write resizes it under a reader, and the reader's null is
     * reported as an id that could not be resolved - a property quietly not arriving.
     */
    @Test
    public void concurrentRegistrationAndLookupLosesNothing() throws Exception {
        final int perThread = 2000;
        final Tracker tracker = new Tracker();
        final AtomicLong thrown = new AtomicLong();

        // Registered before anything races, so every lookup of it below must find it. A
        // reader traversing a table another thread is growing can miss what is already there.
        final PlayerView alwaysPresent = new PlayerView(-1, null);
        tracker.putObj(TrackableTypes.PlayerViewType, -1, alwaysPresent);

        final AtomicLong missed = new AtomicLong();
        final Runnable writer = () -> {
            for (int i = 0; i < perThread; i++) {
                tracker.putObj(TrackableTypes.PlayerViewType, i, new PlayerView(i, null));
            }
        };
        final Runnable reader = () -> {
            try {
                for (int i = 0; i < perThread * 20; i++) {
                    if (tracker.getObj(TrackableTypes.PlayerViewType, -1) == null) {
                        missed.incrementAndGet();
                    }
                }
            } catch (final Throwable t) {
                thrown.incrementAndGet();
            }
        };

        final Thread[] writers = {new Thread(writer), new Thread(writer)};
        final Thread[] readers = {new Thread(reader), new Thread(reader)};
        for (final Thread t : writers) { t.start(); }
        for (final Thread t : readers) { t.start(); }
        for (final Thread t : writers) { t.join(10000); }
        for (final Thread t : readers) { t.join(10000); }

        Assert.assertEquals(thrown.get(), 0L, "a lookup failed while another thread registered");
        Assert.assertEquals(missed.get(), 0L, "a lookup missed an entry registered before it ran");
        for (int i = 0; i < perThread; i++) {
            Assert.assertNotNull(tracker.getObj(TrackableTypes.PlayerViewType, i),
                    "registration " + i + " was lost");
        }
    }
}
