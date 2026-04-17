package forge.gamemodes.net.draft;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.net.EventParticipant;
import forge.gamemodes.net.EventPhase;
import forge.gamemodes.net.NetworkEvent;
import forge.gamemodes.limited.DraftPack;
import forge.gamemodes.limited.LimitedPlayer;
import forge.gamemodes.limited.LimitedPlayerAI;
import forge.gamemodes.net.event.DraftAutoPickedEvent;
import forge.gamemodes.net.event.DraftPackArrivedEvent;
import forge.gamemodes.net.event.DraftSeatPickedEvent;
import forge.gamemodes.net.event.ReceiveEventPoolEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.item.PaperCard;
import forge.util.IHasForgeLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Server-side adapter that wraps {@link BoosterDraft} for network play.
 *
 * <p>Async model: each seat has its own pack queue. When a seat picks, the
 * picked-from pack is passed to the next seat in the pass direction immediately,
 * regardless of what other seats are doing. A fast picker may bank up multiple
 * packs while waiting for slower seats. Each human pick has its own timer,
 * reset whenever a new pack reaches the head of the queue.
 *
 * <p>Mutable state is guarded by {@code synchronized(this)}, but all network
 * dispatch is deferred to a list and run outside the monitor — otherwise a slow
 * client's {@code channel.writeAndFlush().sync()} would block the entire pod.
 */
public final class BoosterDraftHost implements IHasForgeLog {

    private final BoosterDraft draft;
    private final NetworkEvent event;
    private final List<EventParticipant> participants;
    private int currentPackNumber;  // 1-based round number — used to decide pass direction
    private int initialPackSize;    // pack size at start of current round, for pick-number display
    private volatile boolean finished;

    /** Whether a human seat currently has a pack notification in flight (waiting for pick). */
    private final boolean[] inFlight;

    /** Per-seat pick timers. Started when a pack is sent, cancelled on pick. */
    private final Map<Integer, ScheduledFuture<?>> seatTimers = new HashMap<>();

    private final ScheduledExecutorService timerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DraftPickTimer");
        t.setDaemon(true);
        return t;
    });

    public BoosterDraftHost(BoosterDraft draft, NetworkEvent event) {
        this.draft = draft;
        this.event = event;
        this.participants = event.getParticipants();
        this.currentPackNumber = draft.getRound();
        this.finished = false;
        this.inFlight = new boolean[draft.getAllPlayers().size()];
    }

    /**
     * Start the draft: set phase and distribute initial packs.
     * Called once after the BoosterDraft has been initialized.
     */
    public void start() {
        List<Runnable> dispatches;
        synchronized (this) {
            netLog.info("Draft started — {} participants, timer={}s, product={}",
                    participants.size(), event.getPickTimerSeconds(), event.getProductDescription());
            event.setPhase(EventPhase.DRAFTING);
            captureInitialPackSize();
            dispatches = new ArrayList<>();
            advanceDraft(dispatches);
        }
        run(dispatches);
    }

    /** Whether the draft has completed all rounds. */
    public synchronized boolean isFinished() {
        return finished;
    }

    /**
     * Stop the draft and release timer resources. Safe to call multiple times.
     * Does not distribute pools — call before the draft has legitimately finished
     * (e.g. host cleared the event mid-draft, lobby shutting down).
     */
    public synchronized void shutdown() {
        finished = true;
        cancelAllSeatTimers();
        timerExecutor.shutdown();
    }

    /**
     * Handle an incoming pick from a human client.
     *
     * @param seatIndex the seat that made the pick
     * @param card      the chosen card
     */
    public void handlePick(int seatIndex, PaperCard card) {
        List<Runnable> dispatches;
        synchronized (this) {
            if (finished) return;
            List<LimitedPlayer> players = draft.getAllPlayers();
            if (seatIndex < 0 || seatIndex >= players.size()) {
                netLog.warn("Invalid seat index: {}", seatIndex);
                return;
            }
            LimitedPlayer player = players.get(seatIndex);
            DraftPack headPack = player.nextChoice();
            if (headPack == null || !headPack.contains(card)) {
                netLog.warn("Seat {} picked a card not in the current pack", seatIndex);
                return;
            }

            dispatches = new ArrayList<>();
            applyPickAndPass(player, seatIndex, card);
            cancelSeatTimer(seatIndex);
            inFlight[seatIndex] = false;

            // Pick number reported in the broadcast reflects how many cards this
            // seat has drafted so far (after this pick) — 1 for their first pick
            // ever, 16 for the first pick of round 2, etc.
            int seatPickNumber = seatPickCount(player);
            netLog.info("Seat {} picked from pack {}", seatIndex, currentPackNumber);
            addBroadcastSeatPicked(dispatches, seatIndex, seatPickNumber);
            advanceDraft(dispatches);
        }
        run(dispatches);
    }

    /**
     * Apply a pick and, if the card's effect passes the pack, dequeue it from
     * the picker's queue and route it to the next seat in direction. Conspiracy
     * cards such as Agent of Acquisitions cause {@code draftCard} to return
     * {@code false}, meaning the picker keeps the pack for another pick.
     */
    private void applyPickAndPass(LimitedPlayer player, int seatIndex, PaperCard card) {
        Boolean passPack = player.draftCard(card, DeckSection.Sideboard);
        if (!Boolean.FALSE.equals(passPack)) {
            DraftPack passed = player.passPack();
            if (passed != null && !passed.isEmpty()) {
                passToNext(seatIndex, passed);
            }
        }
    }

    /**
     * Core distribution loop: advance rounds, let AI pick, notify humans of
     * packs at the head of their queue. Collects network dispatches into
     * {@code dispatches} to be run outside the monitor.
     */
    private void advanceDraft(List<Runnable> dispatches) {
        while (!finished) {
            // Round advancement — all queues drained means the round is over
            if (draft.isRoundOver()) {
                if (!draft.startRound()) {
                    addFinishDraft(dispatches);
                    return;
                }
                currentPackNumber = draft.getRound();
                captureInitialPackSize();
            }

            // Let any one AI with a pack pick, then restart so we re-check state
            List<LimitedPlayer> players = draft.getAllPlayers();
            boolean aiProgressed = false;
            for (int i = 0; i < players.size(); i++) {
                LimitedPlayer p = players.get(i);
                if (!(p instanceof LimitedPlayerAI ai)) continue;
                DraftPack head = p.nextChoice();
                if (head == null || head.isEmpty()) continue;

                if (p.shouldSkipThisPick()) {
                    // Skip without picking — pass the pack along
                    DraftPack skipPass = p.passPack();
                    if (skipPass != null && !skipPass.isEmpty()) passToNext(i, skipPass);
                    aiProgressed = true;
                    break;
                }

                PaperCard choice = ai.chooseCard();
                if (choice == null) continue;
                applyPickAndPass(ai, i, choice);
                addBroadcastSeatPicked(dispatches, i, seatPickCount(ai));
                aiProgressed = true;
                break;
            }
            if (aiProgressed) continue;

            // No AI work left — notify any humans with a fresh pack
            for (int i = 0; i < players.size(); i++) {
                LimitedPlayer p = players.get(i);
                if (p instanceof LimitedPlayerAI) continue;
                DraftPack head = p.nextChoice();
                if (head == null || head.isEmpty()) continue;
                if (inFlight[i]) continue;

                addSendPackToHuman(dispatches, i, head);
                inFlight[i] = true;
                startSeatTimer(i);
            }
            return;
        }
    }

    /**
     * Pass a non-empty pack from {@code fromSeat} to the next seat in the current
     * pass direction (odd packs go right, even packs go left — MTG convention).
     */
    private void passToNext(int fromSeat, DraftPack pack) {
        int podSize = draft.getAllPlayers().size();
        int dir = (currentPackNumber % 2 == 1) ? 1 : -1;
        int nextSeat = ((fromSeat + dir) % podSize + podSize) % podSize;
        draft.getAllPlayers().get(nextSeat).receiveOpenedPack(pack);
    }

    private void captureInitialPackSize() {
        for (LimitedPlayer pl : draft.getAllPlayers()) {
            DraftPack pack = pl.nextChoice();
            if (pack != null && !pack.isEmpty()) {
                initialPackSize = pack.size();
                return;
            }
        }
    }

    /** Pick number (0-based) within the current pack, derived from cards remaining. */
    private int pickNumberFor(DraftPack pack) {
        return pack == null ? 0 : Math.max(0, initialPackSize - pack.size());
    }

    /** Total cards this seat has drafted so far (1-based — 1 means "just made pick 1"). */
    private static int seatPickCount(LimitedPlayer player) {
        return player.getDeck().get(DeckSection.Sideboard).countAll();
    }

    private void addSendPackToHuman(List<Runnable> dispatches, int seatIndex, DraftPack pack) {
        EventParticipant participant = findParticipant(seatIndex);
        if (participant == null || participant.isAI()) return;

        List<PaperCard> packCards = new ArrayList<>(pack);
        int packNum = currentPackNumber;
        int pickNum = pickNumberFor(pack);
        int timerSecs = event.getPickTimerSeconds();
        int slot = participant.getLobbySlotIndex();

        dispatches.add(() -> FServerManager.getInstance().sendToSlot(slot,
                new DraftPackArrivedEvent(seatIndex, packCards, packNum, pickNum, timerSecs),
                l -> l.draftPackArrived(seatIndex, packCards, packNum, pickNum, timerSecs)));
    }

    private void addBroadcastSeatPicked(List<Runnable> dispatches, int seatIndex, int pickNumber) {
        int[] queueDepths = computeQueueDepths();
        dispatches.add(() -> FServerManager.getInstance().broadcast(
                new DraftSeatPickedEvent(seatIndex, pickNumber, queueDepths)));
    }

    private int[] computeQueueDepths() {
        List<LimitedPlayer> players = draft.getAllPlayers();
        int[] depths = new int[players.size()];
        for (int i = 0; i < players.size(); i++) {
            depths[i] = players.get(i).getPackQueueSize();
        }
        return depths;
    }

    /**
     * Build pools and queue sends to each human participant. Called from inside
     * the monitor; the actual network dispatch happens after release.
     */
    private void addFinishDraft(List<Runnable> dispatches) {
        finished = true;
        cancelAllSeatTimers();
        timerExecutor.shutdown();
        draft.postDraftActions();
        netLog.info("Draft complete — distributing pools");

        List<LimitedPlayer> players = draft.getAllPlayers();
        String eventId = event.getEventId();

        for (int i = 0; i < players.size(); i++) {
            LimitedPlayer player = players.get(i);
            if (player instanceof LimitedPlayerAI) continue;

            EventParticipant participant = findParticipant(i);
            if (participant == null) continue;

            Deck pool = new Deck(player.getDeck(), NetworkEvent.poolNameFor(participant, event));
            NetworkEvent.setEventTags(pool, event);
            int slot = participant.getLobbySlotIndex();
            dispatches.add(() -> FServerManager.getInstance().sendToSlot(slot,
                    new ReceiveEventPoolEvent(eventId, pool),
                    l -> l.receiveEventPool(eventId, pool)));
        }
    }

    private void startSeatTimer(int seatIndex) {
        cancelSeatTimer(seatIndex);
        int seconds = event.getPickTimerSeconds();
        if (seconds <= 0) return;
        ScheduledFuture<?> f = timerExecutor.schedule(
                () -> onSeatTimerExpired(seatIndex), seconds, TimeUnit.SECONDS);
        seatTimers.put(seatIndex, f);
    }

    private void cancelSeatTimer(int seatIndex) {
        ScheduledFuture<?> f = seatTimers.remove(seatIndex);
        if (f != null) f.cancel(false);
    }

    private void cancelAllSeatTimers() {
        for (ScheduledFuture<?> f : seatTimers.values()) {
            if (f != null) f.cancel(false);
        }
        seatTimers.clear();
    }

    /** Auto-pick the first card for a single seat that timed out. */
    private void onSeatTimerExpired(int seatIndex) {
        List<Runnable> dispatches;
        synchronized (this) {
            if (finished || !inFlight[seatIndex]) return;

            List<LimitedPlayer> players = draft.getAllPlayers();
            LimitedPlayer player = players.get(seatIndex);
            DraftPack pack = player.nextChoice();
            if (pack == null || pack.isEmpty()) return;

            PaperCard autoPick = pack.get(0);
            netLog.info("Pick timer expired for seat {} — auto-picking {}", seatIndex, autoPick.getName());

            dispatches = new ArrayList<>();
            applyPickAndPass(player, seatIndex, autoPick);
            inFlight[seatIndex] = false;

            int seatPickNumber = seatPickCount(player);
            addBroadcastSeatPicked(dispatches, seatIndex, seatPickNumber);
            addNotifyAutoPick(dispatches, seatIndex, autoPick, seatPickNumber);
            advanceDraft(dispatches);
        }
        run(dispatches);
    }

    private void addNotifyAutoPick(List<Runnable> dispatches, int seatIndex, PaperCard card, int pickNumber) {
        EventParticipant participant = findParticipant(seatIndex);
        if (participant == null || participant.isAI()) return;
        int slot = participant.getLobbySlotIndex();
        dispatches.add(() -> FServerManager.getInstance().sendToSlot(slot,
                new DraftAutoPickedEvent(seatIndex, card, pickNumber),
                l -> l.draftAutoPicked(seatIndex, card, pickNumber)));
    }

    private EventParticipant findParticipant(int seatIndex) {
        for (EventParticipant p : participants) {
            if (p.getSeatIndex() == seatIndex) {
                return p;
            }
        }
        return null;
    }

    private static void run(List<Runnable> dispatches) {
        for (Runnable r : dispatches) r.run();
    }
}
