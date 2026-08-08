package forge.gamemodes.net.server;

import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.gamemodes.net.DeltaPacket;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.trackable.TrackableProperty;

import com.google.common.collect.Multiset;

import forge.card.CardTypeView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An image of the view graph in network form, holding no references into it.
 *
 * <p>Every value is reduced to plain data — ids, id lists, ordinals, boxed primitives
 * and defensive copies of collections — so a snapshot can be read, diffed and encoded
 * from any thread without racing the engine. This is the piece that lets the network
 * layer stop walking live state.
 *
 * <p>Values are also canonicalised for comparison. Several of the forms
 * {@code DeltaSyncManager.toNetworkValue} produces have identity equality
 * ({@code int[]} for polymorphic refs, {@code CardType}, {@code CombatData}), which is
 * invisible while a dirty bit decides what to send but would make a value diff resend
 * them on every pass. {@link #canonical} is applied to both sides of any comparison.
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
     * Walk the graph and record every reachable object.
     *
     * <p>Mirrors {@code DeltaSyncManager.walkAndCollect}: zone collections are scanned
     * first so the authoritative instance for a deltaKey wins, since {@code copyCard}
     * leaves stale instances sharing a key, and scalar {@code GameEntityView} to
     * {@code GameEntityView} references are not followed because they are the ones that
     * go stale after a zone change.
     */
    static ViewSnapshot build(GameView gameView) {
        Map<Integer, TrackableObject> authoritative = new HashMap<>();
        seedZoneInstances(gameView, authoritative);

        Map<Integer, Map<TrackableProperty, Object>> objects = new LinkedHashMap<>();
        Set<TrackableObject> visited = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        walk(gameView, authoritative, objects, visited);
        return new ViewSnapshot(objects);
    }

    private static void seedZoneInstances(GameView gameView, Map<Integer, TrackableObject> authoritative) {
        if (gameView == null || gameView.getPlayers() == null) {
            return;
        }
        for (PlayerView player : gameView.getPlayers()) {
            Map<TrackableProperty, Object> props = player.getProps();
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
        Map<TrackableProperty, Object> props = card.getProps();
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
        // Dedup by instance, not by key: only CardViews get an authoritative seed from
        // the zone scan, so a CardStateView relies on a later instance at the same key
        // overwriting an earlier one, exactly as the walk treats a replacement.
        // Identity also makes this cycle-safe.
        if (!visited.add(obj)) {
            return;
        }

        Map<TrackableProperty, Object> props = obj.getProps();
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

    /** Network form, then canonicalised so it holds no reference into the graph. */
    private static Object snapshotValue(TrackableProperty prop, Object value) {
        return canonical(DeltaSyncManager.toNetworkValue(prop, value));
    }

    /**
     * Reduce a network value to something that compares by value and shares no mutable
     * state with the engine. Applied to both sides of any comparison, so a form that is
     * merely unstable (rather than aliased) is equally handled.
     */
    static Object canonical(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof DeltaPacket.CombatData combat) {
            // Freshly built per conversion, with no equals, so it never compares equal
            // to its predecessor. Reduce it to its band contents.
            List<Object> bands = new ArrayList<>();
            bands.add(canonical(combat.bandAttackerIds));
            List<Object> defenders = new ArrayList<>();
            for (int[] ref : combat.bandDefenderRefs) {
                defenders.add(canonical(ref));
            }
            bands.add(Collections.unmodifiableList(defenders));
            bands.add(canonical(combat.bandBlockerIds));
            bands.add(canonical(combat.bandPlannedBlockerIds));
            return Collections.unmodifiableList(bands);
        }
        if (value instanceof int[] ints) {
            List<Integer> boxed = new ArrayList<>(ints.length);
            for (int i : ints) {
                boxed.add(i);
            }
            return Collections.unmodifiableList(boxed);
        }
        if (value instanceof CardTypeView type) {
            List<String> parts = new ArrayList<>();
            for (Object t : type.getCoreTypes()) {
                parts.add("c:" + t);
            }
            for (Object t : type.getSupertypes()) {
                parts.add("s:" + t);
            }
            for (Object t : type.getSubtypes()) {
                parts.add("u:" + t);
            }
            for (Object t : type.getExcludedCreatureSubTypes()) {
                parts.add("x:" + t);
            }
            parts.add("all:" + type.hasAllCreatureTypes());
            return Collections.unmodifiableList(parts);
        }
        if (value instanceof Multiset<?> multiset) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Multiset.Entry<?> e : multiset.entrySet()) {
                counts.put(String.valueOf(e.getElement()), e.getCount());
            }
            return Collections.unmodifiableMap(counts);
        }
        if (value instanceof Map<?, ?> map) {
            // Not Map.copyOf — it rejects null keys and values, and this runs on the
            // engine thread where a stray null must not become an exception.
            return Collections.unmodifiableMap(new LinkedHashMap<>(map));
        }
        if (value instanceof Set<?> set) {
            return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(set));
        }
        if (value instanceof Collection<?> collection) {
            return Collections.unmodifiableList(new ArrayList<>(collection));
        }
        return value;
    }

    /**
     * What changed between two snapshots, in the shape {@code DeltaPacket} expects.
     *
     * <p>A key absent from {@code previous} is new and carries its whole property map; a
     * key present in both carries only the properties whose values differ, with a
     * property that disappeared carried as an explicit null so the receiver clears it; a
     * key absent from {@code current} has left the graph.
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
                if (!java.util.Objects.equals(before.get(prop.getKey()), prop.getValue())) {
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

        Set<Integer> evicted = new HashSet<>(previous.objects.keySet());
        evicted.removeAll(current.objects.keySet());

        return new Diff(newObjects, objectDeltas, evicted);
    }

    record Diff(Map<Integer, Map<TrackableProperty, Object>> newObjects,
                Map<Integer, Map<TrackableProperty, Object>> objectDeltas,
                Set<Integer> evicted) {
    }
}
