package forge.web;

import com.google.gson.JsonObject;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckGroup;
import forge.deck.DeckSection;
import forge.deck.DeckUrlLoader;
import forge.deck.io.DeckSerializer;
import forge.game.GameFormat;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.FileSection;
import forge.util.storage.IStorage;
import forge.web.DeckCatalog.OnDevice;
import forge.web.FromBrowser.BrowseFormat;
import forge.web.FromBrowser.CatalogueQuery;
import forge.web.FromBrowser.CountedName;
import forge.web.FromBrowser.DeviceDeckText;
import forge.web.FromBrowser.DeviceDecks;
import forge.web.FromBrowser.EditorCheck;
import forge.web.FromBrowser.EditorDeck;
import forge.web.FromBrowser.EditorEdit;
import forge.web.FromBrowser.EditorOpen;
import forge.web.FromBrowser.EditorRename;
import forge.web.FromBrowser.ImportCommit;
import forge.web.FromBrowser.ImportFetch;
import forge.web.FromBrowser.ImportRead;
import forge.web.ToBrowser.DeviceDeck;
import forge.web.ToBrowser.EditorMessage;
import forge.web.ToBrowser.Fetched;
import forge.web.ToBrowser.ImportProblem;
import forge.web.ToBrowser.ImportResult;
import forge.web.ToBrowser.ImportSummary;
import forge.web.ToBrowser.NameTaken;
import forge.web.ToBrowser.Notice;
import org.tinylog.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One browser's deck editor and deck importer. The browser's session hands it the messages about decks; it keeps the
 * deck being edited and, for a guest, the decks the guest's browser keeps. Decks reach a seat through the lobby, as a
 * deck chosen in the finder does.
 */
final class DeckSession {
    private final Lobby lobby;
    private final WebGuiBase ui;
    private final BooleanSupplier host;
    private final BooleanSupplier atTable;
    private final DeckStore.Storages storages = DeckStore.real();
    /** A guest's decks, by the id its browser keeps each under. The lobby lists them too. */
    private final Map<String, OnDevice> device;

    private DeckEditor editor;
    /** The seat whose finder opened the editor, and the table it was at, so Done puts the deck back only where it came from. */
    private Integer editorSeat;
    private int editorTable;
    /** The subfolder of its format's decks the open deck came from, "" for the top. */
    private String editorPath = "";

    DeckSession(final Lobby lobby, final WebGuiBase ui, final BooleanSupplier host, final BooleanSupplier atTable,
            final Map<String, OnDevice> device) {
        this.lobby = lobby;
        this.ui = ui;
        this.host = host;
        this.atTable = atTable;
        this.device = device;
    }

    /** A browser arrived again: it is shown the editor it left, and a guest is sent the deck it may have missed. */
    synchronized void reconnected(final BrowserChannel channel) {
        if (editor == null) {
            return;
        }
        channel.send(new EditorMessage(editor.state(editorSeat != null)));
        if (editor.target() instanceof DeckEditor.Device d) {
            channel.send(deviceDeck(d.id(), editor.deck(), editor.check().format()));
        }
    }

    void onMessage(final BrowserChannel channel, final JsonObject msg) {
        final String type = msg.get("t").getAsString();
        switch (type) {
            case "browseFormat" -> {
                if (host.getAsBoolean() && !atTable.getAsBoolean()) {
                    lobby.setBrowseFormat(gameType(Wire.decode(msg, BrowseFormat.class).format()));
                    channel.send(lobby.decks());
                }
            }
            case "deviceDecks" -> {
                if (!host.getAsBoolean()) {
                    keepDeviceDecks(Wire.decode(msg, DeviceDecks.class).decks());
                    channel.send(lobby.decks());
                }
            }
            // Building the catalogue reads every card once, which is far too slow for the socket thread
            case "catalogue" -> {
                final CatalogueQuery q = Wire.decode(msg, CatalogueQuery.class);
                ui.runBackgroundTask("Catalogue", () -> channel.send(catalogue(q)));
            }
            // Reading a list looks up misspelt names across every card, which is too slow for the socket thread
            case "importRead" -> {
                final ImportRead read = Wire.decode(msg, ImportRead.class);
                final Check check = check(read.format(), read.cardPool(), read.unrestricted());
                ui.runBackgroundTask("Import", () -> channel.send(DeckImport.result(read.request(), DeckImport.read(read.text(), check), check, null)));
            }
            // Fetching waits on a web site
            case "importFetch" -> {
                final ImportFetch fetch = Wire.decode(msg, ImportFetch.class);
                ui.runBackgroundTask("Import", () -> channel.send(fetched(fetch)));
            }
            case "importCommit" -> commit(channel, Wire.decode(msg, ImportCommit.class));
            default -> edit(channel, type, msg);
        }
    }

    private synchronized void edit(final BrowserChannel channel, final String type, final JsonObject msg) {
        if ("editorOpen".equals(type)) {
            open(Wire.decode(msg, EditorOpen.class), null, channel);
        }
        if (editor == null) {
            return;
        }
        final String refused = switch (type) {
            case "editorEdit" -> change(Wire.decode(msg, EditorEdit.class));
            case "editorRename" -> editor.rename(Wire.decode(msg, EditorRename.class).name());
            case "editorCheck" -> {
                final EditorCheck c = Wire.decode(msg, EditorCheck.class);
                yield editor.setCheck(check(c.format(), c.cardPool(), c.unrestricted()));
            }
            case "editorUndo" -> editor.undo();
            case "editorDeck" -> Wire.decode(msg, EditorDeck.class).op() == FromBrowser.DeckOp.delete ? delete(channel) : editor.duplicate();
            case "editorClose" -> close(channel);
            default -> null;
        };
        if (refused != null) {
            channel.send(new Notice(refused, null, false));
        }
        if (editor != null) {
            channel.send(new EditorMessage(editor.state(editorSeat != null)));
        }
    }

    /** Opens the editor. checkedAgainst is the check to open with, or null for the table's, or the finder's when there is no table. */
    private void open(final EditorOpen o, final Check checkedAgainst, final BrowserChannel channel) {
        final boolean guest = !host.getAsBoolean();
        final Deck deck;
        final boolean readOnly;
        final DeckEditor.Target target;
        Check check = checkedAgainst != null ? checkedAgainst
                : check(lobby.format().name(), atTable.getAsBoolean() && lobby.cardPool() != null ? lobby.cardPool().getName() : null, false);
        editorPath = "";
        if (o.newFormat() != null) {
            check = Check.of(gameType(o.newFormat()), null);
            deck = new Deck(DeckEditor.NEW_DECK);
            readOnly = false;
            target = guest ? new DeckEditor.Device(UUID.randomUUID().toString()) : new DeckEditor.Stored(storages.of(check.format()));
        } else {
            deck = o.key() == null ? null : lobby.deck(o.key());
            if (deck == null) {
                channel.send(new Notice("That deck can't be opened.", null, true));
                return;
            }
            readOnly = o.copy() || DeckCatalog.readOnly(o.key(), guest);
            if (o.key().startsWith(DeckCatalog.DEVICE + ":")) {
                target = new DeckEditor.Device(o.key().substring(DeckCatalog.DEVICE.length() + 1));
            } else {
                editorPath = readOnly ? "" : DeckCatalog.pathOf(o.key());
                final IStorage<Deck> root = storages.of(check.format());
                final IStorage<Deck> folder = editorPath.isEmpty() ? null : root.tryGetFolder(editorPath.substring(1));
                target = new DeckEditor.Stored(folder == null ? root : folder);
            }
        }
        editor = new DeckEditor(deck, readOnly, o.key() != null, target, check, storages, guest, this::sendDeviceDeck);
        editorSeat = o.seat();
        editorTable = lobby.table();
    }

    /** Opens a sealed or draft pool's deck, which saves back into its pool as desktop's limited editor does. */
    synchronized void openPool(final Deck human, final IStorage<DeckGroup> storage, final GameType type, final BrowserChannel channel) {
        editor = new DeckEditor(human, false, true, new DeckEditor.Group(storage), Check.of(type, null), storages,
                !host.getAsBoolean(), this::sendDeviceDeck);
        editorSeat = null;
        editorPath = "";
        channel.send(new EditorMessage(editor.state(false)));
    }

    /** The pool whose deck is open, or null. */
    synchronized String openPoolName() {
        return editor != null && editor.target() instanceof DeckEditor.Group ? editor.deck().getName() : null;
    }

    private String change(final EditorEdit e) {
        final DeckSection from = e.from() == null ? DeckSection.Main : e.from();
        final DeckSection to = e.to() == null ? DeckSection.Main : e.to();
        return switch (e.op()) {
            case add -> editor.add(e.name(), to, Math.max(1, e.count()));
            case remove -> editor.remove(e.name(), from, Math.max(1, e.count()));
            case move -> editor.move(e.name(), from, to, Math.max(1, e.count()));
            case commander -> editor.makeCommander(e.name(), e.from());
            case printings -> editor.setPrintings(e.name(), from, counts(e.printings()));
            case lands -> editor.setLands(counts(e.lands()));
            case landSet -> editor.setLandSet(e.name());
            case suggestLands -> editor.suggestLands();
        };
    }

    /** Done: the deck goes on the seat whose finder opened the editor, if that seat is still at the same table and plays this format. */
    private String close(final BrowserChannel channel) {
        final DeckEditor done = editor;
        final Integer seat = editorSeat;
        editor = null;
        channel.send(new EditorMessage(null));
        channel.send(lobby.decks());
        // A deck never saved (new and untouched, or a precon only looked at) has nothing to put on the seat
        if (seat == null || !atTable.getAsBoolean() || !done.saved()) {
            return null;
        }
        if (lobby.table() != editorTable) {
            return "Not put on your seat: the table changed.";
        }
        if (DeckStore.family(done.check().format()) != DeckStore.family(lobby.format())) {
            return "Not put on your seat: this is a " + done.check().format() + " deck.";
        }
        lobby.setDeck(seat, adopt(done));
        channel.send(lobby.state());
        return null;
    }

    private String delete(final BrowserChannel channel) {
        final String problem = editor.delete();
        if (problem != null) {
            return problem;
        }
        editor = null;
        channel.send(new EditorMessage(null));
        channel.send(lobby.decks());
        return null;
    }

    /** Registers the editor's deck under the key the finder gives it now, which a rename or a move can have changed. */
    private String adopt(final DeckEditor done) {
        if (done.target() instanceof DeckEditor.Device d) {
            return lobby.adopt(DeckCatalog.DEVICE, d.id(), done.deck());
        }
        final boolean atRoot = done.target() instanceof DeckEditor.Stored s && s.storage() == storages.of(done.check().format());
        return lobby.adopt(DeckCatalog.MINE, atRoot ? "" : editorPath, done.deck());
    }

    private ToBrowser.CataloguePage catalogue(final CatalogueQuery q) {
        final DeckEditor e;
        synchronized (this) {
            e = editor;
        }
        final Check check = e == null ? Check.none() : e.check();
        final Deck deck = e == null ? new Deck("") : e.deck();
        final List<PaperCard> commanders = Legality.commanders(deck, check);
        final DeckFormat df = check.deckFormat();
        // A commander deck without its commander shows only cards that can be one, and a Background only once a commander can choose one
        final Predicate<PaperCard> commanderOnly = df.hasCommander() && commanders.isEmpty()
                ? c -> df.isLegalCommander(c.getRules()) && !c.getRules().getType().hasSubtype("Background")
                : null;
        final CardPool inDeck = deck.getAllCardsInASinglePool(true, false);
        final Function<PaperCard, String> problems = Legality.cardProblems(check, commanders);
        return (e == null ? CardCatalog.get() : e.catalogue()).query(q.request(), new CardCatalog.Query(q.text(), q.colours(), q.type(), q.mv(), q.sort(),
                q.offset(), q.showAll()), problems, commanderOnly, inDeck::countByName);
    }

    private ImportResult fetched(final ImportFetch fetch) {
        try {
            final DeckUrlLoader.FetchedDeck f = DeckUrlLoader.fetch(fetch.url());
            final Check check = Check.of(DeckCatalog.familyOf(f.format()), null);
            final DeckImport.Read read = DeckImport.read(f.text(), check);
            final ImportResult result = DeckImport.result(fetch.request(), read, check,
                    new Fetched(f.providerName(), f.sourceUrl(), f.text(), check.format().name()));
            return new ImportResult(result.request(), result.lines(), result.problems(), result.summary(), f.name(), result.fetched());
        } catch (final IOException | RuntimeException e) {
            Logger.warn(e, "Could not fetch a deck from " + fetch.url());
            return new ImportResult(fetch.request(), List.of(),
                    List.of(new ImportProblem(-1, "Couldn't fetch the list", e.getMessage() == null ? "The site didn't answer." : e.getMessage(), List.of())),
                    new ImportSummary(0, 0, 0, null, false, "", null, List.of(), List.of()), null, null);
        }
    }

    /** Saves an imported deck, then does what the player asked: puts it on a seat, opens it, or adds it to the open deck. */
    private synchronized void commit(final BrowserChannel channel, final ImportCommit c) {
        final Check check = check(c.format(), c.cardPool(), c.unrestricted());
        final Deck deck = DeckImport.read(c.text(), check).deck();
        if (c.action() == FromBrowser.ImportAction.add || c.action() == FromBrowser.ImportAction.replace) {
            if (editor != null) {
                final String refused = c.action() == FromBrowser.ImportAction.add ? editor.addAll(deck) : editor.replaceAll(deck);
                if (refused != null) {
                    channel.send(new Notice(refused, null, false));
                }
                channel.send(new EditorMessage(editor.state(editorSeat != null)));
            }
            return;
        }
        final String nameProblem = DeckStore.nameProblem(c.name());
        if (nameProblem != null) {
            channel.send(new Notice(nameProblem, null, false));
            return;
        }
        deck.setName(c.name().trim());
        if (c.url() != null) {
            deck.setSourceUrl(c.url());
        }
        final String key = host.getAsBoolean() ? saveOnHost(channel, deck, check, c) : saveOnDevice(channel, deck, check, c);
        if (key == null) {
            return;
        }
        switch (c.action()) {
            case use -> {
                channel.send(lobby.decks());
                if (c.seat() == null || !atTable.getAsBoolean()) {
                    return;
                }
                if (DeckStore.family(check.format()) != DeckStore.family(lobby.format())) {
                    channel.send(new Notice("Not put on your seat", "It is a " + check.format() + " deck, and this table plays "
                            + lobby.format() + ".", false));
                    return;
                }
                lobby.setDeck(c.seat(), relisted(key, deck));
                channel.send(lobby.state());
            }
            case edit -> {
                channel.send(lobby.decks());
                open(new EditorOpen(relisted(key, deck), null, c.seat(), false), check, channel);
                if (editor != null) {
                    channel.send(new EditorMessage(editor.state(editorSeat != null)));
                }
            }
            default -> channel.send(lobby.decks());
        }
    }

    /** A saved deck's key, registered again after the list was rebuilt, in case the rebuild left it out (Brawl drops illegal decks). */
    private String relisted(final String key, final Deck deck) {
        if (key.startsWith(DeckCatalog.DEVICE + ":")) {
            return lobby.adopt(DeckCatalog.DEVICE, key.substring(DeckCatalog.DEVICE.length() + 1), deck);
        }
        return lobby.adopt(key.substring(0, key.indexOf(':')), "", deck);
    }

    /** Saves to the host's decks, or to the decks loaded from links; null when the name is taken and the browser must be asked. */
    private String saveOnHost(final BrowserChannel channel, final Deck deck, final Check check, final ImportCommit c) {
        final boolean linked = c.url() != null;
        final IStorage<Deck> storage = linked ? DeckUrlLoader.storage() : storages.of(check.format());
        synchronized (DeckCatalog.DECKS) {
            final String taken = DeckStore.taken(storage, deck.getName());
            if (taken != null && c.clash() == null) {
                channel.send(new NameTaken(deck.getName()));
                return null;
            }
            if (taken != null && c.clash() == FromBrowser.Clash.keep) {
                deck.setName(DeckStore.freeName(storage, deck.getName(), null));
            } else if (taken != null) {
                storage.delete(taken);
            }
            if (linked) {
                DeckUrlLoader.store(deck);
            } else {
                storage.add(deck);
            }
        }
        return lobby.adopt(linked ? DeckCatalog.LINKED : DeckCatalog.MINE, "", deck);
    }

    private String saveOnDevice(final BrowserChannel channel, final Deck deck, final Check check, final ImportCommit c) {
        String id = null;
        for (final Map.Entry<String, OnDevice> e : device.entrySet()) {
            if (DeckStore.sameFile(e.getValue().deck().getName(), deck.getName())) {
                id = e.getKey();
            }
        }
        if (id != null && c.clash() == null) {
            channel.send(new NameTaken(deck.getName()));
            return null;
        }
        if (id != null && c.clash() == FromBrowser.Clash.keep) {
            deck.setName(freeDeviceName(deck.getName()));
            id = null;
        }
        final String kept = id == null ? UUID.randomUUID().toString() : id;
        sendDeviceDeck(kept, String.join("\n", DeckSerializer.serializeDeck(deck)), check.format());
        return lobby.adopt(DeckCatalog.DEVICE, kept, deck);
    }

    private String freeDeviceName(final String wanted) {
        String candidate = wanted;
        for (int n = 2; ; n++) {
            final String tried = candidate;
            if (device.values().stream().noneMatch(d -> DeckStore.sameFile(d.deck().getName(), tried))) {
                return candidate;
            }
            candidate = wanted + " (" + n + ")";
        }
    }

    /** Keeps a guest's deck in the lobby's list and sends it to the guest's browser, which stores it. */
    private void sendDeviceDeck(final String id, final String text, final GameType format) {
        if (text == null) {
            device.remove(id);
        } else {
            device.put(id, new OnDevice(parse(text), format));
        }
        final BrowserChannel b = channelFor.get();
        if (b != null) {
            b.send(new DeviceDeck(id, text, format.name()));
        }
    }

    /** Where to send a guest's deck, which the session sets as its browser comes and goes. */
    private final AtomicReference<BrowserChannel> channelFor = new AtomicReference<>();

    void attach(final BrowserChannel channel) {
        channelFor.set(channel);
    }

    private static DeviceDeck deviceDeck(final String id, final Deck deck, final GameType format) {
        return new DeviceDeck(id, String.join("\n", DeckSerializer.serializeDeck(deck)), format.name());
    }

    private void keepDeviceDecks(final List<DeviceDeckText> decks) {
        for (final DeviceDeckText d : decks) {
            try {
                device.put(d.id(), new OnDevice(parse(d.text()), gameType(d.format())));
            } catch (final RuntimeException e) {
                Logger.warn(e, "A deck kept in a guest's browser could not be read");
            }
        }
    }

    private static Deck parse(final String text) {
        return DeckSerializer.fromSections(FileSection.parseSections(Arrays.asList(text.split("\r?\n"))));
    }

    private static Map<String, Integer> counts(final List<CountedName> names) {
        final Map<String, Integer> out = new LinkedHashMap<>();
        if (names != null) {
            names.forEach(n -> out.merge(n.name(), n.count(), Integer::sum));
        }
        return out;
    }

    private static Check check(final String format, final String cardPool, final boolean unrestricted) {
        if (unrestricted) {
            return Check.none();
        }
        final GameType type = gameType(format);
        final GameFormat pool = type == GameType.Constructed && cardPool != null ? FModel.getFormats().getFormat(cardPool) : null;
        return Check.of(type, pool);
    }

    private static GameType gameType(final String name) {
        try {
            return GameType.valueOf(name);
        } catch (final IllegalArgumentException | NullPointerException e) {
            return GameType.Constructed;
        }
    }
}
