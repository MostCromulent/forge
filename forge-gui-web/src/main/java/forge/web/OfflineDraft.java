package forge.web;

import forge.card.CardRules;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.deck.DeckSection;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.DraftRankCache;
import forge.gamemodes.limited.LimitedPlayer;
import forge.gamemodes.limited.LimitedPlayerAI;
import forge.item.PaperCard;
import forge.web.ToBrowser.DraftCard;
import forge.web.ToBrowser.DraftSeat;
import forge.web.ToBrowser.DraftState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One offline booster draft against the computer, driven the way desktop's drafting screen drives it: the player's
 * pick, then the computer's, then the next pack. BoosterDraft has no locks, so everything touching it runs on this
 * draft's own thread, and the browser is sent a whole state after every step.
 */
final class OfflineDraft {
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Draft");
        t.setDaemon(true);
        return t;
    });
    private final String playerName;
    private final Consumer<DraftState> publish;
    private final List<DraftCard> picks = new ArrayList<>();
    private BoosterDraft draft;
    private String product;
    private List<PaperCard> pack = List.of();
    private int round;
    private int packSize;
    private volatile DraftState latest;

    /** make builds the draft, on the draft's thread, since importing a cube waits on a web site; fail hears why it could not. */
    OfflineDraft(final Supplier<BoosterDraft> make, final String playerName, final Consumer<DraftState> publish,
            final Consumer<String> fail) {
        this.playerName = playerName;
        this.publish = publish;
        thread.execute(() -> {
            try {
                draft = make.get();
                product = draft.getProductName() == null ? "Full card pool" : draft.getProductName();
                draft.initializeBoosters();
                advance(false);
            } catch (final RuntimeException e) {
                fail.accept(e.getMessage() == null ? "The draft could not be set up." : e.getMessage());
            }
        });
    }

    /** The state last sent, for a browser that arrives again; null before the first pack. */
    DraftState latest() {
        return latest;
    }

    /** Picks the card at index of the pack the browser was shown. A click on a pack that has moved on since is ignored. */
    void pick(final int packNumber, final int pickNumber, final int index) {
        thread.execute(() -> {
            final DraftState now = latest;
            if (now == null || now.done() || now.pack() != packNumber || now.pick() != pickNumber || index < 0 || index >= pack.size()) {
                return;
            }
            final LimitedPlayer me = draft.getHumanPlayer();
            // Desktop checks these two before taking the card, in this order
            if (me.shouldSkipThisPick()) {
                advance(false);
                return;
            }
            final PaperCard card = me.hasArchdemonCurse() ? me.pickFromArchdemonCurse(me.nextChoice()) : pack.get(index);
            final boolean passed = draft.setChoice(card, DeckSection.Sideboard);
            picks.add(card(card, round, pickNumber));
            advance(passed);
        });
    }

    /** The finished draft as desktop saves it: the player's picks and each computer's deck, all under name. */
    CompletableFuture<DeckGroup> save(final String name) {
        return CompletableFuture.supplyAsync(() -> {
            final Deck[] computer = draft.getComputerDecks();
            final LimitedPlayer[] players = draft.getOpposingPlayers();
            for (int i = 0; i < computer.length; i++) {
                computer[i].setDraftNotes(players[i].getSerializedDraftNotes());
            }
            final LimitedPlayer me = draft.getHumanPlayer();
            final Deck human = new Deck(me.getDeck(), name);
            human.setDraftNotes(me.getSerializedDraftNotes());
            final DeckGroup group = new DeckGroup(name);
            group.setHumanDeck(human);
            group.addAiDecks(computer);
            return group;
        }, thread);
    }

    void close() {
        thread.shutdownNow();
    }

    /** The next pack, as desktop's showPackToDraft finds it, or the end of the draft. */
    private void advance(final boolean passed) {
        if (draft.hasNextChoice()) {
            final CardPool next = draft.nextChoice();
            if (next != null && !next.isEmpty()) {
                pack = next.toFlatList();
                if (draft.getRound() != round) {
                    round = draft.getRound();
                    packSize = pack.size();
                }
                send(passed, false);
                return;
            }
        }
        draft.postDraftActions();
        pack = List.of();
        send(passed, true);
    }

    private void send(final boolean passed, final boolean done) {
        final List<DraftSeat> seats = new ArrayList<>();
        final List<LimitedPlayer> players = draft.getAllPlayers();
        for (int i = 0; i < players.size(); i++) {
            final LimitedPlayer p = players.get(i);
            final String name = i == 0 ? playerName : p.getName() == null || p.getName().isBlank() ? "Seat " + (i + 1) : p.getName();
            seats.add(new DraftSeat(name, p instanceof LimitedPlayerAI, p.getPackQueueSize(), false));
        }
        final int pick = packSize - pack.size() + 1;
        final List<DraftCard> cards = pack.stream().map(c -> card(c, round, pick)).toList();
        // Packs go to the next seat in odd rounds and the previous seat in even ones, as BoosterDraft.passPacks alternates
        latest = new DraftState(product, round, draft.getNumRounds(), pick, packSize, round % 2 == 1 ? 1 : -1, seats, cards,
                List.copyOf(picks), passed, done);
        publish.accept(latest);
    }

    private static DraftCard card(final PaperCard card, final int packNumber, final int pickNumber) {
        final CardRules rules = card.getRules();
        final Double ranking = DraftRankCache.getRanking(card.getName(), card.getEdition());
        return new DraftCard(card.getName(), card.getImageKey(false), JsonCodec.manaCost(rules.getManaCost()),
                rules.getManaCost().getCMC(), CardCatalog.letters(rules.getColor()), rules.getType().toString(), CardCatalog.pt(rules),
                card.getRarity().toString(), ranking == null ? null : (int) Math.round(ranking), packNumber, pickNumber);
    }
}
