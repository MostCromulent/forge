package forge.web;

import forge.StaticData;
import forge.card.CardRules;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.util.ImageUtil;
import forge.util.storage.IStorage;
import forge.web.ToBrowser.EditorCard;
import forge.web.ToBrowser.EditorGroup;
import forge.web.ToBrowser.EditorLand;
import forge.web.ToBrowser.EditorPrinting;
import forge.web.ToBrowser.EditorState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One deck open in the browser's editor. Every change goes through here: it is checked against the engine's limits,
 * applied, and saved at once, to the host's deck folders or, for a guest, to the guest's browser. A deck that can't be
 * changed in place (a precon, somebody else's) is copied by its first change. Changes can be undone while it is open.
 */
final class DeckEditor {
    /** The name a new deck starts with, until its commander names it or the player does. */
    static final String NEW_DECK = "New deck";
    private static final int MOST_UNDO = 100;
    /** The basics the land row offers, with the colour each needs; Wastes needs none. */
    private static final Map<String, String> BASICS = new LinkedHashMap<>();

    static {
        BASICS.put("Plains", "W");
        BASICS.put("Island", "U");
        BASICS.put("Swamp", "B");
        BASICS.put("Mountain", "R");
        BASICS.put("Forest", "G");
        BASICS.put("Wastes", "C");
    }

    sealed interface Target permits Stored, Device {
    }

    /** A folder of the host's decks: a format's own, or a subfolder of it. */
    record Stored(IStorage<Deck> storage) implements Target {
    }

    /** A guest's browser, which keeps the deck under this id. */
    record Device(String id) implements Target {
    }

    /** Tells a guest's browser its deck changed: the deck as .dck text, or null when it was deleted. */
    interface DeviceSink {
        void deviceDeck(String id, String text, GameType format);
    }

    private record Snapshot(Deck deck, Check check) {
    }

    private final DeckStore.Storages storages;
    private final boolean guest;
    private final DeviceSink sink;
    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private Deck deck;
    private Target target;
    private Check check;
    /** Names the deck this can't change in place, until the first change copies it. */
    private String copyOf;
    /** The name this editor has saved the deck under, or null before its first save. */
    private String owned;
    /** Whether the deck exists where this editor saves it: opened from there, or saved there since. */
    private boolean saved;
    private String landed;

    /** saved says the deck was opened from where target saves it; a new deck, or one that is only being read, was not. */
    DeckEditor(final Deck deck, final boolean readOnly, final boolean saved, final Target target, final Check check,
            final DeckStore.Storages storages, final boolean guest, final DeviceSink sink) {
        // Edited as a copy, so a deck another screen holds (a catalogue entry, a seat) never changes under it
        this.deck = new Deck(deck, deck.getName());
        this.target = target;
        this.check = check;
        this.storages = storages;
        this.guest = guest;
        this.sink = sink;
        this.copyOf = readOnly ? deck.getName() : null;
        this.saved = saved && !readOnly;
        this.owned = this.saved && target instanceof Stored ? deck.getName() : null;
    }

    Deck deck() {
        return deck;
    }

    Check check() {
        return check;
    }

    Target target() {
        return target;
    }

    String copyOf() {
        return copyOf;
    }

    String landed() {
        return landed;
    }

    /** Whether the deck exists where it is saved, so Done has a deck of its own to put on a seat. */
    boolean saved() {
        return saved;
    }

    String add(final String name, final DeckSection to, final int count) {
        final PaperCard card = printingFor(name);
        if (card == null) {
            return "No card is called " + name + ".";
        }
        final String limit = overLimit(card, count);
        if (limit != null) {
            return limit;
        }
        return change(name, () -> {
            deck.getOrCreate(to).add(card, count);
            return null;
        });
    }

    String remove(final String name, final DeckSection from, final int count) {
        final CardPool pool = deck.get(from);
        final int have = pool == null ? 0 : pool.countByName(name);
        if (have == 0) {
            return "There is no " + name + " to remove.";
        }
        return change(name, () -> {
            take(pool, name, Math.min(count, have));
            return null;
        });
    }

    String move(final String name, final DeckSection from, final DeckSection to, final int count) {
        if (to == DeckSection.Commander) {
            return makeCommander(name, from);
        }
        final CardPool pool = deck.get(from);
        final int have = pool == null ? 0 : pool.countByName(name);
        if (have == 0 || from == to) {
            return "There is no " + name + " to move.";
        }
        return change(name, () -> {
            final CardPool moving = take(pool, name, Math.min(count, have));
            deck.getOrCreate(to).addAll(moving);
            return null;
        });
    }

    /** Makes a card the commander, from a section of the deck or (from null) the catalogue. A commander it replaces goes to the main deck. */
    String makeCommander(final String name, final DeckSection from) {
        final DeckFormat df = check.deckFormat();
        if (!df.hasCommander()) {
            return "Only commander formats have a commander.";
        }
        final CardPool source = from == null ? null : deck.get(from);
        final PaperCard card = source == null ? printingFor(name) : source.find(c -> c.getName().equals(name));
        if (card == null) {
            return "No card is called " + name + ".";
        }
        final CardRules rules = card.getRules();
        if (!df.isLegalCommander(rules) && !(df.hasSignatureSpell() && rules.canBeSignatureSpell())) {
            return name + " can't be your commander.";
        }
        final CardPool commanders = deck.getOrCreate(DeckSection.Commander);
        if (commanders.countByName(name) > 0) {
            return name + " is already your commander.";
        }
        if (source == null) {
            final String limit = overLimit(card, 1);
            if (limit != null) {
                return limit;
            }
        }
        return change(name, () -> {
            if (source != null) {
                source.remove(card, 1);
            }
            placeCommander(card, commanders);
            if (NEW_DECK.equals(deck.getName())) {
                nameAfterCommander();
            }
            return null;
        });
    }

    /** Sets how many copies of each printing a section holds of one card. The total can't change. */
    String setPrintings(final String name, final DeckSection in, final Map<String, Integer> countsByImageKey) {
        final CardPool pool = deck.get(in);
        final int have = pool == null ? 0 : pool.countByName(name);
        final int total = countsByImageKey.values().stream().mapToInt(Integer::intValue).sum();
        if (total != have) {
            return "The total must stay at " + have + ".";
        }
        final Map<PaperCard, Integer> wanted = new LinkedHashMap<>();
        for (final Map.Entry<String, Integer> e : countsByImageKey.entrySet()) {
            final PaperCard printing = ImageUtil.getPaperCardFromImageKey(e.getKey());
            if (printing == null || !printing.getName().equals(name)) {
                return "That isn't a printing of " + name + ".";
            }
            wanted.merge(printing, e.getValue(), Integer::sum);
        }
        return change(name, () -> {
            take(pool, name, have);
            wanted.forEach((printing, n) -> pool.add(printing, n));
            return null;
        });
    }

    /** Sets how many of each basic land the main deck holds. */
    String setLands(final Map<String, Integer> countsByLand) {
        for (final Map.Entry<String, Integer> e : countsByLand.entrySet()) {
            if (!BASICS.containsKey(e.getKey())) {
                return e.getKey() + " is not a basic land.";
            }
            if (e.getValue() > deck.getMain().countByName(e.getKey()) && !landAllowed(e.getKey())) {
                return e.getKey() + " is outside the commander's colours.";
            }
        }
        return change(countsByLand.keySet().iterator().next(), () -> {
            for (final Map.Entry<String, Integer> e : countsByLand.entrySet()) {
                final int have = deck.getMain().countByName(e.getKey());
                if (e.getValue() > have) {
                    deck.getMain().add(printingFor(e.getKey()), e.getValue() - have);
                } else if (e.getValue() < have) {
                    take(deck.getMain(), e.getKey(), have - e.getValue());
                }
            }
            return null;
        });
    }

    String rename(final String wanted) {
        final String problem = DeckStore.nameProblem(wanted);
        if (problem != null) {
            return problem;
        }
        final String name = wanted.trim();
        if (target instanceof Stored s && copyOf == null) {
            final String existing = DeckStore.taken(s.storage(), name);
            if (existing != null && (owned == null || !DeckStore.sameFile(existing, owned))) {
                return "You already have a deck called " + existing + ".";
            }
        }
        return change(null, () -> {
            renameTo(name);
            return null;
        });
    }

    /** Changes what the deck is checked against. A change of format family moves the deck to that format's folder. */
    String setCheck(final Check wanted) {
        return change(null, () -> {
            moveTo(wanted);
            return null;
        });
    }

    String undo() {
        final Snapshot previous = undo.poll();
        if (previous == null) {
            return "Nothing to undo.";
        }
        landed = null;
        moveTo(previous.check());
        if (!previous.deck().getName().equals(deck.getName())) {
            // Another deck may have taken the old name since, and storage would overwrite it
            final String name = previous.deck().getName();
            renameTo(target instanceof Stored s ? DeckStore.freeName(s.storage(), name, owned) : name);
        }
        replaceCards(previous.deck());
        return save();
    }

    /** Saves a copy under a free name, and carries on editing the copy. */
    String duplicate() {
        if (copyOf != null) {
            becomeCopy();
            return save();
        }
        final Deck copy = new Deck(deck, deck.getName());
        if (target instanceof Stored s) {
            copy.setName(DeckStore.freeName(s.storage(), deck.getName(), null));
            owned = null;
        } else {
            target = new Device(UUID.randomUUID().toString());
            copy.setName(deck.getName() + " (copy)");
        }
        deck = copy;
        undo.clear();
        landed = null;
        return save();
    }

    String delete() {
        try {
            if (target instanceof Device d) {
                sink.deviceDeck(d.id(), null, check.format());
            } else if (target instanceof Stored s && owned != null) {
                synchronized (DeckCatalog.DECKS) {
                    s.storage().delete(owned);
                }
            }
        } catch (final RuntimeException e) {
            return "Could not delete: " + e.getMessage();
        }
        return null;
    }

    /** Adds another deck's cards, as far as the copy limit allows. */
    String addAll(final Deck other) {
        return change(null, () -> {
            for (final DeckSection section : List.of(DeckSection.Main, DeckSection.Sideboard)) {
                final CardPool from = other.get(section);
                if (from == null) {
                    continue;
                }
                for (final Map.Entry<PaperCard, Integer> e : from) {
                    final int room = room(e.getKey());
                    if (room > 0) {
                        deck.getOrCreate(section).add(e.getKey(), Math.min(room, e.getValue()));
                    }
                }
            }
            return null;
        });
    }

    /** Swaps the deck's cards for another deck's, keeping its name and where it is saved. */
    String replaceAll(final Deck other) {
        return change(null, () -> {
            replaceCards(other);
            return null;
        });
    }

    EditorState state(final boolean onSeat) {
        final DeckFormat df = check.deckFormat();
        final List<PaperCard> leaders = Legality.commanders(deck, check);
        final Legality.Result legality = Legality.check(deck, check);
        final boolean wanted = df.hasCommander() && (leaders.isEmpty() || (df.hasSignatureSpell() && deck.getSignatureSpell() == null));
        return new EditorState(deck.getName(), check.label(), check.format().name(),
                check.pool() == null ? null : check.pool().getName(), check.unrestricted(),
                target instanceof Device ? "device" : "storage", copyOf,
                cards(deck.get(DeckSection.Commander), legality), wanted, Legality.identityLetters(leaders),
                groups(deck.getMain(), legality), cards(deck.get(DeckSection.Sideboard), legality), lands(),
                DeckCatalog.stats(deck), legality.verdict(), legality.problemCount(), !undo.isEmpty(), landed, onSeat);
    }

    private String change(final String cardName, final Supplier<String> op) {
        final Snapshot before = new Snapshot(new Deck(deck, deck.getName()), check);
        final String refused = op.get();
        if (refused != null) {
            return refused;
        }
        undo.push(before);
        if (undo.size() > MOST_UNDO) {
            undo.removeLast();
        }
        landed = cardName;
        if (copyOf != null) {
            becomeCopy();
        }
        return save();
    }

    /** The first change to a deck that can't be changed in place: from now on the editor works on a copy of its own. */
    private void becomeCopy() {
        if (deck.getName().equals(copyOf)) {
            deck.setName(copyOf + " (copy)");
        }
        copyOf = null;
        owned = null;
        target = guest ? new Device(UUID.randomUUID().toString()) : new Stored(storages.of(check.format()));
    }

    private String save() {
        try {
            if (target instanceof Device d) {
                sink.deviceDeck(d.id(), String.join("\n", DeckSerializer.serializeDeck(deck)), check.format());
                saved = true;
                return null;
            }
            final IStorage<Deck> storage = ((Stored) target).storage();
            synchronized (DeckCatalog.DECKS) {
                // Storage overwrites by file name, so a deck saved for the first time takes a name nobody else has
                if (owned == null) {
                    deck.setName(DeckStore.freeName(storage, deck.getName(), null));
                }
                storage.add(deck);
                owned = deck.getName();
            }
            saved = true;
            return null;
        } catch (final RuntimeException e) {
            return "Could not save: " + e.getMessage();
        }
    }

    /** Gives the deck a new name, and its file with it. */
    private void renameTo(final String name) {
        if (!(target instanceof Stored s) || owned == null) {
            deck.setName(name);
            return;
        }
        synchronized (DeckCatalog.DECKS) {
            moveFile(s.storage(), s.storage(), name);
        }
    }

    /** Changes the check, and moves the deck when the new one's format keeps its decks in another folder. */
    private void moveTo(final Check wanted) {
        final boolean newFamily = DeckStore.family(wanted.format()) != DeckStore.family(check.format());
        check = wanted;
        if (!newFamily || !(target instanceof Stored s) || copyOf != null) {
            return;
        }
        final IStorage<Deck> to = storages.of(wanted.format());
        synchronized (DeckCatalog.DECKS) {
            if (owned == null) {
                target = new Stored(to);
                return;
            }
            moveFile(s.storage(), to, DeckStore.freeName(to, deck.getName(), null));
        }
    }

    /**
     * Saves the deck under a new name or folder and removes the old file. Storage deletes by the stored deck's own name,
     * so the old deck object must keep its name until it is deleted; and when both names give one file (a change of case),
     * the old file goes first, or deleting it would take the new one with it.
     */
    private void moveFile(final IStorage<Deck> from, final IStorage<Deck> to, final String name) {
        if (from == to && DeckStore.sameFile(owned, name)) {
            from.delete(owned);
            deck.setName(name);
            to.add(deck);
        } else {
            final Deck moved = new Deck(deck, name);
            to.add(moved);
            from.delete(owned);
            deck = moved;
        }
        owned = name;
        target = new Stored(to);
    }

    private void nameAfterCommander() {
        final String wanted = deck.getCommanders().get(0).getName() + " deck";
        if (target instanceof Stored s) {
            renameTo(DeckStore.freeName(s.storage(), wanted, owned));
        } else {
            deck.setName(wanted);
        }
    }

    /** Puts a card in the command zone as desktop's editor does: a partner joins, anything else replaces, and in Oathbreaker each slot holds one. */
    private void placeCommander(final PaperCard card, final CardPool commanders) {
        if (check.format() == GameType.Oathbreaker) {
            final boolean oathbreaker = card.getRules().canBeOathbreaker();
            final PaperCard sameSlot = commanders.find(c -> c.getRules().canBeOathbreaker() == oathbreaker);
            if (sameSlot != null) {
                deck.getMain().add(sameSlot, commanders.count(sameSlot));
                commanders.remove(sameSlot, commanders.count(sameSlot));
            }
        } else if (commanders.countAll() > 0) {
            final List<PaperCard> existing = commanders.toFlatList();
            final boolean partner = existing.size() == 1 && card.getRules().canBePartnerCommander()
                    && existing.get(0).getRules().canBePartnerCommanders(card.getRules());
            if (!partner) {
                deck.getMain().addAll(commanders);
                commanders.clear();
            }
        }
        commanders.add(card, 1);
    }

    private void replaceCards(final Deck from) {
        for (final DeckSection section : List.of(DeckSection.Main, DeckSection.Sideboard, DeckSection.Commander)) {
            final CardPool pool = deck.getOrCreate(section);
            pool.clear();
            final CardPool source = from.get(section);
            if (source != null) {
                pool.addAll(source);
            }
        }
    }

    /** Removes copies of one card from a pool, the printings with the fewest copies first, and returns what it took. */
    private static CardPool take(final CardPool pool, final String name, final int count) {
        final CardPool taken = new CardPool();
        final List<Map.Entry<PaperCard, Integer>> printings = new ArrayList<>();
        for (final Map.Entry<PaperCard, Integer> e : pool) {
            if (e.getKey().getName().equals(name)) {
                printings.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        printings.sort(Map.Entry.comparingByValue());
        int left = count;
        for (final Map.Entry<PaperCard, Integer> e : printings) {
            final int n = Math.min(left, e.getValue());
            pool.remove(e.getKey(), n);
            taken.add(e.getKey(), n);
            left -= n;
            if (left == 0) {
                break;
            }
        }
        return taken;
    }

    /** The printing a new copy takes: the one the deck already holds, if it holds only one, or the preferred art. */
    private PaperCard printingFor(final String name) {
        PaperCard only = null;
        for (final Map.Entry<PaperCard, Integer> e : deck.getAllCardsInASinglePool(true, false)) {
            if (e.getKey().getName().equals(name)) {
                if (only != null && !only.equals(e.getKey())) {
                    only = null;
                    break;
                }
                only = e.getKey();
            }
        }
        return only != null ? only : StaticData.instance().getCommonCards().getCard(name);
    }

    /** How many more copies of a card the copy limit allows, counted by name across the deck. */
    private int room(final PaperCard card) {
        final DeckFormat df = check.deckFormat();
        final int most = df.getMaxCardCopies(card);
        if (most == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return most - deck.getAllCardsInASinglePool(df.hasCommander(), false).countByName(card.getName());
    }

    private String overLimit(final PaperCard card, final int adding) {
        final int room = room(card);
        if (adding <= room) {
            return null;
        }
        final int most = check.deckFormat().getMaxCardCopies(card);
        return most == 1 ? card.getName() + " is already in the deck, and this format allows one."
                : (most - room) + " of " + most + " already, across all zones.";
    }

    private boolean landAllowed(final String land) {
        final List<PaperCard> leaders = Legality.commanders(deck, check);
        if (leaders.isEmpty()) {
            return true;
        }
        final String identity = Legality.identityLetters(leaders);
        final String needs = BASICS.get(land);
        return "C".equals(needs) ? identity.isEmpty() : identity.contains(needs);
    }

    private List<EditorLand> lands() {
        final List<EditorLand> out = new ArrayList<>();
        BASICS.forEach((name, letter) -> out.add(new EditorLand(name, letter, deck.getMain().countByName(name), landAllowed(name))));
        return out;
    }

    static List<EditorGroup> groups(final CardPool pool, final Legality.Result legality) {
        final Map<String, List<EditorCard>> byHeading = new LinkedHashMap<>();
        CardCatalog.HEADINGS.forEach(h -> byHeading.put(h, new ArrayList<>()));
        final Map<String, String> headings = new LinkedHashMap<>();
        for (final Map.Entry<PaperCard, Integer> e : pool) {
            headings.putIfAbsent(e.getKey().getName(), CardCatalog.heading(e.getKey()));
        }
        for (final EditorCard card : cards(pool, legality)) {
            byHeading.get(headings.get(card.name())).add(card);
        }
        final List<EditorGroup> out = new ArrayList<>();
        byHeading.forEach((heading, cards) -> {
            if (!cards.isEmpty()) {
                out.add(new EditorGroup(heading, cards));
            }
        });
        return out;
    }

    /** A pool's cards, one per name, in mana value then name order. */
    static List<EditorCard> cards(final CardPool pool, final Legality.Result legality) {
        final List<EditorCard> out = new ArrayList<>();
        if (pool == null) {
            return out;
        }
        final Map<String, List<Map.Entry<PaperCard, Integer>>> byName = new LinkedHashMap<>();
        for (final Map.Entry<PaperCard, Integer> e : pool) {
            byName.computeIfAbsent(e.getKey().getName(), n -> new ArrayList<>()).add(Map.entry(e.getKey(), e.getValue()));
        }
        byName.forEach((name, printings) -> {
            printings.sort(Map.Entry.<PaperCard, Integer>comparingByValue().reversed());
            final PaperCard shown = printings.get(0).getKey();
            final CardRules rules = shown.getRules();
            final List<EditorPrinting> split = printings.stream()
                    .map(p -> new EditorPrinting(p.getKey().getImageKey(false), p.getValue())).toList();
            out.add(new EditorCard(name, printings.stream().mapToInt(Map.Entry::getValue).sum(), shown.getImageKey(false),
                    JsonCodec.manaCost(rules.getManaCost()), rules.getManaCost().getCMC(), CardCatalog.letters(rules.getColor()),
                    printings.size(), split, legality.flags().get(name)));
        });
        out.sort(Comparator.comparingInt(EditorCard::mv).thenComparing(EditorCard::name));
        return out;
    }
}
