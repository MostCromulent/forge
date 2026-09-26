package forge.web;

import com.google.gson.JsonObject;
import forge.web.FromBrowser.AskDeckDetails;
import forge.web.FromBrowser.AskPrintings;
import forge.web.FromBrowser.HostChoiceAnswer;
import forge.web.FromBrowser.Ready;
import forge.web.FromBrowser.Say;
import forge.web.FromBrowser.SearchCards;
import forge.web.FromBrowser.SeatCommand;
import forge.web.FromBrowser.AskExtraChoices;
import forge.web.FromBrowser.SetArchenemy;
import forge.web.FromBrowser.SetFormat;
import forge.web.FromBrowser.SetSeatExtra;
import forge.web.FromBrowser.SetVariant;
import forge.web.FromBrowser.SetCardPool;
import forge.web.FromBrowser.SetName;
import forge.web.FromBrowser.SetSeat;
import forge.web.FromBrowser.SetSetting;
import forge.web.FromBrowser.SetStops;
import forge.web.FromBrowser.SleeveArt;
import forge.web.FromBrowser.Start;
import forge.web.FromBrowser.DraftPick;
import forge.web.FromBrowser.DraftSave;
import forge.web.FromBrowser.DraftStart;
import forge.web.FromBrowser.LimitedOpen;
import forge.web.FromBrowser.PoolDelete;
import forge.web.FromBrowser.PoolEdit;
import forge.web.FromBrowser.PoolOpen;
import forge.web.FromBrowser.PoolPlay;
import forge.web.FromBrowser.SealedCreate;
import forge.web.FromBrowser.BenchSeat;
import forge.web.FromBrowser.EventDecksOnly;
import forge.web.FromBrowser.EventHostAgain;
import forge.web.FromBrowser.EventSetup;
import forge.web.FromBrowser.SetLimited;
import forge.web.ToBrowser.Addresses;
import forge.web.ToBrowser.DraftState;
import forge.web.ToBrowser.CardSearch;
import forge.web.ToBrowser.ChatLine;
import forge.web.ToBrowser.ErrorMessage;
import forge.web.ToBrowser.Hello;
import forge.web.ToBrowser.LimitedResult;
import forge.web.ToBrowser.NameTaken;
import forge.web.ToBrowser.Notice;
import forge.web.ToBrowser.Printings;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckGroup;
import forge.game.GameType;
import forge.game.GameView;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.GauntletMini;
import forge.gamemodes.match.GameLobby;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.NetworkEventView;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.util.storage.IStorage;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * One browser: its start page, its seat and its match. The host's session also owns shutting the process down.
 *
 * <p>Where the browser is lives in one {@link Stage}, and every move between stages goes through {@link #move}, which
 * closes whatever the stage being left held and tells the browser where it is now. A reconnecting browser is put
 * back by the same stage, so a reload and a first visit cannot disagree about what the page should show.</p>
 */
public final class WebSession {
    /** Where the browser is. Each stage holds what exists there and nothing else. */
    sealed interface Stage permits Menu, Opening, Setup, Event, Playing { }

    /** The start page; for a guest, waiting for the host to open a game. */
    record Menu() implements Stage { }

    /**
     * Taking a seat: the host opening a game of its own, or a guest joining the host's. The table is built while the
     * browser waits, and nothing may change it until the seat is taken.
     */
    record Opening() implements Stage { }

    /** Match setup, at a seat whose GUI the match will be played through. Invited means others can join. */
    record Setup(WebGuiGame gui, boolean invited) implements Stage { }

    /** The Limited pages for kind (sealed or draft): the saved pools, the setup form, and the opponents screen of pool, when it is not null. */
    record Event(String kind, String pool) implements Stage {
        IStorage<DeckGroup> storage() {
            return OfflineEvents.storage(kind);
        }

        GameType type() {
            return "draft".equals(kind) ? GameType.Draft : GameType.Sealed;
        }
    }

    /** In a match, playing it or watching the computer play it. back is where leaving it returns to: a pool, or null for the table. */
    record Playing(WebGuiGame gui, boolean invited, boolean spectating, Event back) implements Stage { }

    private static final int CARD_SEARCH_LIMIT = 60;
    /** Chat is shown to every player, so one message is kept to a length a chat box can hold. */
    private static final int MOST_CHAT_CHARS = 500;
    /** Long enough for any name a player types, short enough to fit on a seat plate. */
    static final int MAX_NAME_LENGTH = 24;
    private final Lobby lobby;
    /** The deck editor and importer, which sit beside the menu or the table rather than being a stage of their own. */
    private final DeckSession decks;
    private final WebGuiBase ui;
    private final WebSessions sessions;
    /** True for the browser that claimed the host's seat, which is the only one that may set the table. */
    private volatile boolean isHost;
    /** Whether this browser came in on the host's link, which is what lets it claim the host's seat. */
    private final boolean mayHost;
    private final LocalGame local = new LocalGame();
    private final Runnable onQuit;
    private volatile BrowserChannel browser;
    private volatile Stage stage = new Menu();
    /** This player's settings: a guest's own, or the host's, which are Forge's preferences. */
    private volatile PlayerSettings settings = PlayerSettings.fresh();
    /** The name this browser chose to play under; null until it has chosen one. */
    private volatile String name;
    /** The face chosen beside the name, before any seat exists to carry it; null until it has chosen one. */
    private volatile Integer avatar;
    /** Whether a sealed pool is being opened, which takes seconds and may wait on a question to the player. */
    private final AtomicBoolean openingPacks = new AtomicBoolean();
    /** The offline draft being played, while there is one. */
    private volatile OfflineDraft offlineDraft;
    /** This seat's part in the table's draft or sealed event; a new one comes with each table. */
    private volatile OnlineDraft onlineDraft;
    /** Whether the draft host was told this seat's player left, so it is told when the browser is back. */
    private volatile boolean seatReportedGone;
    /** A match seats at most four players, so several opponents at once are at most three. */
    private static final int MOST_OPPONENTS = 3;
    /** Whether this session is playing a limited gauntlet, and the result of its last game. */
    private volatile boolean gauntletRunning;
    private volatile LimitedResult lastResult;

    WebSession(final WebGuiBase ui, final WebSessions sessions, final Runnable onQuit, final boolean mayHost) {
        this.mayHost = mayHost;
        this.ui = ui;
        this.sessions = sessions;
        final Map<String, DeckCatalog.OnDevice> device = new ConcurrentHashMap<>();
        this.lobby = new Lobby(local, () -> !isHost, device);
        this.decks = new DeckSession(lobby, ui, () -> isHost, () -> stage instanceof Setup, device);
        this.onQuit = onQuit;
    }

    /** Takes the host's seat. Host dialogs belong to whoever holds it, so they are pointed here. */
    void becomeHost() {
        isHost = true;
        settings = PlayerSettings.saved();
        ui.setNoticeSink(notice -> {
            final BrowserChannel b = browser;
            if (b != null) {
                b.send(notice);
            }
        });
    }

    boolean isHost() {
        return isHost;
    }

    boolean mayHost() {
        return mayHost;
    }

    /** Whether this session is holding a game open, which is what keeps the host's seat reserved. */
    boolean hasGame() {
        final Stage now = stage;
        return now instanceof Setup || now instanceof Event || now instanceof Playing;
    }

    /**
     * Moves from one stage to the next, if the browser is still where the caller found it: a guest's seat can
     * vanish while it is being taken, and a match can end under a lobby being opened. Closes the GUI the old stage
     * held unless the new one carries it on, and tells the browser where it now is.
     */
    private synchronized boolean move(final Stage from, final Stage next) {
        if (stage != from) {
            return false;
        }
        stage = next;
        final WebGuiGame old = guiOf(from);
        if (old != null && old != guiOf(next)) {
            old.close();
        }
        final BrowserChannel b = browser;
        if (b != null) {
            b.send(hello());
        }
        // Every stage is something the panel that says who is here reports: waiting, joining, at a table, playing
        sessions.announcePresence();
        return true;
    }

    private static WebGuiGame guiOf(final Stage stage) {
        if (stage instanceof Setup s) {
            return s.gui();
        }
        return stage instanceof Playing p ? p.gui() : null;
    }

    /** The seat came free or was taken, so a browser waiting on it is told again what it may do. */
    void hostSeatChanged() {
        final BrowserChannel b = browser;
        if (b != null && !isHost) {
            b.send(hello());
        }
    }

    /**
     * The name this session plays under. Every browser reaches Forge through the one set of preferences, so the
     * saved player name is the host's: a host that has not chosen one plays under it, and nobody else ever does.
     */
    String playerName() {
        final String chosen = name;
        return chosen != null || !isHost ? chosen : FModel.getPreferences().getPref(FPref.PLAYER_NAME);
    }

    /** The names the computer plays under at this session's table. */
    List<String> computerNames() {
        return lobby.computerNames();
    }

    /** Whether a browser is attached to this session right now. */
    boolean attached() {
        return browser != null;
    }

    /** Whether this session holds the host's seat. */
    boolean hosting() {
        return isHost;
    }

    /** The face chosen on the start page; the first avatar until one is. */
    int avatarIndex() {
        final Integer face = avatar;
        return face == null ? 0 : face;
    }

    /** What this session is doing, in the words the panel that says who is here uses. */
    String doing() {
        final Stage now = stage;
        if (now instanceof Playing p) {
            return p.spectating() ? "watching" : "playing";
        }
        if (now instanceof Setup) {
            return "table";
        }
        return now instanceof Opening ? "joining" : "waiting";
    }

    /** Sends one message to this session's browser, if it has one. */
    void tell(final Record message) {
        final BrowserChannel b = browser;
        if (b != null) {
            b.send(message);
        }
    }

    /** The loopback port guests take a seat on. */
    int gamePort() {
        return local.isHost() ? local.port() : -1;
    }

    /** A browser arrived, first or again. It is put back where its stage says it is. */
    synchronized void connected(final BrowserChannel channel) {
        final BrowserChannel previous = browser;
        browser = channel;
        decks.attach(channel);
        final Stage now = stage;
        if (now instanceof Playing p && previous != null) {
            p.gui().detach(previous);
        }
        channel.send(hello());
        sessions.greet(this);
        if (isHost) {
            ui.hostRequests().replay(channel::send);
        }
        decks.reconnected(channel);
        if (now instanceof Playing p) {
            p.gui().attach(channel);
            final LimitedResult result = lastResult;
            if (result != null) {
                channel.send(result);
            }
        } else if (now instanceof Setup) {
            // Match setup is drawn from the table, which only these messages describe
            channel.send(lobby.decks());
            channel.send(lobby.state());
            if (isHost && lobby.settingUpEvent()) {
                sendEventOptions(channel);
            }
            final OnlineDraft draft = onlineDraft;
            if (draft != null && draft.drafting()) {
                channel.send(draft.latest());
            }
            if (seatReportedGone) {
                seatReportedGone = false;
                final ServerGameLobby table = sessions.hostLobby();
                if (table != null && table.getDraftHost() != null && podSeat() >= 0) {
                    table.getDraftHost().onSeatReconnected(podSeat());
                }
                sessions.seatsChanged();
            }
        } else if (now instanceof Event) {
            sendLimited(channel);
            final OfflineDraft draft = offlineDraft;
            if (draft != null && draft.latest() != null) {
                channel.send(draft.latest());
            }
        } else if (now instanceof Menu) {
            // A guest that arrives while a game is already open takes a seat without being asked, once it has a name
            joinHostGame();
        }
    }

    synchronized void disconnected(final BrowserChannel channel) {
        if (browser != channel) {
            return;
        }
        browser = null;
        decks.attach(null);
        if (stage instanceof Playing p) {
            p.gui().detach(channel);
        }
    }

    void onMessage(final BrowserChannel channel, final JsonObject msg) {
        switch (msg.get("t").getAsString()) {
            case "decks" -> channel.send(lobby.decks());
            case "setName" -> {
                final SetName chosen = Wire.decode(msg, SetName.class);
                rename(channel, chosen.name(), chosen.avatar());
            }
            // A join that found no seat leaves the browser on the waiting page, which offers to try again
            case "join" -> joinHostGame();
            case "claimHost" -> {
                if (!sessions.claimHost(this)) {
                    channel.send(error("Someone else is already hosting."));
                }
                channel.send(hello());
            }
            // Opening a game connects a client to a server, which the host UI thread owns
            case "lobby" -> ui.invokeInEdtLater(() -> openLobby(channel, false));
            case "invite" -> ui.invokeInEdtLater(() -> openLobby(channel, true));
            case "leaveLobby" -> ui.invokeInEdtLater(() -> {
                final Stage now = stage;
                if (now instanceof Setup && move(now, new Menu())) {
                    local.close();
                    if (isHost) {
                        sessions.hostGameClosed();
                    }
                }
            });
            // The table can be changed only while it is set up: not while it is being built, and not once it is played
            case "ready", "openSeat", "aiSeat", "removeSeat", "setFormat", "setCardPool", "setVariant", "setArchenemy", "setSeatExtra",
                    "setPlayerCount", "setSeat", "sleeveArt" -> {
                if (stage instanceof Setup) {
                    onSetup(channel, msg);
                }
            }
            case "setLimited", "eventSetup", "eventStart", "benchSeat", "eventDecksOnly", "eventHostAgain" -> {
                if (stage instanceof Setup) {
                    onEvent(channel, msg);
                }
            }
            case "chat" -> {
                final String text = Wire.decode(msg, Say.class).text();
                if (text != null && !text.isBlank()) {
                    sessions.say(this, text.length() > MOST_CHAT_CHARS ? text.substring(0, MOST_CHAT_CHARS) : text);
                }
            }
            case "extraChoices" -> {
                final AskExtraChoices ask = Wire.decode(msg, AskExtraChoices.class);
                final ToBrowser.ExtraChoices choices = lobby.extraChoices(ask.index(), ask.section());
                if (choices != null) {
                    channel.send(choices);
                }
            }
            case "deckDetails" -> {
                final ToBrowser.DeckDetailsMessage details = lobby.deckDetails(Wire.decode(msg, AskDeckDetails.class).key());
                if (details != null) {
                    channel.send(details);
                }
            }
            // One set of host questions serves the process, so only the host's browser may answer them
            case "hostChoice" -> {
                if (isHost) {
                    final HostChoiceAnswer answer = Wire.decode(msg, HostChoiceAnswer.class);
                    ui.hostRequests().answer(answer.id(), answer.value());
                }
            }
            // Core asks which category through a host question, and blocks on it, so not on the socket thread
            case "netDecks" -> {
                if (isHost) {
                    ui.runBackgroundTask("Net decks", () -> channel.send(lobby.loadNetDecks()));
                }
            }
            // The links are the host's to hand out. Finding the external address is a web request, so it cannot run on
            // the socket thread.
            case "addresses" -> {
                if (isHost) {
                    ui.runBackgroundTask("Addresses", () -> channel.send(new Addresses(sessions.inviteUrls())));
                }
            }
            case "cardPoolDetails" -> channel.send(Lobby.cardPoolDetails());
            case "cardSearch" -> channel.send(new CardSearch(
                    DeckCatalog.searchCardNames(Wire.decode(msg, SearchCards.class).query(), CARD_SEARCH_LIMIT)));
            case "printings" -> {
                final AskPrintings ask = Wire.decode(msg, AskPrintings.class);
                channel.send(new Printings(ask.name(), DeckCatalog.printings(ask.name(),
                        ask.cardPool() == null ? null : FModel.getFormats().getFormat(ask.cardPool()))));
            }
            // A match takes the whole page, so nothing about decks is done during one
            case "browseFormat", "editorOpen", "editorClose", "editorUndo", "editorEdit", "editorRename", "editorCheck",
                    "editorDeck", "catalogue", "importRead", "importFetch", "importCommit", "deviceDecks" -> {
                if (!(stage instanceof Playing)) {
                    decks.onMessage(channel, msg);
                }
            }
            // LocalGame runs on the host UI thread, so host-side dialogs during setup never block a web server thread
            case "start" -> {
                final Start start = Wire.decode(msg, Start.class);
                ui.invokeInEdtLater(() -> start(channel, start));
            }
            case "leave" -> ui.invokeInEdtLater(this::leave);
            case "limitedOpen" -> {
                final LimitedOpen open = Wire.decode(msg, LimitedOpen.class);
                final String kind = "draft".equals(open.kind()) ? "draft" : "sealed";
                final Stage now = stage;
                if (isHost && ((now instanceof Event e && e.kind().equals(kind) && !open.resume())
                        || ((now instanceof Menu || now instanceof Event)
                            && move(now, new Event(kind, open.resume() ? OfflineEvents.latest(kind) : null))))) {
                    sendLimited(channel);
                }
            }
            case "limitedLeave", "poolClose", "poolOpen" -> {
                final Stage now = stage;
                if (now instanceof Event e) {
                    final String type = msg.get("t").getAsString();
                    move(now, "limitedLeave".equals(type) ? new Menu()
                            : new Event(e.kind(), "poolOpen".equals(type) ? Wire.decode(msg, PoolOpen.class).name() : null));
                }
            }
            case "poolEdit" -> editPool(channel, Wire.decode(msg, PoolEdit.class).name());
            case "poolDelete" -> deletePool(channel, Wire.decode(msg, PoolDelete.class).name());
            case "sealedCreate" -> createSealed(channel, Wire.decode(msg, SealedCreate.class));
            case "draftStart" -> startDraft(channel, Wire.decode(msg, DraftStart.class));
            case "draftPick" -> {
                final DraftPick pick = Wire.decode(msg, DraftPick.class);
                final OfflineDraft draft = offlineDraft;
                final OnlineDraft online = onlineDraft;
                if (draft != null) {
                    draft.pick(pick.step(), pick.index());
                } else if (online != null && stage instanceof Setup) {
                    online.pick(pick.step(), pick.index());
                }
            }
            case "draftSave" -> saveDraft(channel, Wire.decode(msg, DraftSave.class));
            case "draftDiscard" -> endDraft();
            // The gauntlet starts its rounds itself, which, like any match, happens on the host UI thread
            case "gauntletNext" -> ui.invokeInEdtLater(() -> {
                final LimitedResult result = lastResult;
                if (gauntletRunning && result != null && result.nextRound()) {
                    lastResult = null;
                    FModel.getGauntletMini().nextRound();
                }
            });
            case "gauntletRestart" -> ui.invokeInEdtLater(() -> {
                if (gauntletRunning) {
                    lastResult = null;
                    FModel.getGauntletMini().restartRound();
                }
            });
            // A match is started on the host UI thread, as a table's is
            case "poolPlay" -> {
                final PoolPlay play = Wire.decode(msg, PoolPlay.class);
                ui.invokeInEdtLater(() -> playPool(channel, play));
            }
            // A cheat asks its questions as the game does and waits for the answers, so it runs on a thread of its own
            case "dev" -> {
                if (isHost && stage instanceof Playing) {
                    final FromBrowser.Dev dev = Wire.decode(msg, FromBrowser.Dev.class);
                    ui.runBackgroundTask("Dev mode", () -> DevMode.run(local, dev, channel));
                }
            }
            // Quitting stops the process every browser is served from, so it is the host's to do
            case "quit" -> {
                if (isHost) {
                    ui.invokeInEdtLater(this::quit);
                }
            }
            // A setting is the player's, whatever the browser is doing: set before a match, it is what the match is seeded
            // with; during one, the game is told as well
            case "setSetting", "setStops" -> {
                if (stage instanceof Playing p) {
                    p.gui().onBrowserMessage(msg);
                } else if ("setSetting".equals(msg.get("t").getAsString())) {
                    final SetSetting setting = Wire.decode(msg, SetSetting.class);
                    WebSettings.set(settings, null, setting.key(), setting.value());
                } else {
                    final SetStops stops = Wire.decode(msg, SetStops.class);
                    WebSettings.setStops(settings, stops.mine(), stops.phases());
                }
            }
            default -> {
                if (stage instanceof Playing p) {
                    p.gui().onBrowserMessage(msg);
                }
            }
        }
    }

    /** A change to the table, made while it is being set up. */
    private void onSetup(final BrowserChannel channel, final JsonObject msg) {
        switch (msg.get("t").getAsString()) {
            case "ready" -> lobby.setReady(Wire.decode(msg, Ready.class).ready());
            case "openSeat", "aiSeat", "removeSeat" -> {
                final SeatCommand seat = Wire.decode(msg, SeatCommand.class);
                switch (seat.t()) {
                    case openSeat -> lobby.openSeat(seat.index());
                    case aiSeat -> lobby.aiSeat(seat.index());
                    case removeSeat -> lobby.removeSeat(seat.index());
                }
            }
            case "setFormat" -> {
                lobby.setFormat(Wire.decode(msg, SetFormat.class).format());
                relistDecks(channel);
            }
            case "setCardPool" -> {
                lobby.setCardPool(Wire.decode(msg, SetCardPool.class).cardPool());
                relistDecks(channel);
            }
            case "setVariant" -> {
                final SetVariant variant = Wire.decode(msg, SetVariant.class);
                lobby.setVariant(variant.variant(), variant.on());
                relistDecks(channel);
            }
            case "setArchenemy" -> lobby.setArchenemy(Wire.decode(msg, SetArchenemy.class).index());
            case "setSeatExtra" -> {
                final SetSeatExtra extra = Wire.decode(msg, SetSeatExtra.class);
                lobby.setSeatExtra(extra.index(), extra.section(), extra.choice());
            }
            case "setPlayerCount" -> lobby.setPlayerCount(Wire.decode(msg, FromBrowser.SetPlayerCount.class).count());
            case "setSeat" -> applySeat(channel, Wire.decode(msg, SetSeat.class));
            case "sleeveArt" -> {
                final SleeveArt art = Wire.decode(msg, SleeveArt.class);
                lobby.setSleeveArt(art.index(), art.key(), art.offset());
            }
            default -> {
                return;
            }
        }
        channel.send(lobby.state());
    }

    /** A change to the table's draft or sealed event. Everything but the finder's filter is the host's to do. */
    private void onEvent(final BrowserChannel channel, final JsonObject msg) {
        final String type = msg.get("t").getAsString();
        if (!isHost) {
            return;
        }
        switch (type) {
            case "eventDecksOnly" -> {
                lobby.setEventDecksOnly(Wire.decode(msg, EventDecksOnly.class).on());
                relistDecks(channel);
            }
            case "eventHostAgain" -> {
                reportProblem(channel, lobby.hostAgain(Wire.decode(msg, EventHostAgain.class).eventId()));
                relistDecks(channel);
            }
            case "setLimited" -> {
                final String kind = Wire.decode(msg, SetLimited.class).kind();
                final String problem = lobby.setLimited(kind == null ? null : "sealed".equals(kind) ? "sealed" : "draft");
                if (problem != null) {
                    channel.send(error(problem));
                } else if (kind != null) {
                    sendEventOptions(channel);
                }
                relistDecks(channel);
            }
            case "benchSeat" -> {
                final BenchSeat bench = Wire.decode(msg, BenchSeat.class);
                lobby.benchSeat(bench.index(), bench.benched());
            }
            // Building a product can wait on a web site, and dealing packs opens every pool, so neither runs here
            case "eventSetup" -> {
                final EventSetup setup = Wire.decode(msg, EventSetup.class);
                ui.runBackgroundTask("Event", () -> reportProblem(channel, lobby.setUpEvent(setup)));
            }
            case "eventStart" -> {
                if (offlineDraft != null) {
                    channel.send(error("Finish or discard the offline draft first."));
                    return;
                }
                ui.runBackgroundTask("Event", () -> reportProblem(channel, lobby.startEvent()));
            }
            default -> {
                return;
            }
        }
        channel.send(lobby.state());
    }

    private void reportProblem(final BrowserChannel channel, final String problem) {
        if (problem != null) {
            channel.send(error(problem));
        }
    }

    /** The event setup form offers what the offline one does. Reading the lists touches files, so not on the socket thread. */
    private void sendEventOptions(final BrowserChannel channel) {
        ui.runBackgroundTask("Limited", () -> channel.send(OfflineEvents.options()));
    }

    /** Opens match setup: a game of this machine's own, or a seat in the host's. Tells the browser how it went. */
    private void openLobby(final BrowserChannel channel, final boolean invite) {
        if (!isHost) {
            joinHostGame();
            return;
        }
        final Stage from = stage;
        final Opening opening = new Opening();
        // Leaving the old stage closes the match or table it held before the new one is built
        if (from instanceof Opening || !move(from, opening)) {
            return;
        }
        // A new table replaces the old one, so anyone seated at it is told it has gone before it is taken down
        if (from instanceof Setup || from instanceof Playing) {
            sessions.hostGameClosed();
        }
        final WebGuiGame gui = new WebGuiGame(settings);
        lobby.forget();
        lobby.setShareable(invite);
        newOnlineDraft(gui);
        try {
            local.openHost(playerName(), gui, this::lobbyChanged, this::chatted);
            if (invite) {
                // Somebody has to be able to sit down, so the second seat is left open rather than filled
                lobby.openSeat(1);
            }
        } catch (final RuntimeException e) {
            Logger.error(e, "Could not open the lobby");
            gui.close();
            move(opening, new Menu());
            // The move's hello clears the browser's last error, so the reason has to follow it
            channel.send(error("Could not open the lobby: " + e.getMessage()));
            return;
        }
        if (!move(opening, new Setup(gui, invite))) {
            gui.close();
            return;
        }
        applyChosenAvatar();
        channel.send(lobby.decks());
        channel.send(lobby.state());
        sessions.hostGameOpened();
    }

    /** Takes a seat in the host's game. Runs off the host UI thread, because taking one waits on the server. */
    void joinHostGame() {
        final int port = sessions.hostPort();
        final String seatName = name;
        final Opening joining = new Opening();
        final Stage from = stage;
        // Only one seat is taken at a time: the stage says a join is under way until it lands or fails
        if (isHost || !(from instanceof Menu) || port < 0 || browser == null || seatName == null
                || !move(from, joining)) {
            return;
        }
        ui.runBackgroundTask("Joining", () -> {
            lobby.forget();
            final WebGuiGame gui = new WebGuiGame(settings);
            gui.whenOpened(() -> guestMatchOpened(gui));
            newOnlineDraft(gui);
            try {
                local.openGuest(seatName, gui, port, this::lobbyChanged, this::chatted, this::gameGone);
            } catch (final RuntimeException e) {
                Logger.error(e, "Could not take a seat");
                gui.close();
                move(joining, new Menu());
                final BrowserChannel b = browser;
                if (b != null) {
                    // The waiting card supplies "Could not take a seat", so this is the reason alone
                    b.send(error(e.getMessage()));
                }
                return;
            }
            if (!move(joining, new Setup(gui, true))) {
                // The game went while the seat was being taken
                gui.close();
                local.close();
                return;
            }
            applyChosenAvatar();
            final BrowserChannel b = browser;
            if (b != null) {
                b.send(lobby.decks());
                b.send(lobby.state());
            }
        });
    }

    /**
     * Makes this table's online draft and hands it to the seat's client. Its events are handled on the client's
     * dispatch executor, one at a time, as the match's are.
     */
    private void newOnlineDraft(final WebGuiGame gui) {
        seatReportedGone = false;
        toldDrafting = false;
        // An event from an earlier table's draft can still be on its way, and it is not this table's
        final OnlineDraft[] self = new OnlineDraft[1];
        final OnlineDraft draft = new OnlineDraft(gui.dispatchExecutor(), this::eventView, this::podSeat, this::seatHeld,
                local::sendToHost, state -> {
                    if (onlineDraft == self[0]) {
                        showDraft(state);
                    }
                }, (eventId, pool) -> {
                    if (onlineDraft == self[0]) {
                        poolArrived(eventId, pool);
                    }
                });
        self[0] = draft;
        onlineDraft = draft;
        local.setDraftHandler(draft);
    }

    /** The table's event as this seat's lobby last heard it. */
    private NetworkEventView eventView() {
        final GameLobby table = local.hostedLobby() != null ? local.hostedLobby() : local.clientLobby();
        return table == null || table.getData() == null ? null : table.getData().getEventView();
    }

    ServerGameLobby hostedLobby() {
        return local.hostedLobby();
    }

    /** This seat's place in the draft pod, or -1 before the pod is seated. */
    private int podSeat() {
        final ServerGameLobby table = sessions.hostLobby();
        return table == null || local.webSeat() < 0 ? -1 : table.findSeatForLobbySlot(local.webSeat());
    }

    /** Whether a pod seat's player has gone. Every seat's session lives in the host's process, where the draft runs. */
    private boolean seatHeld(final int seat) {
        final ServerGameLobby table = sessions.hostLobby();
        return table != null && table.getDraftHost() != null && table.getDraftHost().isSeatHeld(seat);
    }

    private volatile boolean toldDrafting;

    private void showDraft(final DraftState state) {
        // The first pack takes the browser to the draft, which the hello says it is in
        if (!toldDrafting) {
            toldDrafting = true;
            tell(hello());
        }
        tell(state);
    }

    /**
     * The event's pool arrived. The host keeps it among the event decks and sets the table to play them, as desktop's
     * host does; a guest's is kept in its browser. Either way it opens in the limited editor.
     */
    private void poolArrived(final String eventId, final Deck pool) {
        final NetworkEventView event = eventView();
        final GameType type = event != null && event.getFormat() == EventFormat.SEALED ? GameType.Sealed : GameType.Draft;
        final BrowserChannel b = browser;
        if (isHost) {
            final IStorage<Deck> eventDecks = FModel.getDecks().getNetworkEventDecks();
            final Deck kept;
            synchronized (DeckCatalog.DECKS) {
                // Two events of one product on one day share a name, and the older pool is not the newer one's to replace
                String name = pool.getName();
                for (int n = 2; eventDecks.contains(name); n++) {
                    name = pool.getName() + " (" + n + ")";
                }
                kept = new Deck(pool, name);
                eventDecks.add(kept);
            }
            final ServerGameLobby table = local.hostedLobby();
            if (table != null) {
                table.selectEventForMatch(eventId, true);
            }
            decks.openEventPool(kept, type, eventDecks, null, b);
        } else {
            decks.openEventPool(pool, type, null, "event-" + eventId, b);
        }
        tell(hello());
    }

    /** A pod seat's player went or came back, so this seat's dial shows it afresh. */
    void seatsChanged() {
        final OnlineDraft draft = onlineDraft;
        if (draft != null) {
            draft.refresh();
        }
    }

    /**
     * The browser has been gone a while. If this seat is drafting, the draft host is told its player left, as a closed
     * connection tells desktop's, and holds or picks for the seat until the browser is back.
     */
    void goneAWhile() {
        final OnlineDraft draft = onlineDraft;
        final ServerGameLobby table = sessions.hostLobby();
        final int seat = podSeat();
        if (browser != null || draft == null || !draft.drafting() || table == null || table.getDraftHost() == null || seat < 0) {
            return;
        }
        table.getDraftHost().onSeatDisconnected(seat);
        seatReportedGone = true;
        sessions.seatsChanged();
    }

    /** The host started the match, so this guest's browser follows its seat into the game. */
    private void guestMatchOpened(final WebGuiGame gui) {
        final Stage now = stage;
        if (now instanceof Setup s && s.gui() == gui && move(now, new Playing(gui, true, false, null))) {
            final BrowserChannel b = browser;
            if (b != null) {
                gui.attach(b);
            }
        }
    }

    /** The host's game ended under this guest, so its seat goes and its browser waits for the next one. */
    void gameGone() {
        final Stage now = stage;
        if (!(now instanceof Menu) && move(now, new Menu())) {
            local.close();
        }
    }

    /** A new format, card pool or variant can change which decks a seat may take, so the list goes out again. */
    private void relistDecks(final BrowserChannel to) {
        if (lobby.restrictionsChanged()) {
            to.send(lobby.decks());
        }
    }

    /** The table changed. The browser sees it only once it is set up; until then it is still being built. */
    private void lobbyChanged() {
        final BrowserChannel b = browser;
        if (b != null && stage instanceof Setup) {
            // A guest learns of the host's format or card pool only here, so its deck list is rebuilt here too
            relistDecks(b);
            b.send(lobby.state());
        }
    }

    private void chatted(final String from, final String text) {
        // A player's line reaches every browser through the server's own chat, so only netplay's announcements
        // of who came and went are worth passing on from the game
        if (from == null) {
            tell(new ChatLine(null, text));
        }
    }

    /**
     * Takes the name this browser asked for, unless another player already has it: two players of one name
     * cannot share a netplay game, which tells its clients apart by name. A guest waiting for a name takes its
     * seat as soon as it has one.
     */
    private void rename(final BrowserChannel channel, final String wanted, final Integer face) {
        final String problem = nameProblem(wanted);
        if (problem != null) {
            channel.send(error(problem));
            return;
        }
        name = wanted.trim();
        if (face != null) {
            avatar = face;
        }
        applyChosenAvatar();
        channel.send(hello());
        sessions.announcePresence();
        joinHostGame();
    }

    /** Puts the face chosen on the start page on this session's seat, once it has one. */
    private void applyChosenAvatar() {
        final Integer face = avatar;
        final int seat = local.webSeat();
        if (face != null && seat >= 0 && stage instanceof Setup) {
            lobby.setAvatar(seat, face);
        }
    }

    /** Why a name cannot be this player's, or null if it can. */
    private String nameProblem(final String wanted) {
        final String trimmed = wanted == null ? "" : wanted.trim();
        if (trimmed.isEmpty()) {
            return "Choose a name to play under.";
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            return "That name is too long; keep it to " + MAX_NAME_LENGTH + " characters.";
        }
        if (sessions.nameTaken(trimmed, this)) {
            return "Someone here is already called " + trimmed + ".";
        }
        return null;
    }

    private void applySeat(final BrowserChannel channel, final SetSeat seat) {
        // Your own name is the one you play under, so it follows the same rules as the one you chose first
        if (seat.name() != null && seat.index() == local.webSeat()) {
            final String problem = nameProblem(seat.name());
            if (problem != null) {
                // Match setup has no line for errors, and the seat keeps its old name, so this only needs saying
                channel.send(new Notice("Name not changed", problem, false));
            } else {
                name = seat.name().trim();
                lobby.setName(seat.index(), name);
            }
        } else if (seat.name() != null) {
            lobby.setName(seat.index(), seat.name());
        }
        if (seat.deck() != null) {
            lobby.setDeck(seat.index(), seat.deck());
        }
        if (seat.avatar() != null) {
            lobby.setAvatar(seat.index(), seat.avatar());
        }
        if (seat.sleeve() != null) {
            lobby.setSleeve(seat.index(), seat.sleeve());
        }
    }

    private Hello hello() {
        final Stage now = stage;
        return new Hello(now instanceof Playing, now instanceof Setup, now instanceof Opening,
                now instanceof Playing p && p.spectating(), isHost,
                // Nobody hosts by arriving, so a browser is offered the seat whenever it is free
                !isHost && sessions.hostSeatFree(this),
                // A game nobody was invited to has nobody to talk to, so the browser leaves the chat out altogether
                invited(now) || !isHost,
                playerName(),
                // Seat 0 is the player and seat 1 the opponent, as in the desktop lobby's saved choices
                seatIndices(FPref.UI_AVATARS), seatIndices(FPref.UI_SLEEVES),
                SkinSprites.avatarCount(), SkinSprites.sleeveCount(), DeckCatalog.savedSleeveArt(),
                now instanceof Event, now instanceof Event e ? e.pool() : null, isHost ? OfflineEvents.sealed().size() : 0,
                now instanceof Event e ? e.kind() : null, offlineDraft != null || (now instanceof Setup && onlineDrafting()),
                isHost ? OfflineEvents.storage("draft").size() : 0,
                // The menu's volume slider and music need them before any match sends them with its controls
                WebSettings.values(settings));
    }

    private boolean onlineDrafting() {
        final OnlineDraft draft = onlineDraft;
        return draft != null && draft.drafting();
    }

    /** The Limited pages are drawn from the pools and what the setup form offers. Reading the lists touches files, so not here. */
    private void sendLimited(final BrowserChannel channel) {
        ui.runBackgroundTask("Limited", () -> {
            channel.send(OfflineEvents.pools());
            channel.send(OfflineEvents.options());
        });
    }

    private void editPool(final BrowserChannel channel, final String name) {
        final Stage now = stage;
        final DeckGroup group = now instanceof Event e ? e.storage().get(name) : null;
        if (group == null) {
            channel.send(error("There is no pool called " + name + "."));
            return;
        }
        final Event e = (Event) now;
        decks.openPool(group.getHumanDeck(), e.storage(), e.type(), channel);
    }

    private void deletePool(final BrowserChannel channel, final String name) {
        final Stage now = stage;
        if (!isHost || !(now instanceof Event e)) {
            return;
        }
        if (name.equals(decks.openPoolName())) {
            channel.send(error("Close the deck of " + name + " before deleting the pool."));
            return;
        }
        synchronized (DeckCatalog.DECKS) {
            if (e.storage().contains(name)) {
                e.storage().delete(name);
            }
        }
        if (name.equals(e.pool())) {
            move(now, new Event(e.kind(), null));
        }
        channel.send(OfflineEvents.pools());
    }

    /** Opens a sealed pool as desktop's sealed screen does, then its deck in the editor. Asking for a taken name comes first. */
    private void createSealed(final BrowserChannel channel, final SealedCreate create) {
        if (!isHost || !(stage instanceof Event e) || !"sealed".equals(e.kind())) {
            return;
        }
        final String name = create.name() == null ? "" : create.name().trim();
        final String problem = DeckStore.nameProblem(name);
        if (problem != null) {
            channel.send(error(problem));
            return;
        }
        if (OfflineEvents.sealed().contains(name) && !create.replace()) {
            channel.send(new NameTaken(name));
            return;
        }
        // A second request while the first is opening would pass the name check above and replace its pool
        if (!openingPacks.compareAndSet(false, true)) {
            channel.send(error("Forge is already opening packs."));
            return;
        }
        // A block whose booster the player chooses asks through the host's browser, and waits for the answer. The
        // browser may have been reloaded by the time it is done, so results go to whichever is attached then.
        ui.runBackgroundTask("Sealed", () -> {
            try {
                final DeckGroup group;
                try {
                    group = OfflineEvents.create(create, name);
                } catch (final IllegalArgumentException ex) {
                    tell(error(ex.getMessage()));
                    return;
                }
                if (group == null) {
                    return;
                }
                OfflineEvents.store(OfflineEvents.sealed(), group);
                final Stage now = stage;
                if (now instanceof Event) {
                    move(now, new Event("sealed", name));
                }
                tell(OfflineEvents.pools());
                decks.openPool(group.getHumanDeck(), OfflineEvents.sealed(), GameType.Sealed, browser);
            } finally {
                openingPacks.set(false);
            }
        });
    }

    /** Starts an offline booster draft against the computer, as desktop's Draft screen does. One runs at a time. */
    private synchronized void startDraft(final BrowserChannel channel, final DraftStart start) {
        final Stage now = stage;
        if (!isHost || !(now instanceof Event e) || !"draft".equals(e.kind())) {
            return;
        }
        if (offlineDraft != null) {
            channel.send(error("A draft is already running."));
            return;
        }
        final Supplier<BoosterDraft> make;
        try {
            make = OfflineEvents.draft(start);
        } catch (final IllegalArgumentException ex) {
            channel.send(error(ex.getMessage()));
            return;
        }
        offlineDraft = new OfflineDraft(make, playerName(), this::tell, problem -> {
            endDraft();
            tell(error(problem));
        });
        move(now, new Event("draft", null));
    }

    /** Stops the draft without saving it, as desktop's "quit without saving" does. */
    private void endDraft() {
        final OfflineDraft draft;
        synchronized (this) {
            draft = offlineDraft;
            offlineDraft = null;
        }
        if (draft == null) {
            return;
        }
        draft.close();
        final Stage now = stage;
        if (now instanceof Event e) {
            move(now, new Event(e.kind(), e.pool()));
        }
    }

    /** Saves a finished draft as desktop does, asking before replacing one of the same name, then opens its deck. */
    private void saveDraft(final BrowserChannel channel, final DraftSave save) {
        final OfflineDraft draft = offlineDraft;
        if (!isHost || draft == null || draft.latest() == null || !draft.latest().done()) {
            return;
        }
        final String name = save.name() == null ? "" : save.name().trim();
        final String problem = DeckStore.nameProblem(name);
        if (problem != null) {
            channel.send(error(problem));
            return;
        }
        final IStorage<DeckGroup> drafts = OfflineEvents.storage("draft");
        if (drafts.contains(name) && !save.replace()) {
            channel.send(new NameTaken(name));
            return;
        }
        // Off the draft's own thread, which closing the draft interrupts
        draft.save(name).whenCompleteAsync((group, ex) -> {
            if (ex != null) {
                tell(error("Could not save the draft: " + ex.getMessage()));
                return;
            }
            OfflineEvents.store(drafts, group);
            endDraft();
            final Stage now = stage;
            if (now instanceof Event) {
                move(now, new Event("draft", name));
            }
            tell(OfflineEvents.pools());
            decks.openPool(group.getHumanDeck(), drafts, GameType.Draft, browser);
        });
    }

    /**
     * Plays a pool's deck as desktop's limited screens offer: against one of its opponents, several at once (a draft only),
     * or every one in turn as a gauntlet. Leaving the match returns to the pool.
     */
    private void playPool(final BrowserChannel channel, final PoolPlay play) {
        final Stage from = stage;
        final DeckGroup group = from instanceof Event e ? e.storage().get(play.name()) : null;
        if (!isHost || group == null) {
            return;
        }
        final Event event = (Event) from;
        final List<Deck> ai = group.getAiDecks();
        final Deck human = group.getHumanDeck();
        if (FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY)) {
            final String illegal = DeckFormat.Limited.getDeckConformanceProblem(human);
            if (illegal != null) {
                channel.send(error("Your deck " + illegal + "."));
                return;
            }
        }
        if (play.games() == 1 || play.games() == 3 || play.games() == 5) {
            FModel.getPreferences().setPref(FPref.UI_MATCHES_PER_GAME, String.valueOf(play.games()));
            FModel.getPreferences().save();
        }
        // Saved before the match so HostedMatch never reaches the first-run name prompt
        lobby.saveLooks();
        final Event back = new Event(event.kind(), play.name());
        switch (play.mode() == null ? "one" : play.mode()) {
            case "gauntlet" -> {
                final GauntletMini gauntlet = FModel.getGauntletMini();
                if (event.type() == GameType.Draft) {
                    gauntlet.resetGauntletDraft();
                }
                gauntletRunning = true;
                lastResult = null;
                gauntlet.setRoundStarter((type, players, me) -> playLimited(back, type, List.of(mySeat(players.get(0).getDeck()),
                        opponentSeat(gauntlet.getCurrentRound(), 1, players.get(1).getDeck()))));
                try {
                    gauntlet.launch(ai.size(), human, event.type());
                } catch (final RuntimeException ex) {
                    Logger.error(ex, "Could not start the gauntlet");
                    channel.send(error("Could not start the gauntlet: " + ex.getMessage()));
                }
                // A first round that did not start leaves the pool showing, and no gauntlet to carry on
                if (!(stage instanceof Playing)) {
                    stopGauntlet();
                }
            }
            case "several" -> {
                final int count = Math.min(Math.min(play.count(), MOST_OPPONENTS), ai.size());
                if (event.type() != GameType.Draft || count < 2) {
                    channel.send(error("Several opponents at once needs a draft with at least two opponents."));
                    return;
                }
                // Chosen at random, as desktop's draft screen chooses them
                final List<Integer> indices = new ArrayList<>();
                for (int i = 0; i < ai.size(); i++) {
                    indices.add(i);
                }
                Collections.shuffle(indices);
                final List<LocalGame.Seat> seats = new ArrayList<>(List.of(mySeat(human)));
                for (int k = 0; k < count; k++) {
                    seats.add(opponentSeat(indices.get(k) + 1, k + 1, ai.get(indices.get(k))));
                }
                playLimited(back, event.type(), seats);
            }
            default -> {
                if (play.opponent() < 0 || play.opponent() >= ai.size()) {
                    channel.send(error("There is no such opponent."));
                    return;
                }
                playLimited(back, event.type(), List.of(mySeat(human), opponentSeat(play.opponent() + 1, 1, ai.get(play.opponent()))));
            }
        }
    }

    private LocalGame.Seat mySeat(final Deck deck) {
        return new LocalGame.Seat(playerName(), false, avatarIndex(), LocalGame.storedIndex(FPref.UI_SLEEVES, 0), deck);
    }

    /** An opponent named by its deck's place in the pool, as desktop numbers them, with the looks of seat. */
    private static LocalGame.Seat opponentSeat(final int number, final int seat, final Deck deck) {
        return new LocalGame.Seat("Opponent " + number, true, LocalGame.storedIndex(FPref.UI_AVATARS, seat),
                LocalGame.storedIndex(FPref.UI_SLEEVES, seat), deck);
    }

    /** Starts a pool's match, from the Limited pages or from the match before it in a gauntlet. Null when it could not start. */
    private HostedMatch playLimited(final Event back, final GameType type, final List<LocalGame.Seat> seats) {
        final Stage from = stage;
        final Playing playing = new Playing(new WebGuiGame(settings), false, false, back);
        if (!move(from, playing)) {
            playing.gui().close();
            return null;
        }
        final BrowserChannel b = browser;
        if (b != null) {
            playing.gui().attach(b);
        }
        if (gauntletRunning) {
            playing.gui().onGameOver(() -> recordGauntletGame(playing));
        }
        try {
            local.startLimitedMatch(seats, type, playing.gui());
            return local.hostedMatch();
        } catch (final RuntimeException ex) {
            Logger.error(ex, "Could not start the match");
            local.endMatch();
            move(playing, back);
            tell(error("Could not start the match: " + ex.getMessage()));
            return null;
        }
    }

    /** A gauntlet game ended: the win or loss is recorded after every game, as desktop's limited result does. */
    private void recordGauntletGame(final Playing playing) {
        final GameView game = playing.gui().getGameView();
        if (!gauntletRunning || game == null) {
            return;
        }
        final GauntletMini gauntlet = FModel.getGauntletMini();
        final boolean won = playerName().equals(game.getWinningPlayerName());
        if (won) {
            gauntlet.addWin();
        } else {
            gauntlet.addLoss();
        }
        // The winner of a match's last game is the match's winner
        final boolean wonMatch = game.isMatchOver() && won;
        lastResult = new LimitedResult(gauntlet.getCurrentRound(), gauntlet.getRounds(), gauntlet.getWins(), gauntlet.getLosses(),
                game.isMatchOver(), wonMatch, wonMatch && gauntlet.getCurrentRound() < gauntlet.getRounds());
        tell(lastResult);
    }

    /** Leaving a gauntlet ends it, as desktop's Quit does. */
    private void stopGauntlet() {
        if (!gauntletRunning) {
            return;
        }
        gauntletRunning = false;
        lastResult = null;
        FModel.getGauntletMini().resetCurrentRound();
        FModel.getGauntletMini().setRoundStarter(null);
    }

    private static boolean invited(final Stage stage) {
        if (stage instanceof Setup s) {
            return s.invited();
        }
        return stage instanceof Playing p && p.invited();
    }

    private static List<Integer> seatIndices(final FPref pref) {
        return List.of(LocalGame.storedIndex(pref, 0), LocalGame.storedIndex(pref, 1));
    }

    private static ErrorMessage error(final String message) {
        return new ErrorMessage(message);
    }

    private void start(final BrowserChannel channel, final Start msg) {
        if (!isHost) {
            channel.send(error("Only the host can start the match."));
            return;
        }
        final Stage from = stage;
        if (!(from instanceof Setup setup)) {
            channel.send(error("No lobby is open."));
            return;
        }
        final List<String> problems = lobby.problems();
        if (!problems.isEmpty()) {
            channel.send(error(problems.get(0)));
            return;
        }
        // Saved before the match so HostedMatch never reaches the first-run name prompt
        lobby.saveLooks();
        final Playing playing = new Playing(setup.gui(), setup.invited(), msg.spectate(), null);
        if (!move(from, playing)) {
            return;
        }
        final BrowserChannel b = browser;
        if (b != null) {
            playing.gui().attach(b);
        }
        try {
            local.start();
            if (playing.spectating()) {
                local.spectate();
            }
        } catch (final RuntimeException e) {
            Logger.error(e, "Could not start the match");
            local.endMatch();
            move(playing, new Menu());
            channel.send(error("Could not start the match: " + e.getMessage()));
        }
    }

    /** Back to match setup after a game, into the same kind of lobby as before it. */
    private void leave() {
        final Stage from = stage;
        if (!(from instanceof Playing playing)) {
            return;
        }
        final BrowserChannel b = browser;
        if (playing.back() != null) {
            stopGauntlet();
            local.endMatch();
            if (move(from, playing.back()) && b != null) {
                sendLimited(b);
            }
            return;
        }
        if (isHost && b != null) {
            // An event's matches follow one another, so the new table plays the same event's decks
            final ServerGameLobby was = local.hostedLobby();
            final GameLobbyData data = was == null ? null : was.getData();
            final String event = data != null && data.isLimitedMode() ? data.getActiveEventId() : null;
            final GameType type = data == null ? null : data.getLimitedType();
            final boolean decksOnly = data != null && data.isActiveConformance();
            // Opening the lobby moves on from the match, and closes it
            openLobby(b, playing.invited());
            if (event != null) {
                lobby.playEvent(event, type, decksOnly);
                relistDecks(b);
            }
        } else if (move(from, new Menu())) {
            joinHostGame();
        }
    }

    /** Lets go of this browser's match and seat without stopping the process. */
    void shutdown() {
        closeGui();
        local.shutdown();
    }

    private void quit() {
        sessions.hostGameClosed();
        if (stage instanceof Playing p) {
            p.gui().concede();
        }
        shutdown();
        onQuit.run();
    }

    /** Each GUI holds a thread of its own, so a session going away has to let go of it. */
    private synchronized void closeGui() {
        final WebGuiGame gui = guiOf(stage);
        stage = new Menu();
        if (gui != null) {
            gui.close();
        }
    }
}
