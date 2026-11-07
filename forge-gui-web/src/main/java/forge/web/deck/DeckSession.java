package forge.web.deck;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.GameType;
import forge.util.FileSection;
import forge.util.IHasForgeLog;
import forge.web.WebGuiBase;
import forge.web.deck.DeckCatalog.OnDevice;
import forge.web.lobby.Lobby;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.Commands;
import forge.web.protocol.FromBrowser.*;
import forge.web.protocol.ToBrowser.*;
import org.apache.commons.lang3.EnumUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** One browser's deck editor and importer. It also holds the decks that a guest's browser stores. */
public final class DeckSession implements IHasForgeLog {
    public static final Commands<DeckSession> COMMANDS = new Commands<DeckSession>()
            .on(BrowseFormat.class, (d, ch, b) -> {
                if (d.host.getAsBoolean() && !d.atTable.getAsBoolean()) {
                    d.lobby.setBrowseFormat(gameType(b.format()));
                    d.lobby.queueDecks(ch, d::inTurn);
                }
            })
            .on(DeviceDecks.class, (d, ch, dd) -> {
                if (!d.host.getAsBoolean()) {
                    d.keepDeviceDecks(dd.decks());
                    d.lobby.queueDecks(ch, d::inTurn);
                }
            })
            // The stars belong to the host and are stored where desktop stores them
            .on(DeckFavourite.class, (d, ch, star) -> {
                if (d.host.getAsBoolean()) {
                    d.lobby.catalog().setFavourite(star.key(), star.on());
                    d.lobby.queueDecks(ch, d::inTurn);
                }
            })
    private final Lobby lobby;
    private final WebGuiBase ui;
    private final BooleanSupplier host;
    private final BooleanSupplier atTable;
    private final Executor work = WebGuiBase.worker("WebDecks");
    /** A guest's decks, keyed by the id its browser stores each one under. The lobby lists them too. */
    private final Map<String, OnDevice> device;

    /** Where to send a guest's deck. The session updates it as the browser connects and disconnects. */
    private final AtomicReference<BrowserChannel> channelFor = new AtomicReference<>();

    public DeckSession(final Lobby lobby, final WebGuiBase ui, final BooleanSupplier host, final BooleanSupplier atTable,
            final Map<String, OnDevice> device) {
        this.lobby = lobby;
        this.ui = ui;
        this.host = host;
        this.atTable = atTable;
        this.device = device;
    }

    public void onCommand(final BrowserChannel channel, final Record command) {
        COMMANDS.run(this, channel, command);
    }

    /**
     * Runs a deck list, search or catalogue request on this browser's deck thread, in the order requested. The socket threads
     * also serve every picture, so a list that takes a second to build would block them.
     */
    public void inTurn(final Runnable job) {
        work.execute(job);
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
            final List<String> onDevice = device.values().stream().map(d -> d.deck().getName()).toList();
            deck.setName(DeckStore.freeName(onDevice, deck.getName(), null));
            id = null;
        }
        final String kept = id == null ? UUID.randomUUID().toString() : id;
        sendDeviceDeck(kept, text(deck), check.format());
        return lobby.catalog().adopt(DeckCatalog.DEVICE, kept, deck);
    }

    /** Stores a guest's deck in the lobby's list and sends it to the guest's browser, which saves it. */
    private void sendDeviceDeck(final String id, final String text, final GameType format) {
        if (text == null) {
            device.remove(id);
        } else {
            device.put(id, new OnDevice(parse(text), format));
        }
        // A pool that the browser has not yet confirmed is resent in its current form, so a deck built from it is not lost
        final EventPool p = pendingPool;
        if (p != null && p.id().equals(id)) {
            pendingPool = text == null ? null : new EventPool(id, parse(text), format);
        }
        final BrowserChannel b = channelFor.get();
        if (b != null) {
            b.send(new DeviceDeck(id, text, format.name()));
        }
    }

    public void attach(final BrowserChannel channel) {
        channelFor.set(channel);
    }

    private static DeviceDeck deviceDeck(final String id, final Deck deck, final GameType format) {
        return new DeviceDeck(id, text(deck), format.name());
    }

    private void keepDeviceDecks(final List<DeviceDeckText> decks) {
        final EventPool p = pendingPool;
        if (p != null && decks.stream().anyMatch(d -> p.id().equals(d.id()))) {
            pendingPool = null;
        } else if (p != null) {
            sendDeviceDeck(p.id(), text(p.deck()), p.format());
        }
        for (final DeviceDeckText d : decks) {
            try {
                // Text with no deck sections, as a hand-edited backup can hold, parses to no deck
                final Deck deck = parse(d.text());
                if (deck == null) {
                    netLog.warn("A deck kept in a guest's browser is not a deck: {}", d.id());
                    continue;
                }
                device.put(d.id(), new OnDevice(deck, gameType(d.format())));
            } catch (final RuntimeException e) {
                netLog.warn(e, "A deck kept in a guest's browser could not be read");
            }
        }
    }

    /** A deck as the text of a .dck file. The serializer ends some lines with Windows line endings and some with plain ones, so all are made plain. */
    static String text(final Deck deck) {
        return String.join("\n", DeckSerializer.serializeDeck(deck)).replace("\r\n", "\n");
    }

    private static Deck parse(final String text) {
        return DeckSerializer.fromSections(FileSection.parseSections(Arrays.asList(text.split("\r?\n"))));
    }

    private static GameType gameType(final String name) {
        return EnumUtils.getEnum(GameType.class, name, GameType.Constructed);
    }

}
