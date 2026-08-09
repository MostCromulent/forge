package forge.trackable;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import forge.util.collect.FCollection;

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

    /**
     * An immutable view of the contents, rebuilt only when they change.
     *
     * <p>These hold view objects, and the threads that read them - the UI painting a zone,
     * the network layer building a packet - are never the thread the engine mutates them
     * from. Reading a copy covers every reader, including ones written later, instead of
     * whichever call sites someone remembered to guard.
     *
     * <p>Copying on each read instead would charge that to readers who are not racing
     * anything: a battlefield is drawn every frame and changes a few times a turn, so a
     * local game with no second thread in sight would allocate a list per frame. Making the
     * whole point of this a cost that only network play should bear is what the guards did.
     * The copy belongs on the write.
     */
    private transient volatile List<T> readOnly;

    @Override
    protected void onMutated() {
        readOnly = null;
    }

    private List<T> readOnly() {
        List<T> view = readOnly;
        if (view == null) {
            // Racing writers can only cost a redundant rebuild: safeCopy reads a consistent
            // enough instant, and whichever view is published is a reading the collection had.
            view = Collections.unmodifiableList(safeCopy());
            readOnly = view;
        }
        return view;
    }

    /**
     * Every way of reading the whole contents goes through the view, not just iteration:
     * taking a copy is as often written {@code new ArrayList<>(collection)}, which goes
     * through {@link #toArray()} and would otherwise miss this entirely.
     */
    @Override
    public Iterator<T> iterator() {
        // The view is unmodifiable, so removing through it fails loudly rather than quietly
        // editing something the caller will throw away. Nothing does that today.
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
