package forge.trackable;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import forge.util.collect.FCollection;

/**
 * A collection of view objects that any thread may read while the engine writes to it.
 *
 * <p>The threads that read these — the UI painting a zone, the network layer building a packet
 * — are never the thread the engine mutates them from. So every read of the whole contents is
 * served from an immutable copy, rebuilt only when the contents change. That covers every
 * reader, including ones written later, rather than the call sites someone remembered to guard.
 *
 * <p>The copy is made when the collection changes, not when it is read. Copying per read would
 * charge readers that are not racing anything: a battlefield is drawn every frame and changes a
 * few times a turn, so a local game with no second thread in sight would allocate a list per
 * frame.
 */
public class TrackableCollection<T extends TrackableObject> extends FCollection<T> {
    private static final long serialVersionUID = 1528674215758232314L;

    public TrackableCollection() {
    }
    public TrackableCollection(T e) {
        super(e);
    }
    public TrackableCollection(Collection<T> c) {
        super(c);
    }
    public TrackableCollection(Iterable<T> i) {
        super(i);
    }

    /** Bumped on every change, so a copy taken before one can be told from a current one. */
    private transient volatile int version;

    // Counted through a field updater rather than an AtomicInteger field: these are
    // serialized, and a transient object arrives null on the far side where an int arrives 0.
    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<TrackableCollection> VERSION =
            AtomicIntegerFieldUpdater.newUpdater(TrackableCollection.class, "version");

    private transient volatile Cached<T> cached;

    // Atomic because the engine is not quite the only writer - a dev cheat arrives on the
    // display thread, a concede on a network thread - and a lost increment would republish a
    // version, which is the stale reading this exists to catch.
    @Override
    protected void onMutated() {
        VERSION.incrementAndGet(this);
        cached = null;
    }

    /**
     * The version a cached view was built from, so one that raced a write is spotted.
     *
     * <p>Without it a reader that starts copying, is overtaken by a write, and then publishes
     * would store contents from before that write - over the very null the write left to
     * invalidate it. Nothing would rebuild until the next write, so the collection would serve
     * a reading it no longer has, indefinitely. Stamping the copy with the version it was
     * taken at turns that into a mismatch the next read repairs.
     */
    private record Cached<E>(int version, List<E> items) {
    }

    private List<T> readOnly() {
        final int stamp = version;
        final Cached<T> current = cached;
        if (current != null && current.version() == stamp) {
            return current.items();
        }
        final List<T> items = Collections.unmodifiableList(safeCopy());
        cached = new Cached<>(stamp, items);
        return items;
    }

    /**
     * Every way of reading the whole contents goes through the view, not just iteration:
     * taking a copy is as often written {@code new ArrayList<>(collection)}, which goes
     * through {@link #toArray()} and would otherwise miss this entirely.
     */
    @Override
    public Iterator<T> iterator() {
        // Unmodifiable, so removing through it fails loudly rather than editing a copy.
        return readOnly().iterator();
    }

    @Override
    public Object[] toArray() {
        return readOnly().toArray();
    }

    @Override
    @SuppressWarnings("hiding")
    public <T> T[] toArray(final T[] a) {
        return readOnly().toArray(a);
    }

    @Override
    public Stream<T> stream() {
        return readOnly().stream();
    }

    @Override
    public boolean anyMatch(final Predicate<? super T> test) {
        return readOnly().stream().anyMatch(test);
    }

    @Override
    public boolean allMatch(final Predicate<? super T> test) {
        return readOnly().stream().allMatch(test);
    }
}
