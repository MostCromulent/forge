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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Server-side adapter that wraps {@link BoosterDraft} for network play.
 *
 * <p>The local {@code BoosterDraft} was designed for synchronous single-player use
 * ({@code nextChoice()}/{@code setChoice()} in a loop). This adapter drives the
 * draft asynchronously: AI seats pick immediately on the host, and human picks
 * arrive via {@link forge.gamemodes.net.event.DraftPickEvent} from Netty threads.
 *
 * <p>All mutable state is guarded by {@code synchronized(this)}.
 */
public final class BoosterDraftHost implements IHasForgeLog {

    private final BoosterDraft draft;
    private final NetworkEvent event;
    private final List<EventParticipant> participants;
    private int currentPackNumber;  // 1-based round number
    private int currentPickNumber;  // 0-based pick within the round
    private boolean finished;

    /** Seats that still need to submit a pick this pass. */
    private final Set<Integer> pendingHumanPicks = new HashSet<>();

    /** Server-side pick timer — one per round, auto-picks on expiry. */
    private final ScheduledExecutorService timerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DraftPickTimer");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pickTimerFuture;

    public BoosterDraftHost(BoosterDraft draft, NetworkEvent event) {
        this.draft = draft;
        this.event = event;
        this.participants = event.getParticipants();
        this.currentPackNumber = draft.getRound();
        this.currentPickNumber = 0;
        this.finished = false;
    }

    /**
     * Start the draft: set phase, let AI seats pick, send packs to humans.
     * Called once after the BoosterDraft has been initialized.
     */
    public synchronized void start() {
        netLog.info("Draft started — {} participants, timer={}s, product={}",
                participants.size(), event.getPickTimerSeconds(), event.getProductDescription());
        event.setPhase(EventPhase.DRAFTING);
        FServerManager.getInstance().broadcast(
                new EventPhaseChangedEvent(EventPhase.DRAFTING));
        advanceDraft();
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
        if (finished) {
            return;
        }
        List<LimitedPlayer> players = draft.getAllPlayers();
        if (seatIndex < 0 || seatIndex >= players.size()) {
            netLog.warn("Invalid seat index: {}", seatIndex);
            return;
        }
        if (!pendingHumanPicks.contains(seatIndex)) {
            netLog.warn("Seat {} is not pending a pick", seatIndex);
            return;
        }

        LimitedPlayer player = players.get(seatIndex);
        DraftPack pack = player.nextChoice();
        if (pack == null || !pack.contains(card)) {
            netLog.warn("Card not in seat {}'s pack", seatIndex);
            return;
        }

        // Make the pick (card is removed from pack, added to deck)
        player.draftCard(card, DeckSection.Sideboard);
        pendingHumanPicks.remove(seatIndex);
        netLog.info("Seat {} picked (pack {} pick {})", seatIndex, currentPackNumber, currentPickNumber);

        // Broadcast that this seat picked
        broadcastSeatPicked(seatIndex);

        // If all humans have picked, cancel timer, pass packs and advance
        if (pendingHumanPicks.isEmpty()) {
            cancelPickTimer();
            draft.passPacks();
            currentPickNumber++;

            if (draft.isRoundOver()) {
                if (!draft.startRound()) {
                    finishDraft();
                    return;
                }
                currentPackNumber = draft.getRound();
                currentPickNumber = 0;
            }

            advanceDraft();
        }
    }

    /**
     * Core draft advancement: AI seats pick immediately, then send packs to humans.
     * Uses an internal loop to avoid recursion for all-AI rounds or empty packs.
     */
    private void advanceDraft() {
        while (true) {
            List<LimitedPlayer> players = draft.getAllPlayers();

            // Let all AI seats pick
            for (int i = 0; i < players.size(); i++) {
                LimitedPlayer player = players.get(i);
                if (!(player instanceof LimitedPlayerAI ai)) {
                    continue;
                }
                if (player.shouldSkipThisPick()) {
                    continue;
                }
                DraftPack pack = player.nextChoice();
                if (pack == null || pack.isEmpty()) {
                    continue;
                }

                PaperCard aiPick = ai.chooseCard();
                if (aiPick != null) {
                    ai.draftCard(aiPick, DeckSection.Sideboard);
                    broadcastSeatPicked(i);
                }
            }

            // Determine which human seats need to pick and send them packs
            pendingHumanPicks.clear();
            for (int i = 0; i < players.size(); i++) {
                LimitedPlayer player = players.get(i);
                if (player instanceof LimitedPlayerAI) {
                    continue;
                }
                if (player.shouldSkipThisPick()) {
                    continue;
                }
                DraftPack pack = player.nextChoice();
                if (pack == null || pack.isEmpty()) {
                    continue;
                }

                pendingHumanPicks.add(i);
                sendPackToHuman(i, pack);
            }

            // Start pick timer and return — humans need to pick
            if (!pendingHumanPicks.isEmpty()) {
                startPickTimer();
                return;
            }

            // All AI or empty — advance and loop back
            if (!draft.isRoundOver()) {
                draft.passPacks();
                currentPickNumber++;
            } else if (draft.startRound()) {
                currentPackNumber = draft.getRound();
                currentPickNumber = 0;
            } else {
                finishDraft();
                return;
            }
        }
    }

    private void sendPackToHuman(int seatIndex, DraftPack pack) {
        EventParticipant participant = findParticipant(seatIndex);
        if (participant == null || participant.isAI()) {
            return;
        }

        List<PaperCard> packCards = new ArrayList<>(pack);
        int packNum = currentPackNumber;
        int pickNum = currentPickNumber;
        int timerSecs = event.getPickTimerSeconds();

        FServerManager.getInstance().sendToSlot(participant.getLobbySlotIndex(),
                new DraftPackArrivedEvent(seatIndex, packCards, packNum, pickNum, timerSecs),
                l -> l.draftPackArrived(seatIndex, packCards, packNum, pickNum, timerSecs));
    }

    private void broadcastSeatPicked(int seatIndex) {
        int[] queueDepths = computeQueueDepths();
        int pickNum = currentPickNumber;
        DraftSeatPickedEvent pickedEvent = new DraftSeatPickedEvent(
                seatIndex, pickNum, queueDepths);
        FServerManager server = FServerManager.getInstance();
        server.broadcast(pickedEvent);
        // broadcast() only dispatches MessageEvent to the local lobbyListener;
        // draft events must be forwarded explicitly to the host player.
        ILobbyListener listener = server.getLobbyListener();
        if (listener != null) {
            listener.draftSeatPicked(seatIndex, pickNum, queueDepths);
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
        cancelPickTimer();
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

    private void startPickTimer() {
        cancelPickTimer();
        int seconds = event.getPickTimerSeconds();
        if (seconds <= 0) return;
        pickTimerFuture = timerExecutor.schedule(this::onPickTimerExpired, seconds, TimeUnit.SECONDS);
    }

    private void cancelPickTimer() {
        if (pickTimerFuture != null) {
            pickTimerFuture.cancel(false);
            pickTimerFuture = null;
        }
    }

    /** Auto-pick the first card for all seats that haven't picked yet. */
    private synchronized void onPickTimerExpired() {
        if (finished || pendingHumanPicks.isEmpty()) return;

        netLog.info("Pick timer expired, auto-picking for seats: {}", pendingHumanPicks);
        List<LimitedPlayer> players = draft.getAllPlayers();

        for (int seatIndex : new ArrayList<>(pendingHumanPicks)) {
            LimitedPlayer player = players.get(seatIndex);
            DraftPack pack = player.nextChoice();
            if (pack == null || pack.isEmpty()) continue;

            PaperCard autoPick = pack.get(0);
            player.draftCard(autoPick, DeckSection.Sideboard);
            broadcastSeatPicked(seatIndex);
            notifyAutoPick(seatIndex, autoPick);
        }
        pendingHumanPicks.clear();

        // Advance the draft
        draft.passPacks();
        currentPickNumber++;

        if (draft.isRoundOver()) {
            if (!draft.startRound()) {
                finishDraft();
                return;
            }
            currentPackNumber = draft.getRound();
            currentPickNumber = 0;
        }

        advanceDraft();
    }

    private void notifyAutoPick(int seatIndex, PaperCard card) {
        EventParticipant participant = findParticipant(seatIndex);
        if (participant == null || participant.isAI()) return;
        FServerManager.getInstance().sendToSlot(participant.getLobbySlotIndex(),
                new DraftAutoPickedEvent(seatIndex, card, currentPickNumber),
                l -> l.draftAutoPicked(seatIndex, card, currentPickNumber));
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
