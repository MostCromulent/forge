package forge.web;

import forge.StaticData;
import forge.card.CardEdition;
import forge.card.CardRules;
import forge.card.mana.ManaCostShard;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckGroup;
import forge.deck.DeckgenUtil;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.util.ImageUtil;
import forge.util.Localizer;
import forge.util.MyRandom;
import forge.util.storage.IStorage;
import forge.web.ToBrowser.EditorCard;
import forge.web.ToBrowser.EditorGroup;
import forge.web.ToBrowser.EditorLand;
import forge.web.ToBrowser.EditorPrinting;
import forge.web.ToBrowser.EditorState;
import forge.web.ToBrowser.LandSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** One deck open in the browser's editor, where every change is checked against the engine's limits, applied and saved at once. */
final class DeckEditor {
    /** The name a new deck starts with, until its commander names it or the player does. */
    static final String NEW_DECK = "New deck";
    private static final int MOST_UNDO = 100;
    /** The key of the answer to renaming, duplicating or deleting a pool's deck. */
    private static final String GROUP_OWNS_IT = "lblWebEditorPoolOwnsDeck";
    /** The keys of the answers to changing what a deck built from a collection keeps as it is. */
    private static final String DECK_FIXED = "lblWebCollectionDeckFixed";
    private static final String MAIN_ONLY = "lblWebCollectionMainOnly";
    static final String NOT_OWNED = "lblWebCollectionNotOwned";
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

    sealed interface Target permits Stored, Device, Group {
    }

    /** A folder of the host's decks: a format's own, or a subfolder of it. */
    record Stored(IStorage<Deck> storage) implements Target {
    }

    /** A guest's browser, which keeps the deck under this id. */
    record Device(String id) implements Target {
    }

    /** A sealed or draft pool: the deck is the human deck of the group of its name, whose opponents stay as they are. */
    record Group(IStorage<DeckGroup> storage) implements Target {
    }

    /** A game mode's own cards, which a deck of that mode is built from, with cards read at each use and saved run after each save. mainOnly fixes every section but the main deck. */
    record Collection(String owner, Supplier<CardPool> cards, Predicate<PaperCard> isNew,
            Function<Deck, List<CardEdition>> landSets, Runnable saved, boolean mainOnly) {
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
    /** The cards the deck is built from, or null when it is built from every card. */
    private final Collection collection;
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
    /** The edition new basic lands come from, in limited mode; null otherwise. */
    private String landSet;

    /** saved says the deck was opened from where target saves it; a new deck, or one that is only being read, was not. */
    DeckEditor(final Deck deck, final boolean readOnly, final boolean saved, final Target target, final Check check,
            final DeckStore.Storages storages, final boolean guest, final DeviceSink sink) {
        this(deck, readOnly, saved, target, check, storages, guest, sink, null);
    }

    DeckEditor(final Deck deck, final boolean readOnly, final boolean saved, final Target target, final Check check,
            final DeckStore.Storages storages, final boolean guest, final DeviceSink sink, final Collection collection) {
        // Edited as a copy, so a deck another screen holds (a catalogue entry, a seat) never changes under it
        this.deck = new Deck(deck, deck.getName());
        this.target = target;
        this.check = check;
        this.storages = storages;
        this.guest = guest;
        this.sink = sink;
        this.collection = collection;
        this.copyOf = readOnly ? deck.getName() : null;
        this.saved = saved && !readOnly;
        this.owned = this.saved && target instanceof Stored ? deck.getName() : null;
        this.landSet = limited() ? poolLandSet() : collection != null ? firstLandSet() : null;
    }

    Collection collection() {
        return collection;
    }

    /** A sealed or draft deck: the pool is the catalogue, and cards move between it and the deck rather than appear. */
    boolean limited() {
        return !check.unrestricted() && check.deckFormat() == DeckFormat.Limited;
    }

    /** The cards the catalogue offers: the pool in limited mode, a collection's cards, otherwise every card. */
    CardCatalog catalogue() {
        if (collection != null) {
            return CardCatalog.of(ownedByName().values());
        }
        if (!limited()) {
            return CardCatalog.get();
        }
        final Map<String, PaperCard> byName = new LinkedHashMap<>();
        final CardPool side = deck.get(DeckSection.Sideboard);
        if (side != null) {
            side.forEach(e -> byName.putIfAbsent(e.getKey().getName(), e.getKey()));
        }
        for (final Map.Entry<PaperCard, Integer> e : deck.getMain()) {
            if (!e.getKey().getRules().getType().isBasicLand()) {
                byName.putIfAbsent(e.getKey().getName(), e.getKey());
            }
        }
        return CardCatalog.of(byName.values());
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

    /** Whether the deck exists where it is saved, so Done has a deck of its own to put on a seat. */
    boolean saved() {
        return saved;
    }

    String add(final String name, final DeckSection to, final int count) {
        if (limited()) {
            return addFromPool(name, to, count);
        }
        if (fixedSection(to)) {
            return Localizer.getInstance().getMessage(MAIN_ONLY);
        }
        final PaperCard card = collection == null || BASICS.containsKey(name) ? printingFor(name) : ownedByName().get(name);
        if (card == null) {
            return Localizer.getInstance().getMessage(collection == null ? "lblWebEditorNoSuchCard" : NOT_OWNED, String.valueOf(name));
        }
        final String limit = overLimit(card, count);
        if (limit != null) {
            return limit;
        }
        return change(name, () -> addOwned(deck.getOrCreate(to), card, count));
    }

    String remove(final String name, final DeckSection from, final int count) {
        if (limited() && from != DeckSection.Sideboard) {
            return move(name, from, DeckSection.Sideboard, count);
        }
        if (fixedSection(from)) {
            return Localizer.getInstance().getMessage(MAIN_ONLY);
        }
        final CardPool pool = deck.get(from);
        final int have = pool == null ? 0 : pool.countByName(name);
        if (have == 0) {
            return Localizer.getInstance().getMessage("lblWebEditorNoneToRemove", String.valueOf(name));
        }
        return change(name, () -> take(pool, name, Math.min(count, have)));
    }

    String move(final String name, final DeckSection from, final DeckSection to, final int count) {
        if (fixedSection(from) || fixedSection(to)) {
            return Localizer.getInstance().getMessage(MAIN_ONLY);
        }
        if (to == DeckSection.Commander) {
            return makeCommander(name, from);
        }
        final CardPool pool = deck.get(from);
        final int have = pool == null ? 0 : pool.countByName(name);
        if (have == 0 || from == to) {
            return Localizer.getInstance().getMessage("lblWebEditorNoneToMove", String.valueOf(name));
        }
        return change(name, () -> {
            final CardPool moving = take(pool, name, Math.min(count, have));
            deck.getOrCreate(to).addAll(moving);
        });
    }

    /** Makes a card the commander, from a section of the deck or (from null) the catalogue. A commander it replaces goes to the main deck. */
    String makeCommander(final String name, final DeckSection from) {
        if (fixedSection(DeckSection.Commander)) {
            return Localizer.getInstance().getMessage(MAIN_ONLY);
        }
        final DeckFormat df = check.deckFormat();
        if (!df.hasCommander()) {
            return Localizer.getInstance().getMessage("lblWebEditorNoCommanderFormat");
        }
        final CardPool source = from == null ? null : deck.get(from);
        final PaperCard card = source != null ? source.find(c -> c.getName().equals(name))
                : collection == null ? printingFor(name) : ownedByName().get(name);
        if (card == null && collection != null) {
            return Localizer.getInstance().getMessage(NOT_OWNED, String.valueOf(name));
        }
        if (card == null) {
            return Localizer.getInstance().getMessage("lblWebEditorNoSuchCard", String.valueOf(name));
        }
        final CardRules rules = card.getRules();
        if (!df.isLegalCommander(rules) && !(df.hasSignatureSpell() && rules.canBeSignatureSpell())) {
            return Localizer.getInstance().getMessage("lblWebEditorCannotCommand", String.valueOf(name));
        }
        final CardPool commanders = deck.getOrCreate(DeckSection.Commander);
        if (commanders.countByName(name) > 0) {
            return Localizer.getInstance().getMessage("lblWebEditorAlreadyCommander", String.valueOf(name));
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
        });
    }

    /** Sets how many copies of each printing a section holds of one card. The total can't change. */
    String setPrintings(final String name, final DeckSection in, final Map<String, Integer> countsByImageKey) {
        final CardPool pool = deck.get(in);
        final int have = pool == null ? 0 : pool.countByName(name);
        final int total = countsByImageKey.values().stream().mapToInt(Integer::intValue).sum();
        if (total != have) {
            return Localizer.getInstance().getMessage("lblWebEditorTotalFixed", have);
        }
        final Map<PaperCard, Integer> wanted = new LinkedHashMap<>();
        for (final Map.Entry<String, Integer> e : countsByImageKey.entrySet()) {
            final PaperCard printing = ImageUtil.getPaperCardFromImageKey(e.getKey());
            if (printing == null || !printing.getName().equals(name)) {
                return Localizer.getInstance().getMessage("lblWebEditorNotAPrinting", String.valueOf(name));
            }
            wanted.merge(printing, e.getValue(), Integer::sum);
        }
        // With a collection, each printing only as many times as it is owned and not used elsewhere in the deck
        if (collection != null && !BASICS.containsKey(name)) {
            for (final Map.Entry<PaperCard, Integer> e : wanted.entrySet()) {
                final int here = pool == null ? 0 : pool.count(e.getKey());
                if (e.getValue() > spare(e.getKey()) + here) {
                    return Localizer.getInstance().getMessage(NOT_OWNED, String.valueOf(name));
                }
            }
        }
        return change(name, () -> {
            take(pool, name, have);
            wanted.forEach((printing, n) -> pool.add(printing, n));
        });
    }

    /** Sets how many of each basic land the main deck holds. */
    String setLands(final Map<String, Integer> countsByLand) {
        for (final Map.Entry<String, Integer> e : countsByLand.entrySet()) {
            if (!BASICS.containsKey(e.getKey())) {
                return Localizer.getInstance().getMessage("lblWebEditorNotBasic", String.valueOf(e.getKey()));
            }
            if (e.getValue() > deck.getMain().countByName(e.getKey()) && !landAllowed(e.getKey())) {
                return Localizer.getInstance().getMessage("lblWebEditorOutsideColours", e.getKey());
            }
        }
        return change(countsByLand.keySet().iterator().next(), () -> {
            for (final Map.Entry<String, Integer> e : countsByLand.entrySet()) {
                final int have = deck.getMain().countByName(e.getKey());
                if (e.getValue() > have) {
                    addLands(e.getKey(), e.getValue() - have);
                } else if (e.getValue() < have) {
                    final CardPool taken = take(deck.getMain(), e.getKey(), have - e.getValue());
                    // A pool's lands are part of the pool, and desktop's limited editor moves them back to it
                    if (limited()) {
                        deck.getOrCreate(DeckSection.Sideboard).addAll(taken);
                    }
                }
            }
        });
    }

    /** Chooses the edition new basic lands come from, as desktop's Add Basic Lands dialog does. */
    String setLandSet(final String editionCode) {
        final CardEdition edition = StaticData.instance().getEditions().get(editionCode);
        final boolean offered = collection == null ? limited() : collection.landSets().apply(deck).contains(edition);
        if (!offered || edition == null || !edition.hasBasicLands()) {
            return Localizer.getInstance().getMessage("lblWebEditorNoBasicsFrom", String.valueOf(editionCode));
        }
        landSet = edition.getCode();
        return null;
    }

    /** Sets the basics to the counts desktop's Add Basic Lands dialog suggests for the rest of the deck. */
    String suggestLands() {
        final Map<ManaCostShard, Integer> suggested = DeckgenUtil.suggestBasicLandCount(deck);
        final Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("Plains", suggested.getOrDefault(ManaCostShard.WHITE, 0));
        counts.put("Island", suggested.getOrDefault(ManaCostShard.BLUE, 0));
        counts.put("Swamp", suggested.getOrDefault(ManaCostShard.BLACK, 0));
        counts.put("Mountain", suggested.getOrDefault(ManaCostShard.RED, 0));
        counts.put("Forest", suggested.getOrDefault(ManaCostShard.GREEN, 0));
        return setLands(counts);
    }

    String rename(final String wanted) {
        if (collection != null) {
            return Localizer.getInstance().getMessage(DECK_FIXED);
        }
        if (target instanceof Group) {
            return Localizer.getInstance().getMessage(GROUP_OWNS_IT);
        }
        final String problem = DeckStore.nameProblem(wanted);
        if (problem != null) {
            return problem;
        }
        final String name = wanted.trim();
        if (target instanceof Stored s && copyOf == null) {
            final String existing = DeckStore.taken(s.storage(), name);
            if (existing != null && (owned == null || !DeckStore.sameFile(existing, owned))) {
                return Localizer.getInstance().getMessage("lblWebEditorNameTaken", existing);
            }
        }
        return change(null, () -> renameTo(name));
    }

    /** Changes what the deck is checked against. A change of format family moves the deck to that format's folder. */
    String setCheck(final Check wanted) {
        if (collection != null) {
            return Localizer.getInstance().getMessage(DECK_FIXED);
        }
        return change(null, () -> moveTo(wanted));
    }

    String undo() {
        final Snapshot previous = undo.poll();
        if (previous == null) {
            return Localizer.getInstance().getMessage("lblWebEditorNothingToUndo");
        }
        landed = null;
        moveTo(previous.check());
        if (!previous.deck().getName().equals(deck.getName())) {
            // Another deck may have taken the old name since, and storage would overwrite it
            final String name = previous.deck().getName();
            renameTo(target instanceof Stored s ? DeckStore.freeName(s.storage(), name, owned) : name);
        }
        // Every section, since a limited deck's Attractions and Contraptions move there from the pool
        replaceCards(previous.deck(), List.of(DeckSection.values()));
        return save();
    }

    /** Saves a copy under a free name, and carries on editing the copy. */
    String duplicate() {
        if (collection != null) {
            return Localizer.getInstance().getMessage(DECK_FIXED);
        }
        if (target instanceof Group) {
            return Localizer.getInstance().getMessage(GROUP_OWNS_IT);
        }
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
        if (collection != null) {
            return Localizer.getInstance().getMessage(DECK_FIXED);
        }
        if (target instanceof Group) {
            return Localizer.getInstance().getMessage(GROUP_OWNS_IT);
        }
        // A precon, or someone else's deck, being looked at: nothing of the player's is saved to delete
        if (copyOf != null) {
            return Localizer.getInstance().getMessage("lblWebEditorOnlyOwnDelete");
        }
        try {
            if (target instanceof Device d) {
                sink.deviceDeck(d.id(), null, check.format());
            } else if (target instanceof Stored s && owned != null) {
                synchronized (DeckCatalog.DECKS) {
                    s.storage().delete(owned);
                }
            }
        } catch (final RuntimeException e) {
            return Localizer.getInstance().getMessage("lblWebEditorDeleteFailed", String.valueOf(e.getMessage()));
        }
        return null;
    }

    /** Adds another deck's cards, as far as the copy limit allows. */
    String addAll(final Deck other) {
        return change(null, () -> addCards(other));
    }

    private void addCards(final Deck other) {
        for (final DeckSection section : List.of(DeckSection.Main, DeckSection.Sideboard)) {
            final CardPool from = other.get(section);
            if (from == null || fixedSection(section)) {
                continue;
            }
            for (final Map.Entry<PaperCard, Integer> e : from) {
                final int room = room(e.getKey());
                if (room > 0) {
                    addOwned(deck.getOrCreate(section), e.getKey(), Math.min(room, e.getValue()));
                }
            }
        }
    }

    /** Swaps the deck's cards for another deck's, keeping its name and where it is saved. */
    String replaceAll(final Deck other) {
        if (collection != null) {
            // The sections the collection lets change are replaced, and by what it can supply
            return change(null, () -> {
                deck.getMain().clear();
                if (!collection.mainOnly()) {
                    deck.getOrCreate(DeckSection.Sideboard).clear();
                }
                addCards(other);
            });
        }
        return change(null, () -> replaceCards(other, List.of(DeckSection.Main, DeckSection.Sideboard, DeckSection.Commander)));
    }

    EditorState state() {
        final DeckFormat df = check.deckFormat();
        // A collection's deck has a commander its format knows nothing of, whose colours the catalogue starts narrowed to
        final List<PaperCard> leaders = collection != null ? deck.getCommanders() : Legality.commanders(deck, check);
        final Legality.Result legality = Legality.check(deck, check);
        final boolean wanted = df.hasCommander() && (leaders.isEmpty() || (df.hasSignatureSpell() && deck.getSignatureSpell() == null));
        return new EditorState(deck.getName(), check.label(), check.format().name(),
                check.pool() == null ? null : check.pool().getName(), check.unrestricted(),
                target instanceof Device ? "device" : "storage", copyOf,
                cards(deck.get(DeckSection.Commander), legality), wanted, Legality.identityLetters(leaders),
                groups(deck.getMain(), legality), cards(deck.get(DeckSection.Sideboard), legality), lands(),
                DeckCatalog.stats(deck), legality.verdict(), !undo.isEmpty(), landed,
                limited(), landSet, limited() ? LandSets.ALL : collection == null ? List.of()
                        : collection.landSets().apply(deck).stream().map(e -> new LandSet(e.getCode(), e.getName())).toList(),
                collection == null ? null : collection.owner());
    }

    private String change(final String cardName, final Runnable op) {
        final Snapshot before = new Snapshot(new Deck(deck, deck.getName()), check);
        op.run();
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
            if (target instanceof Group g) {
                synchronized (DeckCatalog.DECKS) {
                    final DeckGroup group = g.storage().get(deck.getName());
                    if (group == null) {
                        return Localizer.getInstance().getMessage("lblWebEditorPoolGone", deck.getName());
                    }
                    group.setHumanDeck(new Deck(deck, deck.getName()));
                    g.storage().add(group);
                }
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
                // Storage keeps this very deck, which goes on being edited, so what the list knew of it is out of date
                DeckCatalog.changed(deck);
                owned = deck.getName();
            }
            saved = true;
            if (collection != null) {
                collection.saved().run();
            }
            return null;
        } catch (final RuntimeException e) {
            return Localizer.getInstance().getMessage("lblWebEditorSaveFailed", String.valueOf(e.getMessage()));
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

    /** Storage deletes by the deck's own name, so the old deck keeps its name until deleted, and goes first when both names are one file. */
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

    private void replaceCards(final Deck from, final List<DeckSection> sections) {
        for (final DeckSection section : sections) {
            if (!deck.has(section) && !from.has(section)) {
                continue;
            }
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

    private String addFromPool(final String name, final DeckSection to, final int count) {
        final CardPool side = deck.get(DeckSection.Sideboard);
        final PaperCard card = side == null ? null : side.find(c -> c.getName().equals(name));
        if (card == null) {
            return Localizer.getInstance().getMessage("lblWebEditorNoneInPool", String.valueOf(name));
        }
        // Attractions and Contraptions go to their own sections, as in desktop's limited editor
        return move(name, DeckSection.Sideboard, to == DeckSection.Main ? DeckSection.matchingSection(card) : to, count);
    }

    /** Adds basics to the main deck: the pool's own first in limited mode, then new ones. */
    private void addLands(final String name, final int count) {
        int wanted = count;
        if (limited()) {
            final CardPool side = deck.get(DeckSection.Sideboard);
            final int fromPool = side == null ? 0 : Math.min(wanted, side.countByName(name));
            if (fromPool > 0) {
                deck.getMain().addAll(take(side, name, fromPool));
                wanted -= fromPool;
            }
        }
        if (wanted > 0) {
            final PaperCard fromSet = landSet == null ? null : StaticData.instance().getCommonCards().getCard(name, landSet);
            deck.getMain().add(fromSet != null ? fromSet : printingFor(name), wanted);
        }
    }

    /** A random edition among the pool's that has basic lands, as desktop's limited editor chooses. */
    private String poolLandSet() {
        final List<String> codes = new ArrayList<>();
        for (final Map.Entry<PaperCard, Integer> e : deck.getAllCardsInASinglePool(false, true)) {
            final CardEdition edition = StaticData.instance().getEditions().get(e.getKey().getEdition());
            if (edition != null && edition.hasBasicLands() && !codes.contains(edition.getCode())) {
                codes.add(edition.getCode());
            }
        }
        return codes.isEmpty() ? CardEdition.Predicates.getRandomSetWithAllBasicLands(StaticData.instance().getEditions()).getCode()
                : codes.get(MyRandom.getRandom().nextInt(codes.size()));
    }

    /** Built once here, because StaticData's sorted list is built lazily without a lock and two editors opening at once read it half sorted. */
    private static final class LandSets {
        static final List<LandSet> ALL = build();

        private static List<LandSet> build() {
            final List<CardEdition> editions = new ArrayList<>();
            for (final CardEdition e : StaticData.instance().getEditions()) {
                if (e.hasBasicLands()) {
                    editions.add(e);
                }
            }
            Collections.sort(editions);
            Collections.reverse(editions);
            return editions.stream().map(e -> new LandSet(e.getCode(), e.getName())).toList();
        }
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

    /** How many more copies of a card the copy limit allows, counted by name across the deck, and a collection can supply. */
    private int room(final PaperCard card) {
        final DeckFormat df = check.deckFormat();
        final int most = df.getMaxCardCopies(card);
        final int byLimit = most == Integer.MAX_VALUE ? Integer.MAX_VALUE
                : most - deck.getAllCardsInASinglePool(df.hasCommander(), false).countByName(card.getName());
        // A basic land is free; any other card, however many the rules allow, is only as many as are owned
        if (collection == null || BASICS.containsKey(card.getName())) {
            return byLimit;
        }
        // The Commander section holds an owned copy too
        final int spare = collection.cards().get().countByName(card.getName())
                - deck.getAllCardsInASinglePool(true, false).countByName(card.getName());
        return Math.min(byLimit, spare);
    }

    /** How many more copies of this very printing the collection can supply, the whole deck counted. */
    private int spare(final PaperCard printing) {
        return collection.cards().get().count(printing) - deck.getAllCardsInASinglePool(true, false).count(printing);
    }

    /** Adds copies of a card: as it is without a collection or for a basic land, otherwise as the printings owned, the one asked for first. */
    private void addOwned(final CardPool to, final PaperCard card, final int count) {
        if (collection == null || BASICS.containsKey(card.getName())) {
            to.add(card, count);
            return;
        }
        final List<PaperCard> printings = new ArrayList<>();
        collection.cards().get().forEach(e -> {
            if (e.getKey().getName().equals(card.getName())) {
                printings.add(e.getKey());
            }
        });
        // An imported list names a printing, which is taken first when it is owned
        printings.sort((a, b) -> Boolean.compare(!a.equals(card), !b.equals(card)));
        int left = count;
        for (final PaperCard printing : printings) {
            final int take = Math.min(left, spare(printing));
            if (take > 0) {
                to.add(printing, take);
                left -= take;
            }
        }
    }

    /** A section a collection's deck keeps as it is, or does not have. */
    private boolean fixedSection(final DeckSection section) {
        return collection != null && collection.mainOnly() && section != DeckSection.Main;
    }

    /** The collection's cards by name, one printing each. */
    private Map<String, PaperCard> ownedByName() {
        final Map<String, PaperCard> byName = new LinkedHashMap<>();
        collection.cards().get().forEach(e -> byName.putIfAbsent(e.getKey().getName(), e.getKey()));
        return byName;
    }

    /** The first edition a collection's basic lands may come from, or null when it names none. */
    private String firstLandSet() {
        final List<CardEdition> sets = collection.landSets().apply(deck);
        return sets.isEmpty() ? null : sets.get(0).getCode();
    }

    private String overLimit(final PaperCard card, final int adding) {
        final int room = room(card);
        if (adding <= room) {
            return null;
        }
        final int most = check.deckFormat().getMaxCardCopies(card);
        if (most == Integer.MAX_VALUE) {
            return Localizer.getInstance().getMessage("lblWebConquestNoneLeft", card.getName());
        }
        return most == 1 ? Localizer.getInstance().getMessage("lblWebEditorOnlyOne", card.getName())
                : Localizer.getInstance().getMessage("lblWebEditorCopiesAlready", most - room, most);
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
