package forge.web;

import forge.deck.Deck;
import forge.gamemodes.net.EventParticipant;
import forge.gamemodes.net.NetworkEventView;
import forge.gamemodes.net.event.DraftPickEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gui.interfaces.IDraftEventHandler;
import forge.item.PaperCard;
import forge.web.ToBrowser.DraftCard;
import forge.web.ToBrowser.DraftSeat;
import forge.web.ToBrowser.DraftState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * One seat's view of a draft the host's lobby runs, built from the draft host's events as desktop's drafting screen
 * builds it. Events arrive on the network thread and are handled one at a time on the session's own executor, so the
 * browser is sent whole states in the order the draft made them. The browser's seat is drawn first, as offline.
 */
final class OnlineDraft implements IDraftEventHandler {
    private final Executor serial;
    private final Supplier<NetworkEventView> event;
    private final IntSupplier lobbySeat;
    private final IntPredicate held;
    private final Consumer<NetEvent> send;
    private final Consumer<DraftState> publish;
    private final BiConsumer<String, Deck> onPool;
    private final DraftView view = new DraftView();
    private final List<DraftCard> picks = new ArrayList<>();
    private List<PaperCard> pack = List.of();
    private int mySeat = -1;
    private int packNumber;
    private int pickNumber;
    private int packSize;
    private int clockSeconds;
    private long clockEnd;
    private int[] depths;
    /** The card this seat sent as its pick, which counts once the draft host says the seat picked. */
    private PaperCard pending;
    private volatile boolean pooled;

    /**
     * lobbySeat finds this seat in the pod before its first pack says it; held says whether a seat's player has gone;
     * send reaches the draft host; onPool is handed the pool the event ends with.
     */
    OnlineDraft(final Executor serial, final Supplier<NetworkEventView> event, final IntSupplier lobbySeat, final IntPredicate held,
            final Consumer<NetEvent> send, final Consumer<DraftState> publish, final BiConsumer<String, Deck> onPool) {
        this.serial = serial;
        this.event = event;
        this.lobbySeat = lobbySeat;
        this.held = held;
        this.send = send;
        this.publish = publish;
        this.onPool = onPool;
    }

    /** The state last sent, for a browser that arrives again; null before the first pack. */
    DraftState latest() {
        return view.latest();
    }

    /** Whether the draft is on screen: a pack has come, and the pool that ends the event has not. */
    boolean drafting() {
        return view.latest() != null && !pooled;
    }

    @Override
    public void draftPackArrived(final int seatIndex, final List<PaperCard> cards, final int packNum, final int pickNum,
            final int timerSeconds) {
        serial.execute(() -> {
            mySeat = seatIndex;
            if (packNum != packNumber) {
                view.log("Pack " + packNum + " · passing " + (packNum % 2 == 1 ? "right" : "left"));
            }
            pack = List.copyOf(cards);
            packNumber = packNum;
            pickNumber = pickNum;
            packSize = cards.size() + pickNum;
            clockSeconds = Math.max(0, timerSeconds);
            clockEnd = clockSeconds == 0 ? 0 : System.currentTimeMillis() + clockSeconds * 1000L;
            send(true, List.of());
        });
    }

    @Override
    public void draftSeatPicked(final int seatIndex, final int[] seatQueueDepths) {
        serial.execute(() -> {
            final int seat = seat();
            final List<Integer> moved = DraftView.moved(depths, seatQueueDepths, seatIndex, direction());
            depths = seatQueueDepths.clone();
            final boolean mine = seatIndex == seat;
            if (mine) {
                if (pending != null) {
                    picks.add(DraftView.card(pending, packNumber, pickNumber + 1));
                    view.log("You picked " + pending.getName());
                    pending = null;
                }
                // The pack picked from has gone on; the next arrives as its own event
                pack = List.of();
                clockEnd = 0;
            } else {
                view.log(nameOf(seatIndex) + " picked · " + depthOf(seatIndex) + " waiting");
            }
            if (view.latest() != null) {
                send(mine, moved);
            }
        });
    }

    @Override
    public void draftAutoPicked(final int seatIndex, final PaperCard card, final int packNum, final int pickInPack) {
        serial.execute(() -> {
            // The timer took the pick, so a click that raced it is not counted as well
            pending = null;
            picks.add(DraftView.card(card, packNum, pickInPack));
            view.log("Out of time: you were given " + card.getName());
        });
    }

    @Override
    public void receiveEventPool(final String eventId, final Deck pool) {
        serial.execute(() -> {
            pooled = true;
            onPool.accept(eventId, pool);
        });
    }

    /** Picks the card at index of the pack shown in state step. A click on a pack that has moved on is ignored. */
    void pick(final int step, final int index) {
        serial.execute(() -> {
            final DraftState now = view.latest();
            if (pooled || now == null || now.step() != step || pending != null || index < 0 || index >= pack.size()) {
                return;
            }
            pending = pack.get(index);
            send.accept(new DraftPickEvent(mySeat, pending));
        });
    }

    /** Sends the state again with each seat's held flag read afresh, after a player went or came back. */
    void refresh() {
        serial.execute(() -> {
            if (view.latest() != null && !pooled) {
                send(false, List.of());
            }
        });
    }

    private int seat() {
        if (mySeat < 0) {
            mySeat = lobbySeat.getAsInt();
        }
        return mySeat;
    }

    /** Packs go to the next seat in odd packs and the previous seat in even ones, as the draft host passes them. */
    private int direction() {
        return packNumber % 2 == 0 && packNumber > 0 ? -1 : 1;
    }

    private List<EventParticipant> participants() {
        final NetworkEventView e = event.get();
        return e == null ? List.of() : e.getParticipants();
    }

    private String nameOf(final int seat) {
        final EventParticipant p = EventParticipant.findBySeat(participants(), seat);
        return p == null || p.getName() == null ? "Seat " + (seat + 1) : p.getName();
    }

    private int depthOf(final int seat) {
        return depths != null && seat >= 0 && seat < depths.length ? depths[seat] : 0;
    }

    private void send(final boolean newPack, final List<Integer> moved) {
        final NetworkEventView e = event.get();
        final int n = depths != null ? depths.length : participants().size();
        final int me = Math.max(0, seat());
        if (depths == null) {
            // Before any seat has picked, every seat holds the one pack it opened
            depths = new int[n];
            Arrays.fill(depths, 1);
        }
        final List<DraftSeat> seats = new ArrayList<>();
        for (int d = 0; d < n; d++) {
            final int s = (me + d) % n;
            final EventParticipant p = EventParticipant.findBySeat(participants(), s);
            seats.add(new DraftSeat(nameOf(s), p == null || p.isAI(), depths[s], held.test(s)));
        }
        final List<Integer> movedHere = moved.stream().map(s -> Math.floorMod(s - me, n)).toList();
        final int pick = pickNumber + 1;
        final List<DraftCard> cards = pack.stream().map(c -> DraftView.card(c, packNumber, pick)).toList();
        final int left = clockEnd == 0 ? 0 : (int) Math.max(0, clockEnd - System.currentTimeMillis());
        publish.accept(view.state(newPack, step -> new DraftState(step, e == null || e.getProductDescription() == null ? "" : e.getProductDescription(), packNumber,
                e == null ? 0 : e.getNumRounds(), pick, packSize, direction(), seats, cards, List.copyOf(picks), movedHere,
                clockSeconds, left, view.lines(), false)));
    }
}
