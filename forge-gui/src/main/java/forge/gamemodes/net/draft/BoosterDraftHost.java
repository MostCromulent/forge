package forge.gamemodes.net.draft;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.DraftPack;
import forge.gamemodes.limited.LimitedPlayer;
import forge.gamemodes.limited.LimitedPlayerAI;
import forge.gamemodes.net.event.DraftPackArrivedEvent;
import forge.gamemodes.net.event.DraftSeatPickedEvent;
import forge.gamemodes.net.event.ReceiveEventPoolEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.RemoteClient;
import forge.item.PaperCard;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
public final class BoosterDraftHost {

    private final BoosterDraft draft;
    private final NetworkEvent event;
    private final List<EventParticipant> participants;
    private int currentPackNumber;  // 1-based round number
    private int currentPickNumber;  // 0-based pick within the round
    private boolean finished;

    /** Seats that still need to submit a pick this pass. */
    private final Set<Integer> pendingHumanPicks = new HashSet<>();

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
        event.setPhase(EventPhase.DRAFTING);
        FServerManager.getInstance().broadcast(
                new forge.gamemodes.net.event.EventPhaseChangedEvent(EventPhase.DRAFTING));
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
            System.err.println("[DraftHost] Invalid seat index: " + seatIndex);
            return;
        }
        if (!pendingHumanPicks.contains(seatIndex)) {
            System.err.println("[DraftHost] Seat " + seatIndex + " is not pending a pick");
            return;
        }

        LimitedPlayer player = players.get(seatIndex);
        DraftPack pack = player.nextChoice();
        if (pack == null || !pack.contains(card)) {
            System.err.println("[DraftHost] Card not found in seat " + seatIndex + "'s pack: " + card);
            return;
        }

        // Make the pick (card is removed from pack, added to deck)
        player.draftCard(card, DeckSection.Sideboard);
        pendingHumanPicks.remove(seatIndex);

        // Broadcast that this seat picked
        broadcastSeatPicked(seatIndex);

        // If all humans have picked, pass packs and advance
        if (pendingHumanPicks.isEmpty()) {
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
     */
    private void advanceDraft() {
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

        // If no human picks are needed (all seats are AI, or packs empty),
        // pass and continue
        if (pendingHumanPicks.isEmpty()) {
            // Check if we can pass packs and continue
            if (!draft.isRoundOver()) {
                draft.passPacks();
                currentPickNumber++;
                advanceDraft();
            } else if (draft.startRound()) {
                currentPackNumber = draft.getRound();
                currentPickNumber = 0;
                advanceDraft();
            } else {
                finishDraft();
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

        FServerManager server = FServerManager.getInstance();
        RemoteClient client = server.getClientByName(participant.getName());
        if (client != null) {
            client.send(new DraftPackArrivedEvent(seatIndex, packCards, packNum, pickNum, timerSecs));
        } else {
            // Host player — dispatch to local lobby listener
            forge.interfaces.ILobbyListener listener = server.getLobbyListener();
            if (listener != null) {
                listener.draftPackArrived(seatIndex, packCards, packNum, pickNum, timerSecs);
            }
        }
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
        forge.interfaces.ILobbyListener listener = server.getLobbyListener();
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
        draft.postDraftActions();

        List<LimitedPlayer> players = draft.getAllPlayers();
        FServerManager server = FServerManager.getInstance();

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
            pool.getTags().add("eventId:" + eventId);
            pool.getTags().add("eventFormat:" + event.getFormat().name());
            pool.getTags().add("eventProduct:" + event.getProductDescription());
            pool.getTags().add("eventDate:" + LocalDate.now().toString());

            RemoteClient client = server.getClientByName(participant.getName());
            if (client != null) {
                client.send(new ReceiveEventPoolEvent(eventId, pool));
            } else {
                // Host player — dispatch to local lobby listener
                forge.interfaces.ILobbyListener listener = server.getLobbyListener();
                if (listener != null) {
                    listener.receiveEventPool(eventId, pool);
                }
            }
        }
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
