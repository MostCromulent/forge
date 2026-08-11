package forge.gamemodes.net.server;

import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.gamemodes.net.DeltaPacket;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.trackable.TrackableProperty;
import forge.trackable.Tracker;

import com.google.common.collect.MapMaker;


import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An immutable image of the view graph, already in the form that goes on the wire.
 *
 * <p>Keyed by delta key — the {@code (type, id)} pair identifying one view object — with each
 * object's properties reduced to plain data: ids in place of object references, ordinals in
 * place of enums, boxed primitives, and collections of the same. Nothing here refers back into
 * the live graph, so a snapshot can be read, diffed and encoded from any thread without racing
 * the engine. That is what lets the sending path stop reading state the game is still changing.
 *
 * <p>The values are safe to hold because of work done elsewhere: a property whose value is a
 * collection stores a private copy when it is set (see {@code TrackableTypes.detach}), so the
 * instance recorded here is one the engine has no reference to and cannot go on mutating.
 *
 * <p>A packet is {@link #diff} between two of these — the one just built and the one a client
 * was last sent. Building is client-independent, so one image per pass serves every client.
 */
final class ViewSnapshot {

    /** deltaKey to that object's properties, in network form. */
    private final Map<Integer, Map<TrackableProperty, Object>> objects;

    private ViewSnapshot(Map<Integer, Map<TrackableProperty, Object>> objects) {
        this.objects = objects;
    }

    static ViewSnapshot empty() {
        return new ViewSnapshot(Collections.emptyMap());
    }

    Map<Integer, Map<TrackableProperty, Object>> objects() {
        return objects;
    }

    int size() {
        return objects.size();
    }

    /**
     * The last snapshot taken of a game, and the change count it was taken at.
     *
     * <p>Keyed on the tracker rather than the view because a tracker belongs to one game and
     * has no equality of its own, while two views of successive games can compare equal - a
     * view is equal to anything of its class with its id. Weak keys so an entry dies with the
     * game that made it; a snapshot holds only ids and plain data, so it cannot keep the game
     * alive itself.
     *
     * <p>It lives here rather than on the tracker so that the engine's whole part in this is
     * one counter: nothing in the engine has to know a snapshot exists.
     */
    private static final ConcurrentMap<Tracker, AtomicReference<Stamped>> CACHE =
            new MapMaker().weakKeys().makeMap();

    private record Stamped(long changeCount, ViewSnapshot snapshot) {
    }

    /**
     * The current snapshot of this game, taken once however many clients ask for it.
     *
     * <p>Building it does not depend on who is asking, and during one engine action every
     * client collects in turn with nothing changing in between, so the same image would
     * otherwise be built once per client. Even a single client asks more than once per change,
     * because several things flush.
     */
    static ViewSnapshot current(GameView gameView) {
        final Tracker tracker = gameView == null ? null : gameView.getTracker();
        if (tracker == null) {
            return build(gameView);
        }
        final AtomicReference<Stamped> ref = CACHE.computeIfAbsent(tracker, t -> new AtomicReference<>());
        final Stamped cached = ref.get();
        // Read the count before building, never after: a build the engine changed underneath
        // then carries the older count, so the next reader rebuilds instead of trusting it.
        final long stamp = tracker.getChangeCount();
        if (cached != null && cached.changeCount() == stamp) {
            return cached.snapshot();
        }
        final ViewSnapshot snapshot = build(gameView);
        ref.set(new Stamped(stamp, snapshot));
        return snapshot;
    }

    /**
     * Record every object reachable from the game view.
     *
     * <p>Two rules, both inherited from what the graph actually contains. Zone collections are
     * scanned before anything else, so that where {@code copyCard} has left more than one
     * instance sharing a delta key, the one a zone holds is the one recorded. And a scalar
     * reference from one {@code GameEntityView} to another is not followed: those are the
     * references that go stale after a zone change, so following them would record the state a
     * card had when something last pointed at it.
     */
    static ViewSnapshot build(GameView gameView) {
        Map<Integer, TrackableObject> authoritative = new HashMap<>();
        seedZoneInstances(gameView, authoritative);

        Map<Integer, Map<TrackableProperty, Object>> objects = new LinkedHashMap<>();
        Set<TrackableObject> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        walk(gameView, authoritative, objects, visited);
        return new ViewSnapshot(objects);
    }

    private static void seedZoneInstances(GameView gameView, Map<Integer, TrackableObject> authoritative) {
        if (gameView == null || gameView.getPlayers() == null) {
            return;
        }
        for (PlayerView player : gameView.getPlayers()) {
            Map<TrackableProperty, Object> props = player.getPropsCopy();
            for (TrackableProperty zoneProp : DeltaSyncManager.ZONE_COLLECTIONS) {
                if (props.get(zoneProp) instanceof TrackableCollection<?> zone) {
                    for (Object item : zone) {
                        // Only the card that wins its own key seeds its states; a card
                        // that loses is stale, and its states are stale with it.
                        if (item instanceof CardView cv
                                && authoritative.putIfAbsent(DeltaPacket.makeDeltaKey(cv), cv) == null) {
                            seedCardStates(cv, authoritative);
                        }
                    }
                }
            }
        }
    }

    /**
     * The states of an authoritative card are themselves authoritative.
     *
     * <p>Zone membership only tells us which {@code CardView} wins at a key. A fresh
     * CardView seeds an empty CardStateView in its constructor, so without this a stale
     * card's empty state can outrank the live one at the same key and its properties
     * read as deleted.
     */
    private static void seedCardStates(CardView card, Map<Integer, TrackableObject> authoritative) {
        Map<TrackableProperty, Object> props = card.getPropsCopy();
        for (TrackableProperty stateProp : CARD_STATES) {
            if (props.get(stateProp) instanceof TrackableObject state
                    && DeltaPacket.typeTagFor(state) >= 0) {
                authoritative.putIfAbsent(DeltaPacket.makeDeltaKey(state), state);
            }
        }
    }

    private static final List<TrackableProperty> CARD_STATES = List.of(
            TrackableProperty.CurrentState,
            TrackableProperty.AlternateState,
            TrackableProperty.LeftSplitState,
            TrackableProperty.RightSplitState);

    private static void walk(TrackableObject obj,
                             Map<Integer, TrackableObject> authoritative,
                             Map<Integer, Map<TrackableProperty, Object>> objects,
                             Set<TrackableObject> visited) {
        if (DeltaPacket.typeTagFor(obj) < 0) {
            return;
        }
        int deltaKey = DeltaPacket.makeDeltaKey(obj);

        TrackableObject auth = authoritative.get(deltaKey);
        if (auth != null && auth != obj) {
            return;
        }
        // By instance and not by key, because only CardViews are seeded from the zone scan:
        // a CardStateView relies on a later instance at the same key replacing an earlier
        // one. Identity also makes this safe against cycles.
        if (!visited.add(obj)) {
            return;
        }

        Map<TrackableProperty, Object> props = obj.getPropsCopy();
        // Right-sized rather than an EnumMap: EnumMap allocates a slot per constant, and
        // TrackableProperty has over two hundred while a typical object sets a handful.
        Map<TrackableProperty, Object> recorded = new HashMap<>(Math.max(4, props.size() * 2));
        for (Map.Entry<TrackableProperty, Object> entry : props.entrySet()) {
            recorded.put(entry.getKey(), snapshotValue(entry.getKey(), entry.getValue()));
        }
        objects.put(deltaKey, recorded);

        boolean parentIsGameEntityView = obj instanceof GameEntityView;
        for (Map.Entry<TrackableProperty, Object> entry : props.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof TrackableObject child) {
                if (parentIsGameEntityView && child instanceof GameEntityView) {
                    continue;
                }
                walk(child, authoritative, objects, visited);
            } else if (value instanceof TrackableCollection<?> children) {
                for (TrackableObject child : children) {
                    walk(child, authoritative, objects, visited);
                }
            }
        }
    }

    /** Network form, which is already detached from the graph. */
    private static Object snapshotValue(TrackableProperty prop, Object value) {
        return DeltaSyncManager.toNetworkValue(prop, value);
    }

    /**
     * What changed between two snapshots, in the shape {@code DeltaPacket} expects.
     *
     * <p>A key absent from {@code previous} is new and carries its whole property map; a key
     * present in both carries only the properties whose values differ, with a property that
     * disappeared carried as an explicit null so the receiver clears it.
     *
     * <p>A key absent from {@code current} is not reported. An object leaving the graph leaves
     * the client holding it, which costs memory and nothing else: what the client displays is
     * reached from the game view, so an unreferenced object is already invisible. Telling it to
     * drop them is a separate change, and one that has to answer what happens when a card comes
     * back.
     */
    static Diff diff(ViewSnapshot previous, ViewSnapshot current) {
        Map<Integer, Map<TrackableProperty, Object>> newObjects = new LinkedHashMap<>();
        Map<Integer, Map<TrackableProperty, Object>> objectDeltas = new LinkedHashMap<>();

        for (Map.Entry<Integer, Map<TrackableProperty, Object>> entry : current.objects.entrySet()) {
            Map<TrackableProperty, Object> before = previous.objects.get(entry.getKey());
            if (before == null) {
                newObjects.put(entry.getKey(), entry.getValue());
                continue;
            }
            Map<TrackableProperty, Object> changed = new HashMap<>();
            for (Map.Entry<TrackableProperty, Object> prop : entry.getValue().entrySet()) {
                if (!Objects.equals(before.get(prop.getKey()), prop.getValue())) {
                    changed.put(prop.getKey(), prop.getValue());
                }
            }
            for (TrackableProperty gone : before.keySet()) {
                if (!entry.getValue().containsKey(gone)) {
                    changed.put(gone, null);
                }
            }
            if (!changed.isEmpty()) {
                objectDeltas.put(entry.getKey(), changed);
            }
        }

        return new Diff(newObjects, objectDeltas);
    }

    record Diff(Map<Integer, Map<TrackableProperty, Object>> newObjects,
                Map<Integer, Map<TrackableProperty, Object>> objectDeltas) {
    }
}
