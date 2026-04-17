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
import forge.gamemodes.net.event.EventPhaseChangedEvent;
import forge.gamemodes.net.event.ReceiveEventPoolEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.interfaces.ILobbyListener;
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
 * <p>All mutable state is guarded by {@code synchronized(this)}.
 */
public final class BoosterDraftHost implements IHasForgeLog {

    private final BoosterDraft draft;
    private final NetworkEvent event;
    private final List<EventParticipant> participants;
    private int currentPackNumber;  // 1-based round number — used to decide pass direction
    private int initialPackSize;    // pack size at start of current round, for pick-number display
    private boolean finished;

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
    public synchronized void start() {
        netLog.info("Draft started — {} participants, timer={}s, product={}",
                participants.size(), event.getPickTimerSeconds(), event.getProductDescription());
        event.setPhase(EventPhase.DRAFTING);
        FServerManager.getInstance().broadcast(
                new EventPhaseChangedEvent(EventPhase.DRAFTING));
        captureInitialPackSize();
        distributeActive();
    }

    /** Whether the draft has completed all rounds. */
    public synchronized boolean isFinished() {
        return finished;
    }

    /**
     * Handle an incoming pick from a human client.
     *
     * @param seatIndex the seat that made the pick
     * @param card      the chosen card
     */
    public synchronized void handlePick(int seatIndex, PaperCard card) {
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

        // Remove the card from the pack (still at head of queue) and add to pool
        player.draftCard(card, DeckSection.Sideboard);
        cancelSeatTimer(seatIndex);
        inFlight[seatIndex] = false;

        // Dequeue the pack from this seat and pass to the next in direction
        DraftPack passed = player.passPack();
        if (passed != null && !passed.isEmpty()) {
            passToNext(seatIndex, passed);
        }

        netLog.info("Seat {} picked from pack {}", seatIndex, currentPackNumber);
        broadcastSeatPicked(seatIndex);
        distributeActive();
    }

    /**
     * Core distribution loop: advance rounds, let AI pick, notify humans of
     * packs at the head of their queue.
     */
    private void distributeActive() {
        while (!finished) {
            // Round advancement — all queues drained means the round is over
            if (draft.isRoundOver()) {
                if (!draft.startRound()) {
                    finishDraft();
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
                ai.draftCard(choice, DeckSection.Sideboard);
                DraftPack passed = ai.passPack();
                if (passed != null && !passed.isEmpty()) passToNext(i, passed);
                broadcastSeatPicked(i);
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

                sendPackToHuman(i, head);
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

    private void sendPackToHuman(int seatIndex, DraftPack pack) {
        EventParticipant participant = findParticipant(seatIndex);
        if (participant == null || participant.isAI()) return;

        List<PaperCard> packCards = new ArrayList<>(pack);
        int packNum = currentPackNumber;
        int pickNum = Math.max(0, initialPackSize - pack.size());
        int timerSecs = event.getPickTimerSeconds();

        FServerManager.getInstance().sendToSlot(participant.getLobbySlotIndex(),
                new DraftPackArrivedEvent(seatIndex, packCards, packNum, pickNum, timerSecs),
                l -> l.draftPackArrived(seatIndex, packCards, packNum, pickNum, timerSecs));
    }

    private void broadcastSeatPicked(int seatIndex) {
        int[] queueDepths = computeQueueDepths();
        DraftSeatPickedEvent pickedEvent = new DraftSeatPickedEvent(
                seatIndex, 0, queueDepths);
        FServerManager server = FServerManager.getInstance();
        server.broadcast(pickedEvent);
        // broadcast() only dispatches MessageEvent to the local lobbyListener;
        // draft events must be forwarded explicitly to the host player.
        ILobbyListener listener = server.getLobbyListener();
        if (listener != null) {
            listener.draftSeatPicked(seatIndex, 0, queueDepths);
        }
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
     * Build pools and send them to each human participant.
     */
    private void finishDraft() {
        finished = true;
        cancelAllSeatTimers();
        timerExecutor.shutdown();
        draft.postDraftActions();
        netLog.info("Draft complete — distributing pools");

        List<LimitedPlayer> players = draft.getAllPlayers();
        String eventId = event.getEventId();

        // Send pool to each human
        for (int i = 0; i < players.size(); i++) {
            LimitedPlayer player = players.get(i);
            if (player instanceof LimitedPlayerAI) {
                continue;
            }

            EventParticipant participant = findParticipant(i);
            if (participant == null) {
                continue;
            }

            String poolName = participant.getName() + "-" + eventId.substring(0, Math.min(8, eventId.length()));
            Deck pool = new Deck(player.getDeck(), poolName);
            NetworkEvent.setEventTags(pool, event);
            FServerManager.getInstance().sendToSlot(participant.getLobbySlotIndex(),
                    new ReceiveEventPoolEvent(eventId, pool),
                    l -> l.receiveEventPool(eventId, pool));
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
    private synchronized void onSeatTimerExpired(int seatIndex) {
        if (finished || !inFlight[seatIndex]) return;

        List<LimitedPlayer> players = draft.getAllPlayers();
        LimitedPlayer player = players.get(seatIndex);
        DraftPack pack = player.nextChoice();
        if (pack == null || pack.isEmpty()) return;

        PaperCard autoPick = pack.get(0);
        netLog.info("Pick timer expired for seat {} — auto-picking {}", seatIndex, autoPick.getName());
        player.draftCard(autoPick, DeckSection.Sideboard);
        inFlight[seatIndex] = false;

        DraftPack passed = player.passPack();
        if (passed != null && !passed.isEmpty()) {
            passToNext(seatIndex, passed);
        }

        broadcastSeatPicked(seatIndex);
        notifyAutoPick(seatIndex, autoPick);
        distributeActive();
    }

    private void notifyAutoPick(int seatIndex, PaperCard card) {
        EventParticipant participant = findParticipant(seatIndex);
        if (participant == null || participant.isAI()) return;
        FServerManager.getInstance().sendToSlot(participant.getLobbySlotIndex(),
                new DraftAutoPickedEvent(seatIndex, card, 0),
                l -> l.draftAutoPicked(seatIndex, card, 0));
    }

    private EventParticipant findParticipant(int seatIndex) {
        for (EventParticipant p : participants) {
            if (p.getSeatIndex() == seatIndex) {
                return p;
            }
        }
        return null;
    }
}
