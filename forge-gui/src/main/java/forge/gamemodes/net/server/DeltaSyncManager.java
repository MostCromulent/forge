package forge.gamemodes.net.server;

import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.gamemodes.net.DeltaPacket;
import forge.gamemodes.net.DeltaPacket.CombatData;
import forge.gamemodes.net.NetworkChecksumUtil;
import forge.game.combat.CombatView;
import forge.util.collect.FCollection;

import forge.util.IHasForgeLog;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.trackable.TrackableProperty;
import forge.trackable.TrackableTypes;
import forge.trackable.TrackableTypes.TrackableType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages delta synchronization between server and clients.
 * Tracks changes to TrackableObjects via per-consumer dirty tracking and builds
 * minimal delta packets using property maps.
 */
public class DeltaSyncManager implements IHasForgeLog {

    // How often to include a checksum for validation (every N packets)
    public static final int CHECKSUM_INTERVAL = 20;
    private static final int MIN_CHECKSUM_INTERVAL = 5;
    private static final int CLEAN_STREAK_TO_RESTORE = 10;
    private static final int SAMPLE_SIZE = 15;

    // Zone collection properties on PlayerView — the authoritative source for
    // CardView instances. Cross-reference properties (Commander, AttachedCards,
    // ExiledWith, etc.) may hold stale instances after zone changes via copyCard.
    // Built dynamically from ZoneType's trackable property mapping.
    // Excludes Flashback: virtual zone whose cards are references to cards in
    // other zones (Graveyard, Library, etc.), not unique canonical instances.
    static final EnumSet<TrackableProperty> ZONE_COLLECTIONS = EnumSet.noneOf(TrackableProperty.class);
    static {
        for (ZoneType z : ZoneType.values()) {
            TrackableProperty prop = z.getTrackableProperty();
            if (prop != null && z != ZoneType.Flashback) {
                ZONE_COLLECTIONS.add(prop);
            }
        }
    }

    /**
     * Build a {@link ViewSnapshot} alongside each walk and report where they disagree.
     * Off by default: diagnostic only, and it doubles the per-pass graph traversal.
     */
    private static final boolean SHADOW_SNAPSHOT = Boolean.getBoolean("forge.snapshot.shadow");

    /**
     * Build packets by diffing snapshots instead of walking the graph for dirty bits.
     * The two are meant to produce the same client state, so this exists to run them
     * against each other in the same harness.
     */
    private static final boolean SNAPSHOT_AUTHORITY = Boolean.getBoolean("forge.snapshot.authority");

    /** Whether packets come from snapshot diffs, which decides how a client is seeded. */
    public static boolean snapshotAuthority() {
        return SNAPSHOT_AUTHORITY;
    }

    /**
     * The last snapshot this client is known to hold. Replaced wholesale, never
     * mutated, so the encode gate can read it from a Netty thread without locking.
     * Empty unless the snapshot is being maintained.
     */
    private volatile ViewSnapshot baseline = ViewSnapshot.empty();

    /**
     * The last snapshot built for this client, kept across a reconnect where the baseline
     * is not. The two answer different questions — what was last seen of the game, versus
     * what this client is believed to hold — and a reconnect resets only the second.
     */
    private volatile ViewSnapshot published = ViewSnapshot.empty();

    /**
     * A packet carrying the whole of the last published snapshot, for a client that has
     * just reconnected and holds nothing.
     *
     * <p>Pure computation over an immutable value, which is the point: a reconnect is
     * handled on a Netty thread, and it cannot be deferred to an engine-owned site because
     * the engine is parked awaiting the very client being reseeded. Walking the live graph
     * there is what this replaces. It carries no checksum for the same reason — computing
     * one reads live state.
     *
     * <p>Returns null before anything has been published, which is the game-start case;
     * that one runs on the engine thread and can build normally.
     */
    DeltaPacket reseedFromPublished() {
        final ViewSnapshot snapshot = published;
        if (snapshot.size() == 0) {
            return null;
        }
        final ViewSnapshot.Diff diff = ViewSnapshot.diff(ViewSnapshot.empty(), snapshot);
        baseline = snapshot;
        sequenceNumber++;
        return new DeltaPacket(sequenceNumber, diff.objectDeltas(), diff.newObjects(), 0, null);
    }

    /**
     * Forget what this client is believed to hold, so the next packet is a full state
     * through the ordinary diff.
     *
     * <p>The baseline advances as soon as a packet is built, which keeps the encode gate
     * accurate for the messages that follow it — those are encoded on the calling thread,
     * before any write could have completed. The cost of advancing early is that anything
     * stopping a packet from reaching the socket would leave the server believing it had
     * sent state it never did, and never sending it again. Discarding the baseline on any
     * failed delivery is what bounds that: the recovery path is the normal path.
     */
    void invalidateBaseline() {
        baseline = ViewSnapshot.empty();
    }

    /** Per-thread allocation counter, when the JVM exposes one. Shadow diagnostics only. */
    private static final com.sun.management.ThreadMXBean ALLOC_BEAN = allocBean();

    private static com.sun.management.ThreadMXBean allocBean() {
        if (!SHADOW_SNAPSHOT) {
            return null;
        }
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()) {
            sun.setThreadAllocatedMemoryEnabled(true);
            return sun;
        }
        return null;
    }

    private static long allocatedBytes() {
        return ALLOC_BEAN == null ? 0L : ALLOC_BEAN.getCurrentThreadAllocatedBytes();
    }

    // each DeltaSyncManager gets a unique ID
    private static final AtomicInteger NEXT_CONSUMER_ID = new AtomicInteger(0);
    private final int consumerId = NEXT_CONSUMER_ID.getAndIncrement();

    /**
     * Whether this client already holds {@code obj}, which is what decides whether a
     * reference to it travels as an id or is serialized inline. Handed to the encoder
     * as the IdRef gate.
     *
     * <p>The walk answers this from consumer registration; the snapshot answers it from
     * the baseline, which is what was actually sent. Both answers are computed while the
     * snapshot is being shadowed, because a wrong one here fails silently: too permissive
     * ships an id the client cannot resolve, too conservative inlines a whole card into
     * every packet that mentions one.
     */
    boolean receiverKnows(TrackableObject obj) {
        int deltaKey = DeltaPacket.makeDeltaKey(obj);
        if (SNAPSHOT_AUTHORITY) {
            boolean inBaseline = baseline.objects().containsKey(deltaKey);
            (inBaseline ? gateIdRef : gateInline).incrementAndGet();
            return inBaseline;
        }
        boolean registered = obj.hasConsumer(consumerId);
        if (!SHADOW_SNAPSHOT) {
            return registered;
        }
        (registered ? gateIdRef : gateInline).incrementAndGet();
        if (baseline.objects().containsKey(deltaKey) != registered) {
            gateDisagreed.incrementAndGet();
            if (gateDisagreementKeys.add(deltaKey)) {
                netLog.warn("[Gate] key={} id={}: registered={} inBaseline={}",
                        String.format("0x%08X", deltaKey), obj.getId(), registered, !registered);
            }
        }
        return registered;
    }

    /**
     * Tallies for the encode gate. {@code inline} is the one worth watching: it counts
     * references serialized as whole objects, and it is the answer that fails silently,
     * since an id the client cannot resolve at least warns on arrival.
     */
    private final AtomicInteger gateIdRef = new AtomicInteger();
    private final AtomicInteger gateInline = new AtomicInteger();
    private final AtomicInteger gateDisagreed = new AtomicInteger();
    private final Set<Integer> gateDisagreementKeys = Collections.synchronizedSet(new HashSet<>());

    private long sequenceNumber = 0;

    // Objects registered with this consumer (for cleanup on disconnect/reset)
    private final Map<Integer, TrackableObject> registeredByKey = new HashMap<>();
    // Used to block stale cross-reference replacements
    private final Map<Integer, CardView> authoritativeInstances = new HashMap<>();

    // Not atomic: only accessed from game thread
    // Defer the first checksum until the game state stabilizes — seq=1 races
    // with game initialization (hand drawing), so an immediate checksum would
    // compare a mid-init snapshot against the client's post-delta state.
    private long packetsSinceLastChecksum = 0;

    // Sampled checksum state
    private final EnumSet<TrackableProperty> recentDeltaProperties = EnumSet.noneOf(TrackableProperty.class);
    private int checksumInterval = CHECKSUM_INTERVAL;
    private int cleanChecksumStreak = 0;
    // Stored at checksum time, logged on resync request
    private String lastChecksumBreakdown;
    private List<String> lastChecksumDetail;

    /**
     * Collect all changes from the GameView hierarchy and build a delta packet.
     * New objects are registered with this consumer and sent in full.
     * Existing objects only send properties dirty for THIS consumer.
     *
     * <p>Must be called on the game thread. All delta collection and checksum
     * computation runs single-threaded — no locks, snapshots, or volatile
     * barriers needed.
     */
    public DeltaPacket collectDeltas(GameView gameView) {
        Thread self = Thread.currentThread();
        Thread concurrent = collectOwner.getAndSet(self);
        if (concurrent != null && concurrent != self) {
            netLog.warn("[Collect] Concurrent entry: {} entered while {} was still inside. "
                            + "Per-consumer registration state and the view graph are both unguarded here.",
                    self.getName(), concurrent.getName());
        }
        if (SHADOW_SNAPSHOT && collectThreads.add(self.getName())) {
            netLog.info("[Collect] First entry from thread {} ({} distinct so far)",
                    self.getName(), collectThreads.size());
        }
        try {
            return collectDeltasInternal(gameView);
        } finally {
            collectOwner.compareAndSet(self, null);
        }
    }

    /**
     * Records which thread is inside {@link #collectDeltas}, so a second one entering is
     * reported rather than silently corrupting per-consumer state.
     *
     * <p>Deliberately a detector and not a guard: it observes, it does not serialise. It
     * also logs rather than throwing, because this is reachable from Netty threads where
     * an exception reaches {@code exceptionCaught} and closes the channel — and unlike
     * {@code FThreads.assertExecutedByEdt}, which returns early under net play, it has to
     * stay live in exactly the mode where these problems occur.
     */
    private final java.util.concurrent.atomic.AtomicReference<Thread> collectOwner =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final Set<String> collectThreads = Collections.synchronizedSet(new HashSet<>());

    private DeltaPacket collectDeltasInternal(GameView gameView) {
        if (SNAPSHOT_AUTHORITY) {
            ViewSnapshot current = ViewSnapshot.build(gameView);
            ViewSnapshot.Diff diff = ViewSnapshot.diff(baseline, current);
            baseline = current;
            published = current;
            return finishPacket(gameView, diff.objectDeltas(), diff.newObjects());
        }

        Map<Integer, Map<TrackableProperty, Object>> objectDeltas = new HashMap<>();
        // need parent-before-child insertion order
        Map<Integer, Map<TrackableProperty, Object>> newObjects = new LinkedHashMap<>();
        Set<Integer> currentObjectIds = new HashSet<>();

        long walkStart = SHADOW_SNAPSHOT ? System.nanoTime() : 0L;
        long walkAllocStart = SHADOW_SNAPSHOT ? allocatedBytes() : 0L;
        authoritativeInstances.clear();
        preScanZoneCollections(gameView);
        walkAndCollect(gameView, objectDeltas, newObjects, currentObjectIds);
        long walkNanos = SHADOW_SNAPSHOT ? System.nanoTime() - walkStart : 0L;
        long walkAlloc = SHADOW_SNAPSHOT ? allocatedBytes() - walkAllocStart : 0L;

        // Prune registrations for objects no longer in the graph
        Iterator<Map.Entry<Integer, TrackableObject>> regIt = registeredByKey.entrySet().iterator();
        while (regIt.hasNext()) {
            Map.Entry<Integer, TrackableObject> entry = regIt.next();
            if (!currentObjectIds.contains(entry.getKey())) {
                entry.getValue().unregisterConsumer(consumerId);
                regIt.remove();
            }
        }

        DeltaPacket packet = finishPacket(gameView, objectDeltas, newObjects);
        if (SHADOW_SNAPSHOT) {
            shadowCompare(gameView, packet, walkNanos, walkAlloc);
        }
        return packet;
    }

    /**
     * Sequence, checksum and wrap what either path produced.
     *
     * <p>The checksum is deliberately still computed from the live view rather than from
     * the snapshot: it is the independent oracle for whichever path built the packet, and
     * deriving it from the snapshot would make it agree with the diff by construction.
     */
    private DeltaPacket finishPacket(GameView gameView,
                                     Map<Integer, Map<TrackableProperty, Object>> objectDeltas,
                                     Map<Integer, Map<TrackableProperty, Object>> newObjects) {
        // Accumulate changed properties for delta-biased sampling
        for (Map<TrackableProperty, Object> delta : objectDeltas.values()) {
            recentDeltaProperties.addAll(delta.keySet());
        }

        if (!newObjects.isEmpty()) {
            netLog.info("[DeltaSync] New objects: {}, Deltas: {}", newObjects.size(), objectDeltas.size());
        }

        sequenceNumber++;

        int checksum = 0;
        int[] checksumPropertyOrdinals = null;
        packetsSinceLastChecksum++;
        if (packetsSinceLastChecksum >= checksumInterval) {
            checksumPropertyOrdinals = selectChecksumProperties();
            List<String> detail = new ArrayList<>();
            checksum = NetworkChecksumUtil.computeSampledChecksum(gameView, checksumPropertyOrdinals, detail);
            packetsSinceLastChecksum = 0;
            recentDeltaProperties.clear();
            cleanChecksumStreak++;

            // Restore default interval after sustained clean streak
            if (checksumInterval < CHECKSUM_INTERVAL && cleanChecksumStreak >= CLEAN_STREAK_TO_RESTORE) {
                netLog.info("[DeltaSync] {} clean checksums, restoring interval to {}",
                        cleanChecksumStreak, CHECKSUM_INTERVAL);
                checksumInterval = CHECKSUM_INTERVAL;
            }

            logSampledChecksumDetails(gameView, checksum, sequenceNumber, checksumPropertyOrdinals);
            netLog.info("[Gate] idRef={} inline={} disagreed={}",
                    gateIdRef.get(), gateInline.get(), gateDisagreed.get());

            // Store breakdown for logging if the client reports a mismatch
            int turn = gameView.getTurn();
            int phaseOrdinal = gameView.getPhase() != null ? gameView.getPhase().ordinal() : -1;
            lastChecksumBreakdown = NetworkChecksumUtil.computeChecksumBreakdown(turn, phaseOrdinal, gameView);
            lastChecksumDetail = detail;
        }

        return new DeltaPacket(sequenceNumber, objectDeltas, newObjects, checksum, checksumPropertyOrdinals);
    }

    /**
     * Build a snapshot alongside the walk and report where the two disagree.
     *
     * <p>Compares end state rather than packet shape: whether a property arrives under
     * newObjects or objectDeltas depends on registration history for the walk and on
     * baseline presence for the diff, so the two legitimately differ there. What must
     * agree is the set of (object, property, value) a client ends up with.
     *
     * <p>Walk-only entries are expected — a dirty bit survives a value changing and
     * changing back, which a diff correctly skips. Snapshot-only entries are the
     * interesting direction: they are changes the walk did not report.
     *
     * <p>The baseline advances every pass here rather than on delivery, because nothing
     * built from the snapshot is sent while it is only shadowing the walk.
     */
    private void shadowCompare(GameView gameView, DeltaPacket packet, long walkNanos, long walkAlloc) {
        long buildStart = System.nanoTime();
        long buildAllocStart = allocatedBytes();
        ViewSnapshot current = ViewSnapshot.build(gameView);
        long buildNanos = System.nanoTime() - buildStart;
        long buildAlloc = allocatedBytes() - buildAllocStart;
        long diffStart = System.nanoTime();
        long diffAllocStart = allocatedBytes();
        ViewSnapshot.Diff diff = ViewSnapshot.diff(baseline, current);
        long diffNanos = System.nanoTime() - diffStart;
        long diffAlloc = allocatedBytes() - diffAllocStart;
        baseline = current;

        Map<Integer, Map<TrackableProperty, Object>> fromWalk =
                mergeForComparison(packet.getNewObjects(), packet.getObjectDeltas());
        Map<Integer, Map<TrackableProperty, Object>> fromSnapshot =
                mergeForComparison(diff.newObjects(), diff.objectDeltas());

        int mismatched = 0;
        int snapshotOnly = 0;
        int walkOnly = 0;

        for (Map.Entry<Integer, Map<TrackableProperty, Object>> entry : fromWalk.entrySet()) {
            Map<TrackableProperty, Object> other = fromSnapshot.get(entry.getKey());
            for (Map.Entry<TrackableProperty, Object> prop : entry.getValue().entrySet()) {
                if (other == null || !other.containsKey(prop.getKey())) {
                    walkOnly++;
                } else if (!java.util.Objects.equals(other.get(prop.getKey()), prop.getValue())) {
                    mismatched++;
                    netLog.warn("[Shadow] seq={} key={} {}: walk={} snapshot={}",
                            packet.getSequenceNumber(), String.format("0x%08X", entry.getKey()),
                            prop.getKey(), prop.getValue(), other.get(prop.getKey()));
                }
            }
        }
        for (Map.Entry<Integer, Map<TrackableProperty, Object>> entry : fromSnapshot.entrySet()) {
            Map<TrackableProperty, Object> other = fromWalk.get(entry.getKey());
            // A key the walk sends as a new object carries full state, and the client
            // clears before applying — so a property the walk omits there is already
            // cleared, which is what the diff says explicitly with a null.
            boolean walkSendsFullState = packet.getNewObjects().containsKey(entry.getKey());
            for (TrackableProperty prop : entry.getValue().keySet()) {
                if (other != null && other.containsKey(prop)) {
                    continue;
                }
                if (walkSendsFullState && entry.getValue().get(prop) == null) {
                    continue;
                }
                snapshotOnly++;
                netLog.warn("[Shadow] seq={} key={} {}: reported by snapshot only, value={}",
                        packet.getSequenceNumber(), String.format("0x%08X", entry.getKey()),
                        prop, entry.getValue().get(prop));
            }
        }

        netLog.info("[Shadow] seq={} objects={} walkKeys={} snapshotKeys={} "
                        + "mismatched={} snapshotOnly={} walkOnly={} evicted={} "
                        + "walkUs={} buildUs={} diffUs={} walkBytes={} buildBytes={} diffBytes={}",
                packet.getSequenceNumber(), current.size(), fromWalk.size(), fromSnapshot.size(),
                mismatched, snapshotOnly, walkOnly, diff.evicted().size(),
                walkNanos / 1000, buildNanos / 1000, diffNanos / 1000,
                walkAlloc, buildAlloc, diffAlloc);
    }

    /** Flatten a packet's two maps into one, with values canonicalised for comparison. */
    private static Map<Integer, Map<TrackableProperty, Object>> mergeForComparison(
            Map<Integer, Map<TrackableProperty, Object>> newObjects,
            Map<Integer, Map<TrackableProperty, Object>> objectDeltas) {
        Map<Integer, Map<TrackableProperty, Object>> merged = new HashMap<>();
        for (Map<Integer, Map<TrackableProperty, Object>> source : List.of(newObjects, objectDeltas)) {
            for (Map.Entry<Integer, Map<TrackableProperty, Object>> entry : source.entrySet()) {
                Map<TrackableProperty, Object> into =
                        merged.computeIfAbsent(entry.getKey(), k -> new EnumMap<>(TrackableProperty.class));
                for (Map.Entry<TrackableProperty, Object> prop : entry.getValue().entrySet()) {
                    into.put(prop.getKey(), ViewSnapshot.canonical(prop.getValue()));
                }
            }
        }
        return merged;
    }

    /**
     * Recursively walk the object graph starting from a TrackableObject, collecting deltas.
     * Discovers children by inspecting property values for TrackableObject/TrackableCollection
     * references. CombatView is serialized inline by toNetworkValue().
     */
    private void walkAndCollect(TrackableObject obj,
                                Map<Integer, Map<TrackableProperty, Object>> objectDeltas,
                                Map<Integer, Map<TrackableProperty, Object>> newObjects,
                                Set<Integer> currentObjectIds) {
        int type = DeltaPacket.typeTagFor(obj);
        if (type < 0) return;
        int deltaKey = DeltaPacket.makeDeltaKey(obj);

        // Block stale cross-references before touching currentObjectIds.
        // Zone instances (seeded by preScanZoneCollections) are authoritative —
        // any other instance at the same key is stale and must not be processed
        // or have its children walked (stale CardStateViews would bypass the
        // CardView-only auth check and overwrite correct deltas).
        TrackableObject auth = authoritativeInstances.get(deltaKey);
        if (auth != null && auth != obj) return;

        // Dedup: skip if same instance already processed this pass
        if (!currentObjectIds.add(deltaKey)) {
            if (registeredByKey.get(deltaKey) == obj) return;
            // Different instance at same key — replacement (zone change)
        }

        collectObjectDelta(obj, objectDeltas, newObjects);

        boolean parentIsGameEntityView = obj instanceof GameEntityView;
        for (Map.Entry<TrackableProperty, Object> entry : ((Map<TrackableProperty, Object>) obj.getProps()).entrySet()) {
            Object value = entry.getValue();
            if (value instanceof TrackableObject to) {
                // Skip GameEntityView→GameEntityView scalar cross-references
                // (CardView→CardView, PlayerView→CardView are stale after zone
                // changes). Non-GameEntityView parents (StackItemView.SourceCard)
                // hold primary containment refs — walked and auth-checked above.
                if (parentIsGameEntityView && to instanceof GameEntityView) {
                    continue;
                }
                walkAndCollect(to, objectDeltas, newObjects, currentObjectIds);
            } else if (value instanceof TrackableCollection<?> tc) {
                for (TrackableObject to : tc) {
                    walkAndCollect(to, objectDeltas, newObjects, currentObjectIds);
                }
            }
        }
    }

    /**
     * Process a single object's delta. Stale cross-references are already
     * filtered by the authoritative check in walkAndCollect.
     */
    private void collectObjectDelta(TrackableObject obj,
                                    Map<Integer, Map<TrackableProperty, Object>> objectDeltas,
                                    Map<Integer, Map<TrackableProperty, Object>> newObjects) {
        int deltaKey = DeltaPacket.makeDeltaKey(obj);
        TrackableObject old = registeredByKey.get(deltaKey);

        if (old == obj) {
            // Existing object — dirty props only
            EnumSet<TrackableProperty> dirtyProps = obj.getAndClearDirtyProps(consumerId);
            Map<TrackableProperty, Object> delta = buildPropertyMap(obj, dirtyProps);
            if (!delta.isEmpty()) {
                objectDeltas.put(deltaKey, delta);
                netLog.trace("[DeltaSync] Delta: key={} id={}, props={}",
                        String.format("0x%08X", deltaKey), obj.getId(), delta.keySet());
            }
            return;
        }

        // New or replacement — send full state via newObjects so the client
        // clears stale properties before applying
        if (old != null) {
            old.unregisterConsumer(consumerId);
            objectDeltas.remove(deltaKey);
        }
        obj.registerConsumer(consumerId);
        obj.getAndClearDirtyProps(consumerId);
        registeredByKey.put(deltaKey, obj);
        Map<TrackableProperty, Object> allProps = buildPropertyMap(obj, null);
        // A replacement is sent even when it carries nothing: the client clears the key
        // before applying, so an empty map is how it learns the old instance's properties
        // are gone. Suppressing it leaves the client rendering state the server dropped.
        if (!allProps.isEmpty() || old != null) {
            newObjects.put(deltaKey, allProps);
            netLog.trace("[DeltaSync] {}: key={} id={}, {} props",
                    old != null ? "Replaced instance" : "New object",
                    String.format("0x%08X", deltaKey), obj.getId(), allProps.size());
        }
    }

    /**
     * Pre-scan zone collections across all players to seed authoritativeInstances.
     * Provides cross-player coverage for stale Commander references.
     */
    private void preScanZoneCollections(GameView gameView) {
        if (gameView == null || gameView.getPlayers() == null) return;
        for (PlayerView player : gameView.getPlayers()) {
            for (TrackableProperty zoneProp : ZONE_COLLECTIONS) {
                if (((Map<TrackableProperty, Object>) player.getProps()).get(zoneProp) instanceof TrackableCollection<?> tc) {
                    for (Object item : tc) {
                        if (item instanceof CardView cv) {
                            authoritativeInstances.putIfAbsent(DeltaPacket.makeDeltaKey(cv), cv);
                        }
                    }
                }
            }
        }
    }

    /**
     * Build a property map for a subset of dirty properties.
     */
    private Map<TrackableProperty, Object> buildPropertyMap(TrackableObject obj, Set<TrackableProperty> dirtyProps) {
        if (dirtyProps != null && dirtyProps.isEmpty()) {
            // Most visited objects change nothing on a given pass, and both maps below
            // are sized to the whole property enum regardless of how little is in them.
            return Collections.emptyMap();
        }
        Map<TrackableProperty, Object> props = obj.getProps();
        // Copy before iterating — the engine may write props concurrently
        Map<TrackableProperty, Object> snapshot = new EnumMap<>(props);
        if (dirtyProps == null) {
            dirtyProps = snapshot.keySet();
        }
        Map<TrackableProperty, Object> delta = new EnumMap<>(TrackableProperty.class);
        for (TrackableProperty prop : dirtyProps) {
            delta.put(prop, toNetworkValue(prop, snapshot.get(prop)));
        }
        return delta;
    }

    /**
     * Convert a property value to a network-safe form.
     * Object references become Integer IDs. Everything else passes through
     * as-is — Java serialization handles it natively.
     */
    @SuppressWarnings("unchecked")
    static Object toNetworkValue(TrackableProperty prop, Object value) {
        if (value == null) return null;
        TrackableType<?> type = prop.getType();

        // Object references → Integer ID
        if (type == TrackableTypes.CardViewType || type == TrackableTypes.PlayerViewType)
            return ((TrackableObject) value).getId();

        // Polymorphic reference → int[]{typeMarker, id}
        if (type == TrackableTypes.GameEntityViewType) {
            GameEntityView entity = (GameEntityView) value;
            return new DeltaPacket.EntityRef(
                    entity instanceof CardView ? DeltaPacket.EntityRef.CARD : DeltaPacket.EntityRef.PLAYER,
                    entity.getId());
        }

        // Collections of objects → List<Integer> of IDs
        if (type == TrackableTypes.CardViewCollectionType || type == TrackableTypes.PlayerViewCollectionType) {
            TrackableCollection<?> coll = (TrackableCollection<?>) value;
            List<Integer> ids = new ArrayList<>(coll.size());
            for (TrackableObject obj : coll) ids.add(obj == null ? -1 : obj.getId());
            return ids;
        }

        // CardStateView slot reference → ordinal of CardStateName
        if (type == TrackableTypes.CardStateViewType) {
            CardStateView csv = (CardStateView) value;
            return csv.getState().ordinal();
        }

        if (type == TrackableTypes.CombatViewType) {
            return combatViewToCombatData((CombatView) value);
        }

        if (type == TrackableTypes.StackItemViewType) {
            return ((TrackableObject) value).getId();
        }

        if (type == TrackableTypes.StackItemViewListType) {
            TrackableCollection<?> coll = (TrackableCollection<?>) value;
            List<Integer> ids = new ArrayList<>(coll.size());
            for (TrackableObject obj : coll) ids.add(obj == null ? -1 : obj.getId());
            return ids;
        }

        return value;
    }

    /**
     * Convert a CombatView into a serializable CombatData by iterating its band entries.
     */
    @SuppressWarnings("unchecked")
    private static CombatData combatViewToCombatData(CombatView combat) {
        Map<TrackableProperty, Object> props = combat.getProps();
        Map<FCollection<CardView>, GameEntityView> bandsWithDefenders =
                (Map<FCollection<CardView>, GameEntityView>) props.get(TrackableProperty.BandsWithDefenders);
        Map<FCollection<CardView>, FCollection<CardView>> bandsWithBlockers =
                (Map<FCollection<CardView>, FCollection<CardView>>) props.get(TrackableProperty.BandsWithBlockers);
        Map<FCollection<CardView>, FCollection<CardView>> bandsWithPlannedBlockers =
                (Map<FCollection<CardView>, FCollection<CardView>>) props.get(TrackableProperty.BandsWithPlannedBlockers);

        if (bandsWithDefenders == null || bandsWithDefenders.isEmpty()) {
            return new CombatData(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        List<List<Integer>> allAttackerIds = new ArrayList<>();
        List<DeltaPacket.EntityRef> allDefenderRefs = new ArrayList<>();
        List<List<Integer>> allBlockerIds = new ArrayList<>();
        List<List<Integer>> allPlannedBlockerIds = new ArrayList<>();

        for (Map.Entry<FCollection<CardView>, GameEntityView> entry : bandsWithDefenders.entrySet()) {
            FCollection<CardView> band = entry.getKey();
            GameEntityView defender = entry.getValue();

            // Attacker IDs for this band
            List<Integer> attackerIds = new ArrayList<>();
            for (CardView attacker : band) {
                attackerIds.add(attacker.getId());
            }
            allAttackerIds.add(attackerIds);

            allDefenderRefs.add(new DeltaPacket.EntityRef(
                    defender instanceof CardView ? DeltaPacket.EntityRef.CARD : DeltaPacket.EntityRef.PLAYER,
                    defender.getId()));

            // Blockers for this band
            FCollection<CardView> blockers = bandsWithBlockers != null ? bandsWithBlockers.get(band) : null;
            if (blockers != null && !blockers.isEmpty()) {
                List<Integer> blockerIds = new ArrayList<>();
                for (CardView blocker : blockers) {
                    blockerIds.add(blocker.getId());
                }
                allBlockerIds.add(blockerIds);
            } else {
                allBlockerIds.add(null);
            }

            // Planned blockers for this band
            FCollection<CardView> plannedBlockers = bandsWithPlannedBlockers != null ? bandsWithPlannedBlockers.get(band) : null;
            if (plannedBlockers != null && !plannedBlockers.isEmpty()) {
                List<Integer> plannedIds = new ArrayList<>();
                for (CardView pb : plannedBlockers) {
                    plannedIds.add(pb.getId());
                }
                allPlannedBlockerIds.add(plannedIds);
            } else {
                allPlannedBlockerIds.add(null);
            }
        }

        return new CombatData(allAttackerIds, allDefenderRefs, allBlockerIds, allPlannedBlockerIds);
    }

    /**
     * Register consumers on objects not yet tracked, without clearing dirty bits.
     * Used when the view graph has been populated after the initial sendFullState
     * (which sees an empty view). Objects already registered by collectDeltas'
     * new-object path are skipped — their consumers and dirty bits are preserved.
     */
    public void registerNewObjects(GameView gameView) {
        if (gameView == null) {
            return;
        }
        int before = registeredByKey.size();
        walkAndRegister(gameView, new HashSet<>());
        int added = registeredByKey.size() - before;
        if (added > 0) {
            netLog.info("[DeltaSync] Registered {} new objects (total {})", added, registeredByKey.size());
        }
    }

    private void walkAndRegister(TrackableObject obj, Set<Integer> visited) {
        int type = DeltaPacket.typeTagFor(obj);
        if (type < 0) return;
        int deltaKey = DeltaPacket.makeDeltaKey(obj);
        if (!visited.add(deltaKey)) return;

        // Only register consumer if not already tracked — don't add to
        // registeredByKey so collectDeltas' new-object path still fires and
        // sends the full property map to the client.
        if (!registeredByKey.containsKey(deltaKey)) {
            obj.registerConsumer(consumerId);
        }

        // Same skip guards as walkAndCollect. No auth check needed here:
        // walkAndRegister only registers consumers (no data sent), and the
        // first collectDeltas corrects any stale registrations.
        boolean parentIsGameEntityView = obj instanceof GameEntityView;
        for (Object value : ((Map<TrackableProperty, Object>) obj.getProps()).values()) {
            if (value instanceof TrackableObject to) {
                if (parentIsGameEntityView && to instanceof GameEntityView) {
                    continue;
                }
                walkAndRegister(to, visited);
            } else if (value instanceof TrackableCollection<?> tc) {
                for (TrackableObject to : tc) {
                    walkAndRegister(to, visited);
                }
            }
        }
    }

    /**
     * Select properties for sampled checksum. Biases toward recently-changed
     * properties (up to half the sample), fills rest randomly from eligible pool.
     */
    private int[] selectChecksumProperties() {
        Set<TrackableProperty> eligible = NetworkChecksumUtil.getEligibleProperties();
        List<TrackableProperty> selected = new ArrayList<>(SAMPLE_SIZE);

        int biasTarget = SAMPLE_SIZE / 2;
        List<TrackableProperty> biasedCandidates = new ArrayList<>();
        for (TrackableProperty prop : recentDeltaProperties) {
            if (eligible.contains(prop)) {
                biasedCandidates.add(prop);
            }
        }
        Collections.shuffle(biasedCandidates);
        int biasCount = Math.min(biasTarget, biasedCandidates.size());
        for (int i = 0; i < biasCount; i++) {
            selected.add(biasedCandidates.get(i));
        }

        // Fill remaining slots randomly from rest of eligible pool
        Set<TrackableProperty> selectedSet = EnumSet.noneOf(TrackableProperty.class);
        selectedSet.addAll(selected);
        List<TrackableProperty> remaining = new ArrayList<>();
        for (TrackableProperty prop : eligible) {
            if (!selectedSet.contains(prop)) {
                remaining.add(prop);
            }
        }
        Collections.shuffle(remaining);
        int fillCount = Math.min(SAMPLE_SIZE - selected.size(), remaining.size());
        for (int i = 0; i < fillCount; i++) {
            selected.add(remaining.get(i));
        }

        // Convert to sorted ordinals for determinism
        int[] ordinals = new int[selected.size()];
        for (int i = 0; i < selected.size(); i++) {
            ordinals[i] = selected.get(i).ordinal();
        }
        Arrays.sort(ordinals);
        return ordinals;
    }

    private void logSampledChecksumDetails(GameView gameView, int checksum, long seq, int[] sampledOrdinals) {
        int turn = gameView.getTurn();
        int phaseOrdinal = gameView.getPhase() != null ? gameView.getPhase().ordinal() : -1;
        String phaseName = phaseOrdinal >= 0 ?
                forge.game.phase.PhaseType.values()[phaseOrdinal].name() : "null";
        netLog.info("[DeltaSync] Sampled checksum for seq={}: hash={}, props={}", seq, checksum,
                NetworkChecksumUtil.sampledPropertyNames(sampledOrdinals));
        netLog.info("[DeltaSync]   Turn: {} (snapshot), Phase: {} (snapshot, current={})",
                turn, phaseName,
                gameView.getPhase() != null ? gameView.getPhase().name() : "null");
        for (PlayerView player : NetworkChecksumUtil.getSortedPlayers(gameView)) {
            netLog.info("[DeltaSync]   Player {} ({}): Life={}, Hand={}, GY={}, BF={}",
                    player.getId(), player.getName(), player.getLife(),
                    player.getZoneSize(ZoneType.Hand), player.getZoneSize(ZoneType.Graveyard), player.getZoneSize(ZoneType.Battlefield));
        }
    }

    /**
     * Called when a resync is requested due to checksum mismatch.
     * Halves the checksum interval (more frequent checks) and resets clean streak.
     */
    public void onResyncRequested() {
        cleanChecksumStreak = 0;
        int oldInterval = checksumInterval;
        checksumInterval = Math.max(MIN_CHECKSUM_INTERVAL, checksumInterval / 2);
        if (checksumInterval != oldInterval) {
            netLog.info("[DeltaSync] Resync detected, checksum interval reduced: {} -> {}",
                    oldInterval, checksumInterval);
        }
        if (lastChecksumBreakdown != null) {
            netLog.error("[DeltaSync] Server breakdown: {}", lastChecksumBreakdown);
        }
        if (lastChecksumDetail != null) {
            netLog.error("[DeltaSync] Server checksum detail: {}", lastChecksumDetail);
        }
        // Clear so a later resync only logs if a fresh checksum has been
        // computed since — otherwise we'd log a breakdown that postdates the
        // mismatch the client is reporting.
        lastChecksumBreakdown = null;
        lastChecksumDetail = null;
    }

    /**
     * Reset all tracking state for reconnection.
     * Unregisters this consumer from all tracked objects.
     * After reset, the next sync will be treated as a fresh initial sync.
     */
    public void reset() {
        // Unregister consumer from all tracked objects
        for (TrackableObject obj : registeredByKey.values()) {
            obj.unregisterConsumer(consumerId);
        }
        registeredByKey.clear();
        baseline = ViewSnapshot.empty();
        sequenceNumber = 0;
        packetsSinceLastChecksum = 0;
        recentDeltaProperties.clear();
        checksumInterval = CHECKSUM_INTERVAL;
        cleanChecksumStreak = 0;
        lastChecksumBreakdown = null;
        lastChecksumDetail = null;
    }

    public long getCurrentSequence() {
        return sequenceNumber;
    }

}
