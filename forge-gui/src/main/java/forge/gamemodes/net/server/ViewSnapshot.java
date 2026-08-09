package forge.gamemodes.net.server;

import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.gamemodes.net.DeltaPacket;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.trackable.TrackableProperty;
import forge.util.IHasForgeLog;

import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multiset;

import forge.card.CardType;
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
final class ViewSnapshot implements IHasForgeLog {

    /** deltaKey to that object's properties, in network form. */
    private final Map<Integer, Map<TrackableProperty, Object>> objects;

    /**
     * Which instance each key was read from, kept only while auditing. Two instances can
     * share a key, and when the two paths disagree the first thing worth knowing is whether
     * they were even looking at the same object.
     */
    private final Map<Integer, Integer> readFrom = new HashMap<>();

    private ViewSnapshot(Map<Integer, Map<TrackableProperty, Object>> objects) {
        this.objects = objects;
    }

    /** Identity of the instance this key's values came from, or null if not auditing. */
    Integer instanceFor(int deltaKey) {
        return readFrom.get(deltaKey);
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
        ViewSnapshot snapshot = new ViewSnapshot(objects);
        walk(gameView, authoritative, objects, visited, snapshot.readFrom);
        return snapshot;
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
                             Set<TrackableObject> visited,
                             Map<Integer, Integer> readFrom) {
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

        Map<TrackableProperty, Object> props = obj.getPropsCopy();
        // Right-sized rather than an EnumMap: EnumMap allocates a slot per constant, and
        // TrackableProperty has over two hundred while a typical object sets a handful.
        Map<TrackableProperty, Object> recorded = new HashMap<>(Math.max(4, props.size() * 2));
        for (Map.Entry<TrackableProperty, Object> entry : props.entrySet()) {
            recorded.put(entry.getKey(), snapshotValue(entry.getKey(), entry.getValue()));
        }
        objects.put(deltaKey, recorded);
        if (AUDIT_VALUES) {
            readFrom.put(deltaKey, System.identityHashCode(obj));
        }

        boolean parentIsGameEntityView = obj instanceof GameEntityView;
        for (Map.Entry<TrackableProperty, Object> entry : props.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof TrackableObject child) {
                if (parentIsGameEntityView && child instanceof GameEntityView) {
                    continue;
                }
                walk(child, authoritative, objects, visited, readFrom);
            } else if (value instanceof TrackableCollection<?> children) {
                for (TrackableObject child : children) {
                    walk(child, authoritative, objects, visited, readFrom);
                }
            }
        }
    }

    /** Network form, which is already detached from the graph. */
    private static Object snapshotValue(TrackableProperty prop, Object value) {
        Object result = DeltaSyncManager.toNetworkValue(prop, value);
        if (AUDIT_VALUES) {
            auditImmutable(prop, result);
        }
        return result;
    }

    private static final boolean AUDIT_VALUES = Boolean.getBoolean("forge.snapshot.shadow");
    private static final Set<String> AUDIT_REPORTED = Collections.synchronizedSet(new HashSet<>());

    /**
     * Report any snapshot value that is not of a type known to be immutable and
     * value-comparable.
     *
     * <p>The whole point of a snapshot is that it can be read and diffed from any thread
     * without racing the engine, which holds only if nothing in it aliases live state or
     * compares by identity. That claim was established by reading the property types;
     * this checks it against what actually flows through. One report per offending type.
     */
    private static void auditImmutable(TrackableProperty prop, Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character || value instanceof Enum<?>) {
            return;
        }
        // canonical() returns these wrapped; the wrappers are the marker that a defensive
        // copy was taken rather than the live collection passed through.
        String type = value.getClass().getName();
        if (type.startsWith("java.util.Collections$Unmodifiable")
                || type.startsWith("java.util.ImmutableCollections")
                || value instanceof ImmutableMultiset
                // Records over freshly built contents, which is what they were made for.
                || value instanceof DeltaPacket.EntityRef
                || value instanceof DeltaPacket.CombatData
                // Mutable and identity-equal, but this is a private copy nothing else holds,
                // and the diff compares it through canonical().
                || value instanceof CardType
                || value instanceof forge.card.ColorSet
                || value instanceof forge.card.mana.ManaCost
                || value instanceof forge.item.IPaperCard
                // No mutators, and equals/hashCode delegate to its backing map.
                || value instanceof forge.game.keyword.KeywordCollectionView) {
            return;
        }
        if (AUDIT_REPORTED.add(type)) {
            netLog.warn("[Shadow] snapshot value of unaudited type {} for {} — verify it is "
                    + "immutable and compares by value", type, prop);
        }
    }

    /**
     * Reduce a network value to something that compares by value. Applied to both sides of
     * any comparison, so a form that is merely unstable is equally handled. Never stored
     * and never sent — see {@link #detached}.
     */
    static Object canonical(Object value) {
        if (value == null) {
            return null;
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
    /**
     * Whether two stored values represent the same thing. Plain equality settles almost
     * everything; canonicalising is the fallback for the types that compare by identity,
     * and running it second keeps its allocation off the path taken by every unchanged
     * property.
     */
    private static boolean same(Object before, Object after) {
        return java.util.Objects.equals(before, after)
                || java.util.Objects.equals(canonical(before), canonical(after));
    }

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
                if (!same(before.get(prop.getKey()), prop.getValue())) {
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
