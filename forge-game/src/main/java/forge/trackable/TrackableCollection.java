package forge.trackable;

import java.util.Collection;
import java.util.Iterator;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.google.common.collect.Iterators;

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
     * Reading the whole contents always gives a private copy.
     *
     * <p>These hold view objects, and the threads that read them - the UI painting a zone,
     * the network layer building a packet - are never the thread the engine mutates them
     * from. Copying here covers every reader, including ones written later, instead of at
     * whichever call sites someone remembered to guard.
     *
     * <p>Every way of reading the contents is covered, not just iteration: taking a copy is
     * as often written {@code new ArrayList<>(collection)}, which goes through
     * {@link #toArray()} and would otherwise miss this entirely.
     */
    @Override
    public Iterator<T> iterator() {
        // Unmodifiable, so removing through it fails loudly rather than quietly editing
        // something the caller will throw away. Nothing does that today.
        return Iterators.unmodifiableIterator(safeCopy().iterator());
    }

    @Override
    public Object[] toArray() {
        return safeCopy().toArray();
    }

    @Override
    @SuppressWarnings("hiding")
    public <T> T[] toArray(final T[] a) {
        return safeCopy().toArray(a);
    }

    @Override
    public Stream<T> stream() {
        return safeCopy().stream();
    }

    @Override
    public boolean anyMatch(final Predicate<? super T> test) {
        return safeCopy().stream().anyMatch(test);
    }

    @Override
    public boolean allMatch(final Predicate<? super T> test) {
        return safeCopy().stream().allMatch(test);
    }
}
