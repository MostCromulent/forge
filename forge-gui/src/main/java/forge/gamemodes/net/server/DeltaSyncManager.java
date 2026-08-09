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
import java.util.EnumSet;
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
     * Everything this client would need from scratch, or null before anything is published.
     *
     * <p>For measuring what a delta saves. The baseline has to be what a full state costs
     * under the method actually in use, and under the snapshot that is the diff against an
     * empty baseline rather than a setGameView. Taking it from the published snapshot also
     * keeps the measurement off the live graph, which is the whole point of the snapshot.
     */
    DeltaPacket fullStateForMeasurement() {
        final ViewSnapshot snapshot = published;
        if (snapshot.size() == 0) {
            return null;
        }
        final ViewSnapshot.Diff diff = ViewSnapshot.diff(ViewSnapshot.empty(), snapshot);
        return new DeltaPacket(sequenceNumber, diff.objectDeltas(), diff.newObjects(), 0, null);
    }

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

    // each DeltaSyncManager gets a unique ID

    /**
     * Whether this client already holds {@code obj}, which is what decides whether a
     * reference to it travels as an id or is serialized inline. Handed to the encoder
     * as the IdRef gate.
     *
     * <p>The walk answers this from consumer registration; the snapshot answers it from
     * the baseline, which is what was actually sent. Both answers are computed while the
     * snapshot is only being cross-checked, because a wrong one here fails silently: too permissive
     * ships an id the client cannot resolve, too conservative inlines a whole card into
     * every packet that mentions one.
     */
    boolean receiverKnows(TrackableObject obj) {
        return baseline.objects().containsKey(DeltaPacket.makeDeltaKey(obj));
    }

    private long sequenceNumber = 0;

    // Objects registered with this consumer (for cleanup on disconnect/reset)
    // Used to block stale cross-reference replacements

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
            CONCURRENT_ENTRIES.incrementAndGet();
            netLog.warn("[Collect] Concurrent entry: {} entered while {} was still inside. "
                            + "Per-consumer registration state and the view graph are both unguarded here.",
                    self.getName(), concurrent.getName());
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
    private static final AtomicInteger CONCURRENT_ENTRIES = new AtomicInteger();

    /**
     * How many times a second thread has been found inside collection, across every client
     * in this process. Counted rather than only logged so a test can hold it at zero — the
     * detector has fired during ordinary play before collection was serialised, and nothing
     * would notice it starting again.
     */
    public static int concurrentEntryCount() {
        return CONCURRENT_ENTRIES.get();
    }

    private DeltaPacket collectDeltasInternal(GameView gameView) {
        ViewSnapshot current = ViewSnapshot.current(gameView);
        ViewSnapshot.Diff diff = ViewSnapshot.diff(baseline, current);
        baseline = current;
        published = current;
        return finishPacket(gameView, diff.objectDeltas(), diff.newObjects());
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
            // Store breakdown for logging if the client reports a mismatch
            int turn = gameView.getTurn();
            int phaseOrdinal = gameView.getPhase() != null ? gameView.getPhase().ordinal() : -1;
            lastChecksumBreakdown = NetworkChecksumUtil.computeChecksumBreakdown(turn, phaseOrdinal, gameView);
            lastChecksumDetail = detail;
        }

        return new DeltaPacket(sequenceNumber, objectDeltas, newObjects, checksum, checksumPropertyOrdinals);
    }

    /**
     * Convert a property value to a network-safe form: object references become ids, and
     * everything else already is what should travel.
     *
     * <p>Nothing is copied here. A property whose value the engine goes on mutating stores a
     * private copy as it is set, so what is read here is already detached - and returning the
     * stored instance rather than a copy of it is what lets an unchanged property compare
     * equal by identity when two snapshots are diffed.
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
            return Collections.unmodifiableList(ids);
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
            return Collections.unmodifiableList(ids);
        }

        return value;
    }

    /**
     * Convert a CombatView into a serializable CombatData by iterating its band entries.
     */
    @SuppressWarnings("unchecked")
    private static CombatData combatViewToCombatData(CombatView combat) {
        // One reading for all three bands below, so they cannot come from different instants.
        Map<TrackableProperty, Object> props = combat.getPropsCopy();
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
