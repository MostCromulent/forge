package forge.trackable;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.collect.Lists;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;

import forge.trackable.TrackableTypes.TrackableType;

/**
 * Per-game lookup + change-coalescing ledger for {@link TrackableObject}s. Owned by the
 * game thread; every TrackableObject in an active game's view holds a reference to one
 * instance.
 *
 * <p><b>Object lookup.</b> Stores ({@link TrackableTypes.TrackableType}, id) → instance.
 * Used by deserialization to resolve {@code IdRef} stand-ins back to canonical objects.
 *
 * <p><b>Freeze model.</b> {@link #freeze()}/{@link #unfreeze()} bracket a region during
 * which {@link TrackableObject#set} queues changes rather than applying them. When the
 * freeze counter reaches zero, queued changes replay through {@code set}. Used to bundle the
 * state changes of a multi-step engine effect into a single coherent post-effect snapshot. {@link #flush()} drains the
 * queue without leaving the frozen state.
 *
 * <p><b>Thread safety.</b> The freeze machinery is game-thread only: the {@code unfreeze}
 * replay writes to TrackableObjects, so running it from another thread races the engine.
 * The object lookup is not, because on a
 * client it cannot be — a message is decoded on the network thread, which resolves id
 * references against this table, while the previous message is still being applied on the
 * display thread, which writes to it. A read losing that race returns null, and the caller
 * reports a reference it could not resolve rather than failing, so the table is backed by
 * concurrent maps and the two at least do not corrupt it.
 */
public class Tracker {
    private int freezeCounter = 0;
    private final List<DelayedPropChange> delayedPropChanges = Lists.newArrayList();

    private final Table<TrackableType<?>, Integer, Object> objLookups =
            Tables.newCustomTable(new ConcurrentHashMap<>(), ConcurrentHashMap::new);

    private final AtomicLong changeCount = new AtomicLong();

    /**
     * How many property changes this game's view has seen.
     *
     * <p>For a reader that has built something from the whole view and wants to know whether
     * it is still current, without asking what changed. Monotonic, and only bumped when a
     * property actually takes a new value.
     */
    public long getChangeCount() {
        return changeCount.get();
    }

    // Atomic rather than a plain increment: the game thread is the sanctioned mutator, but
    // dev cheats enter from the display thread and concede runs inline on a network thread,
    // and a lost increment could move the count backwards - which would let a stale reading
    // look current again.
    void recordChange() {
        changeCount.incrementAndGet();
    }

    public final boolean isFrozen() {
        return freezeCounter > 0;
    }

    public void freeze() {
        freezeCounter++;
    }

    // Note: objLookups exist on the tracker and not on the TrackableType because
    // TrackableType is global and Tracker is per game.
    @SuppressWarnings("unchecked")
    public <T> T getObj(TrackableType<T> type, Integer id) {
        return (T)objLookups.get(type, id);
    }

    public boolean hasObj(TrackableType<?> type, Integer id) {
        return objLookups.contains(type, id);
    }

    public <T> void putObj(TrackableType<T> type, Integer id, T val) {
        objLookups.put(type, id, val);
    }

    public void unfreeze() {
        if (!isFrozen() || --freezeCounter > 0 || delayedPropChanges.isEmpty()) {
            return;
        }
        //after being unfrozen, ensure all changes delayed during freeze are now applied
        for (final DelayedPropChange change : delayedPropChanges) {
            change.object.set(change.prop, change.value);
        }
        delayedPropChanges.clear();
    }

    public void flush() {
        // unfreeze and refreeze the tracker in order to flush current pending properties
        if (!isFrozen()) {
            return;
        }
        unfreeze();
        freeze();
    }

    public void addDelayedPropChange(final TrackableObject object, final TrackableProperty prop, final Object value) {
        delayedPropChanges.add(new DelayedPropChange(object, prop, value));
    }

    public void clearDelayed() {
        delayedPropChanges.clear();
    }

    private class DelayedPropChange {
        private final TrackableObject object;
        private final TrackableProperty prop;
        private final Object value;
        private DelayedPropChange(final TrackableObject object0, final TrackableProperty prop0, final Object value0) {
            object = object0;
            prop = prop0;
            value = value0;
        }
        @Override public String toString() {
            return "Set " + prop + " of " + object + " to " + value;
        }
    }
}
