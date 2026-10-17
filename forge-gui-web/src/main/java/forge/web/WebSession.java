package forge.web;

import com.google.gson.JsonObject;
import forge.deck.DeckGroup;
import forge.game.GameType;
import forge.game.GameView;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.IHasForgeLog;
import forge.util.Localizer;
import forge.util.LogSafe;
import forge.util.storage.IStorage;
import forge.web.campaign.CampaignSession;
import forge.web.deck.CardCatalog;
import forge.web.deck.DeckCatalog;
import forge.web.deck.DeckSession;
import forge.web.images.SkinSprites;
import forge.web.limited.Draft;
import forge.web.limited.EventSeat;
import forge.web.limited.DraftOwner;
import forge.web.limited.OfflineDraft;
import forge.web.limited.OfflineEvents;
import forge.web.limited.OfflineLimited;
import forge.web.lobby.Lobby;
import forge.web.lobby.LobbyFormats;
import forge.web.match.LocalGame;
import forge.web.match.WebGuiGame;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.Commands;
import forge.web.protocol.Commands.Handler;
import forge.web.protocol.FromBrowser.*;
import forge.web.protocol.ToBrowser;
import forge.web.protocol.ToBrowser.*;
import forge.web.protocol.Wire;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Consumer;

/** One browser's session, whose place is held in one {@link Stage} that changes only through {@link #move}. */
public final class WebSession implements IHasForgeLog {
    /** Where the browser is. Each stage holds what exists there and nothing else. */
    public sealed interface Stage permits Menu, Opening, Setup, Event, InCampaign, Playing { }

    /** The start page; for a guest, waiting for the host to open a game. */
    public record Menu() implements Stage { }

    /** Taking a seat: the table is built while the browser waits, and nothing may change it until the seat is taken. */
    record Opening() implements Stage { }

    /** Match setup, at a seat whose GUI the match will be played through. Invited means others can join. */
    public record Setup(WebGuiGame gui, boolean invited) implements Stage { }

    /** The Limited pages for a kind of event: the saved pools, the setup form, and the opponents screen of pool, when it is not null. */
    public record Event(EventKind kind, String pool) implements Stage {
        public IStorage<DeckGroup> storage() {
            return OfflineEvents.storage(kind);
        }

        public GameType type() {
            return kind == EventKind.draft ? GameType.Draft : GameType.Sealed;
        }
    }

    /** A campaign mode's pages: its saved games while save is null, otherwise the one that is open. */
    public record InCampaign(String mode, String save) implements Stage { }

    /** In a match, playing it or watching the computer play it. back is where leaving it returns to: a pool, a campaign, or null for the table. */
    public record Playing(WebGuiGame gui, boolean invited, boolean spectating, Stage back) implements Stage { }

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
    public volatile PlayerSettings settings = PlayerSettings.fresh();
    /** The name this browser chose to play under; null until it has chosen one. */
    private volatile String name;
    /** The face chosen beside the name, before any seat exists to carry it; null until it has chosen one. */
    private volatile Integer avatar;
    private final OfflineLimited limited;
    /** This seat's part in the table's draft or sealed event. */
    private final EventSeat eventSeat;
    /** The campaign modes, which a build without them replaces with Campaigns.NONE. */
    private final Campaigns campaigns;

    private static final AtomicInteger SESSIONS = new AtomicInteger();
    /** Tells this session's lines in the log from another's, whose player may have no name yet or the same one. */
    private final int number = SESSIONS.incrementAndGet();
    /** When the browser last went, or 0 while it has never gone. */
    private volatile long leftAt;

    /** This session as the log names it. */
    String label() {
        final String who = playerName();
        return "#" + number + " " + (StringUtils.isBlank(who) ? "unnamed" : who);
    }

    /** How long the browser was gone before it came back just now, or -1 when this is its first arrival. */
    long secondsAway() {
        final long left = leftAt;
        return left == 0 ? -1 : (System.currentTimeMillis() - left) / 1000;
    }

    WebSession(final WebGuiBase ui, final WebSessions sessions, final Runnable onQuit, final boolean mayHost) {
        this.mayHost = mayHost;
        this.ui = ui;
        this.sessions = sessions;
        final Map<String, DeckCatalog.OnDevice> device = new ConcurrentHashMap<>();
        this.lobby = new Lobby(local, () -> !isHost, device, ui);
        this.decks = new DeckSession(lobby, ui, () -> isHost, () -> stage instanceof Setup, device);
        this.limited = new OfflineLimited(this, ui, lobby, decks, local);
        this.eventSeat = new EventSeat(this, ui, lobby, decks, local, sessions::hostLobby, sessions::seatsChanged);
        this.campaigns = new CampaignSession(new CampaignHost());
        this.onQuit = onQuit;
    }

    /** Takes the host's seat. Host dialogs belong to whoever holds it, so they are pointed here. */
    void becomeHost() {
        isHost = true;
        settings = PlayerSettings.saved();
        campaigns.becameHost();
        ui.setNoticeSink(notice -> {
            final BrowserChannel b = browser;
            if (b != null) {
                b.send(notice);
            }
        });
    }

    boolean mayHost() {
        return mayHost;
    }

    /** Where the browser is. Only {@link #move} changes it, apart from the session closing. */
    public Stage stage() {
        return stage;
    }

    /** Whether this session is holding a game open, which is what keeps the host's seat reserved. */
    boolean hasGame() {
        final Stage now = stage;
        return now instanceof Setup || now instanceof Event || now instanceof InCampaign || now instanceof Playing;
    }

    /** Moves to the next stage only if the browser is still where the caller found it, closing the old GUI unless the new stage keeps it. */
    public synchronized boolean move(final Stage from, final Stage next) {
        if (stage != from) {
            return false;
        }
        stage = next;
        netLog.info("{}: {} to {}", label(), from.getClass().getSimpleName(), next.getClass().getSimpleName());
        final WebGuiGame old = guiOf(from);
        if (old != null && old != guiOf(next)) {
            old.close();
        }
        tell(hello());
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
        if (!isHost) {
            tell(hello());
        }
    }

    /** The saved player name is the host's, so only a host that has not chosen a name plays under it. */
    public String playerName() {
        final String chosen = name;
        return chosen != null || !isHost ? chosen : FModel.getPreferences().getPref(FPref.PLAYER_NAME);
    }

    Lobby lobby() {
        return lobby;
    }

    LocalGame local() {
        return local;
    }

    EventSeat eventSeat() {
        return eventSeat;
    }

    public boolean attached() {
        return browser != null;
    }

    public BrowserChannel browser() {
        return browser;
    }

    /** Whether this guest holds a seat at the host's table, which it keeps while its browser is gone. */
    boolean seatedAsGuest() {
        final Stage now = stage;
        return !isHost && (now instanceof Setup || now instanceof Playing p && p.invited());
    }

    /** This session's seat at the table it sits at, or -1. */
    int seatIndex() {
        return local.webSeat();
    }

    /** The host hands another seat of its running match to the AI. */
    void handSeatToAi(final int seat) {
        local.handToAi(seat);
    }

    /** Whether this session holds the host's seat. */
    public boolean hosting() {
        return isHost;
    }

    /** The face chosen on the start page; the first avatar until one is. */
    public int avatarIndex() {
        final Integer face = avatar;
        return face == null ? 0 : face;
    }

    /** What this session is doing, for the panel that says who is here. */
    Doing doing() {
        final Stage now = stage;
        if (now instanceof Playing p) {
            return p.spectating() ? Doing.watching : Doing.playing;
        }
        if (now instanceof Setup) {
            return Doing.table;
        }
        return now instanceof Opening ? Doing.joining : Doing.waiting;
    }

    /** Sends one message to this session's browser, if it has one. */
    public void tell(final Record message) {
        final BrowserChannel b = browser;
        if (b != null) {
            b.send(message);
        }
    }

    /** Shows the browser the game a match is played through, when one is attached. */
    public void attach(final WebGuiGame gui) {
        final BrowserChannel b = browser;
        if (b != null) {
            gui.attach(b);
        }
    }

    /** Whether this session has a game open that guests can take a seat in. */
    boolean hostsGame() {
        return local.isHost();
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
        // The same for the whole run, so a browser is sent them once rather than with every change to the table
        channel.send(LobbyFormats.cardPools());
        if (isHost) {
            ui.hostRequests().replay(channel::send);
        }
        decks.reconnected(channel);
        if (now instanceof Playing p) {
            p.gui().attach(channel);
            final LimitedResult result = limited.lastResult;
            if (result != null) {
                channel.send(result);
            }
            campaigns.reconnected(channel, now);
        } else if (now instanceof Setup) {
            // Match setup is drawn from the table, which only these messages describe
            lobby.sendDecks(channel);
            lobby.sendState(channel);
            if (isHost && lobby.events().settingUpEvent()) {
                sendEventOptions(channel);
            }
            eventSeat.reconnected(channel);
        } else if (now instanceof Event) {
            limited.sendLimited(channel);
            final OfflineDraft draft = limited.draft();
            if (draft != null && draft.latest() != null) {
                channel.send(draft.latest());
            }
        } else if (now instanceof InCampaign) {
            final OfflineDraft draft = limited.draft();
            if (draft != null && draft.latest() != null) {
                channel.send(draft.latest());
            }
            campaigns.reconnected(channel, now);
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
        leftAt = System.currentTimeMillis();
        decks.attach(null);
        if (stage instanceof Playing p) {
            p.gui().detach(channel);
        }
    }

    /** The commands the session handles itself. The rest belong to the deck editor, the Limited pages, the match or a campaign. */
    public static final Commands<WebSession> COMMANDS = new Commands<WebSession>()
            .on(Plain.decks, (s, ch, c) -> s.lobby.queueDecks(ch, s.decks::inTurn))
            .on(PageError.class, (s, ch, e) -> netLog.warn("Page error for {}: {}", s.label(), LogSafe.forLog(e.text(), 1500)))
            .on(SetName.class, (s, ch, n) -> s.rename(ch, n.name(), n.avatar()))
            // A join that found no seat leaves the browser on the waiting page, which offers to try again
            .on(Plain.join, (s, ch, c) -> s.joinHostGame())
            .on(Plain.claimHost, (s, ch, c) -> s.claimHost(ch))
            // Opening a game connects a client to a server, which the host UI thread owns
            .on(Plain.lobby, (s, ch, c) -> s.ui.invokeInEdtLater(() -> s.openLobby(ch, false, null)))
            .on(Invite.class, (s, ch, i) -> s.ui.invokeInEdtLater(() -> s.openLobby(ch, true, i.limited())))
            .on(Plain.leaveLobby, (s, ch, c) -> s.ui.invokeInEdtLater(s::leaveLobby))
            .on(Ready.class, atSetup((s, ch, r) -> s.lobby.setReady(r.ready())))
            .on(SeatCommand.class, atSetup(WebSession::seatCommand))
            .on(SetFormat.class, atSetup((s, ch, f) -> {
                s.lobby.setFormat(f.format());
                s.relistDecks(ch);
            }))
            .on(SetCardPool.class, atSetup((s, ch, p) -> {
                s.lobby.setCardPool(p.cardPool());
                s.relistDecks(ch);
            }))
            .on(SetVariant.class, atSetup((s, ch, v) -> {
                s.lobby.setVariant(v.variant(), v.on());
                s.relistDecks(ch);
            }))
            .on(SetArchenemy.class, atSetup((s, ch, a) -> s.lobby.setArchenemy(a.index())))
            .on(SetTeams.class, atSetup((s, ch, t) -> s.lobby.setTeams(t.count())))
            .on(SetTeam.class, atSetup((s, ch, t) -> s.lobby.setTeam(t.index(), t.team())))
            .on(SetSeatExtra.class, atSetup((s, ch, e) -> s.lobby.setSeatExtra(e.index(), e.section(), e.choice())))
            .on(SetPlayerCount.class, atSetup((s, ch, p) -> s.lobby.setPlayerCount(p.count(), invited(s.stage))))
            .on(SetMatchLength.class, atSetup((s, ch, m) -> {
                if (s.lobby.setMatchLength(m.games())) {
                    s.sessions.lobbyChanged();
                }
            }))
            .on(SetMaxBracket.class, atSetup((s, ch, b) -> {
                if (s.lobby.setMaxBracket(b.bracket())) {
                    s.sessions.lobbyChanged();
                }
            }))
            .on(SetSeat.class, atSetup(WebSession::applySeat))
            .on(SleeveArt.class, atSetup((s, ch, a) -> s.lobby.setSleeveArt(a.index(), a.key(), a.offset())))
            // The saved sleeves are the host's own Forge profile, and the list of them travels in the hello
            .on(ForgetSleeveArt.class, atSetup(host((s, ch, f) -> {
                DeckCatalog.forgetSleeveArt(f.key());
                ch.send(s.hello());
            })))
            .on(EventDecksOnly.class, atEvent((s, ch, d) -> {
                s.lobby.events().setEventDecksOnly(d.on());
                s.relistDecks(ch);
            }))
            .on(EventHostAgain.class, atEvent((s, ch, e) -> {
                reportProblem(ch, s.lobby.events().hostAgain(e.eventId()));
                s.relistDecks(ch);
            }))
            .on(EventForget.class, atEvent((s, ch, e) -> {
                reportProblem(ch, s.lobby.events().forgetEvent(e.eventId()));
                s.relistDecks(ch);
            }))
            .on(Plain.eventNew, atEvent((s, ch, c) -> {
                final String problem = s.lobby.events().newEvent();
                if (problem != null) {
                    ch.send(error(problem));
                } else {
                    s.sendEventOptions(ch);
                }
                s.relistDecks(ch);
            }))
            .on(SetLimited.class, atEvent((s, ch, l) -> s.setLimited(ch, l.kind())))
            .on(SitOut.class, atEvent((s, ch, o) -> s.lobby.events().sitOut(o.index(), o.sittingOut())))
            // Building a product can wait on a web site, and dealing packs opens every pool, so neither runs here
            .on(EventSetup.class, atEvent((s, ch, e) -> s.ui.runBackgroundTask("Event", () -> {
                reportProblem(ch, s.lobby.events().setUpEvent(e));
                s.lobby.sendState(ch);
            })))
            .on(Plain.eventStart, atEvent((s, ch, c) -> {
                if (s.limited.draft() != null) {
                    ch.send(error(Localizer.getInstance().getMessage("lblWebSessionFinishDraft")));
                } else {
                    reportProblem(ch, s.lobby.events().startEvent());
                }
            }))
            .on(GiveSeat.class, host((s, ch, g) -> s.sessions.giveSeat(g.from(), g.to())))
            .on(DropPlayer.class, host((s, ch, d) -> s.sessions.dropPlayer(d.name())))
            .on(Say.class, (s, ch, say) -> {
                final String text = say.text();
                if (text != null && !text.isBlank()) {
                    s.sessions.say(s, text.length() > MOST_CHAT_CHARS ? text.substring(0, MOST_CHAT_CHARS) : text);
                }
            })
            .on(AskExtraChoices.class, (s, ch, a) -> {
                final ToBrowser.ExtraChoices choices = s.lobby.extraChoices(a.index(), a.section());
                if (choices != null) {
                    ch.send(choices);
                }
            })
            .on(DeckQuery.class, (s, ch, q) -> s.decks.inTurn(() -> ch.send(s.lobby.deckMatches(q.kind(), q.value()))))
            .on(AskDeckDetails.class, (s, ch, a) -> s.decks.inTurn(() -> {
                final ToBrowser.DeckDetailsMessage details = s.lobby.deckDetails(a.key());
                if (details != null) {
                    ch.send(details);
                }
            }))
            // One set of host questions serves the process, so only the host's browser may answer them
            .on(HostChoiceAnswer.class, host((s, ch, a) -> s.ui.hostRequests().answer(a.id(), a.value())))
            // Core asks which category through a host question, and blocks on it, so not on the socket thread
            .on(Plain.netDecks, host((s, ch, c) -> s.ui.runBackgroundTask("Net decks", () -> s.lobby.loadNetDecks(ch))))
            // Finding the external address is a web request, so it cannot run on the socket thread
            .on(Plain.addresses, host((s, ch, c) -> s.ui.runBackgroundTask("Addresses", () -> ch.send(new Addresses(s.sessions.inviteUrls())))))
            .on(Plain.cardPoolDetails, (s, ch, c) -> ch.send(LobbyFormats.cardPoolDetails()))
            .on(Plain.cardSets, (s, ch, c) -> ch.send(CardCatalog.sets()))
            .on(SetDeckGen.class, host((s, ch, gen) -> {
                final var prefs = FModel.getPreferences();
                prefs.setPref(FPref.DECKGEN_SINGLETONS, gen.singletons());
                prefs.setPref(FPref.DECKGEN_ARTIFACTS, gen.noArtifacts());
                prefs.save();
                s.lobby.queueDecks(ch, s.decks::inTurn);
            }))
            .on(SearchCards.class, (s, ch, q) -> s.decks.inTurn(() -> ch.send(new CardSearch(DeckCatalog.searchCardNames(q.query(), CARD_SEARCH_LIMIT)))))
            .on(AskPrintings.class, (s, ch, a) -> s.decks.inTurn(() -> ch.send(new Printings(a.name(), DeckCatalog.printings(a.name(),
                    a.cardPool() == null ? null : FModel.getFormats().getFormat(a.cardPool()))))))
            // LocalGame runs on the host UI thread, so host-side dialogs during setup never block a web server thread
            .on(Start.class, (s, ch, st) -> s.ui.invokeInEdtLater(() -> s.start(ch, st)))
            .on(Plain.leave, (s, ch, c) -> s.ui.invokeInEdtLater(s::leave))
            .on(LimitedOpen.class, WebSession::openLimited)
            .on(Plain.limitedLeave, (s, ch, c) -> s.atPool(e -> new Menu()))
            .on(Plain.poolClose, (s, ch, c) -> s.atPool(e -> new Event(e.kind(), null)))
            .on(PoolOpen.class, (s, ch, o) -> s.atPool(e -> new Event(e.kind(), o.name())))
            .on(DraftMove.class, (s, ch, m) -> {
                final Draft draft = s.draft();
                if (draft != null) {
                    draft.move(m.index(), m.sideboard());
                }
            })
            .on(DraftPick.class, (s, ch, p) -> {
                final Draft draft = s.draft();
                if (draft != null) {
                    draft.pick(p.step(), p.index(), p.sideboard());
                }
            })
            // A cheat asks its questions as the game does and waits for the answers, so it runs on a thread of its own
            .on(Dev.class, host((s, ch, d) -> {
                if (s.stage instanceof Playing) {
                    s.ui.runBackgroundTask("Dev mode", () -> DevMode.run(s.local, d, ch));
                }
            }))
            // Quitting stops the process every browser is served from, so it is the host's to do
            .on(Plain.quit, host((s, ch, c) -> s.ui.invokeInEdtLater(s::quit)))
            // A setting changed before a match is what the match is seeded with, and during one the game is told as well
            .on(SetSetting.class, (s, ch, set) -> {
                if (s.stage instanceof Playing p) {
                    p.gui().setSetting(set);
                } else {
                    WebSettings.set(s.settings, null, set.key(), set.value());
                }
            })
            .on(SetStops.class, (s, ch, stops) -> {
                if (s.stage instanceof Playing p) {
                    p.gui().setStops(stops);
                } else {
                    WebSettings.setStops(s.settings, stops.mine(), stops.phases());
                }
            });

    /**
     * The table can be changed only while it is set up: not while it is being built, and not once it is played.
     * Every change to it is made on the host UI thread, which also opens, starts and leaves it.
     */
    private static <T extends Record> Handler<WebSession, T> atSetup(final Handler<WebSession, T> change) {
        return (s, ch, c) -> s.ui.invokeInEdtLater(() -> {
            if (s.stage instanceof Setup) {
                change.run(s, ch, c);
                s.lobby.sendState(ch);
            }
        });
    }

    /** A change to the table's draft or sealed event, which is the host's to make. */
    private static <T extends Record> Handler<WebSession, T> atEvent(final Handler<WebSession, T> change) {
        return atSetup(host(change));
    }

    private static <T extends Record> Handler<WebSession, T> host(final Handler<WebSession, T> handler) {
        return (s, ch, c) -> {
            if (s.isHost) {
                handler.run(s, ch, c);
            }
        };
    }

    void onMessage(final BrowserChannel channel, final JsonObject msg) {
        final Record command = Wire.decode(msg);
        if (command == null) {
            netLog.warn("Unknown browser message {}", LogSafe.forLog(msg.toString(), 200));
        } else if (COMMANDS.run(this, channel, command)) {
            return;
        } else if (DeckSession.COMMANDS.handles(command)) {
            deckCommand(channel, command);
        } else if (OfflineLimited.COMMANDS.handles(command)) {
            limited.onCommand(channel, command);
        } else if (WebGuiGame.COMMANDS.handles(command)) {
            if (stage instanceof Playing p) {
                p.gui().onCommand(channel, command);
            }
        } else if (campaigns.handles(command)) {
            campaigns.run(channel, command);
        }
    }

    /** A deck editor or importer command. A match takes the whole page, so nothing about decks is done during one. */
    private void deckCommand(final BrowserChannel channel, final Record command) {
        if (campaigns.deckCommand(channel, command) || stage instanceof Playing) {
            return;
        }
        final boolean closing = command instanceof Bare b && b.t() == Plain.editorClose;
        // The pools go first, since closing the editor lists every deck again before the page changes
        if (closing && stage instanceof Event) {
            channel.send(OfflineEvents.pools());
        }
        decks.onCommand(channel, command);
    }

    private void claimHost(final BrowserChannel channel) {
        if (!sessions.claimHost(this)) {
            channel.send(error(Localizer.getInstance().getMessage("lblWebSessionAlreadyHosting")));
        }
        channel.send(hello());
    }

    private void leaveLobby() {
        final Stage now = stage;
        if (now instanceof Setup && move(now, new Menu())) {
            local.endMatch();
            if (isHost) {
                sessions.hostGameClosed();
            }
        }
    }

    private void seatCommand(final BrowserChannel channel, final SeatCommand seat) {
        // A guest's seat is emptied by sending the guest away, which opens it; the seat itself goes only once it is empty
        if (!isHost || seat.t() == SeatAction.aiSeat || !sessions.releaseGuestAt(seat.index())) {
            switch (seat.t()) {
                case openSeat -> lobby.openSeat(seat.index());
                case aiSeat -> lobby.aiSeat(seat.index());
                case removeSeat -> lobby.removeSeat(seat.index());
            }
        }
    }

    /** Switches the table to a kind of event, or back to Constructed when kind is null. */
    private void setLimited(final BrowserChannel channel, final EventKind kind) {
        final String problem = lobby.events().setLimited(kind);
        if (problem != null) {
            channel.send(error(problem));
        } else if (kind != null) {
            sendEventOptions(channel);
        }
        relistDecks(channel);
    }

    private void openLimited(final BrowserChannel channel, final LimitedOpen open) {
        final EventKind kind = open.kind() == EventKind.draft ? EventKind.draft : EventKind.sealed;
        final Stage now = stage;
        if (isHost && ((now instanceof Event e && e.kind() == kind && !open.resume())
                || ((now instanceof Menu || now instanceof Event)
                    && move(now, new Event(kind, open.resume() ? OfflineEvents.latest(kind) : null))))) {
            limited.sendLimited(channel);
        }
    }

    /** Moves between the Limited pages, while the browser is on one. */
    private void atPool(final Function<Event, Stage> next) {
        final Stage now = stage;
        if (now instanceof Event e) {
            move(now, next.apply(e));
        }
    }

    private static void reportProblem(final BrowserChannel channel, final String problem) {
        if (problem != null) {
            channel.send(error(problem));
        }
    }

    /** The event setup form offers what the offline one does. Reading the lists touches files, so not on the socket thread. */
    private void sendEventOptions(final BrowserChannel channel) {
        ui.runBackgroundTask("Limited", () -> channel.send(OfflineEvents.options()));
    }

    /** Opens match setup: a game of this machine's own, set for an event when limited is not null, or a seat in the host's. Tells the browser how it went. */
    private void openLobby(final BrowserChannel channel, final boolean invite, final EventKind limited) {
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
        eventSeat.newTable(gui);
        try {
            local.openHost(playerName(), gui, this::lobbyChanged, this::chatted);
            if (invite) {
                // Somebody has to be able to sit down, so the second seat is left open rather than filled
                lobby.openSeat(1);
            }
        } catch (final RuntimeException e) {
            netLog.error(e, "Could not open the lobby");
            gui.close();
            move(opening, new Menu());
            // The move's hello clears the browser's last error, so the reason has to follow it
            channel.send(error(Localizer.getInstance().getMessage("lblWebSessionLobbyFailed", String.valueOf(e.getMessage()))));
            return;
        }
        if (!move(opening, new Setup(gui, invite))) {
            gui.close();
            return;
        }
        applyChosenAvatar();
        if (limited != null) {
            setLimited(channel, limited);
        }
        lobby.sendDecks(channel);
        lobby.sendState(channel);
        sessions.hostGameOpened();
    }

    /** Takes a seat in the host's game. Runs off the host UI thread, because taking one waits on the server. */
    void joinHostGame() {
        final String seatName = name;
        final Opening joining = new Opening();
        final Stage from = stage;
        // Only one seat is taken at a time: the stage says a join is under way until it lands or fails
        if (isHost || !(from instanceof Menu) || !sessions.hostHasGame() || sessions.hostPlaying() || browser == null || seatName == null
                || !move(from, joining)) {
            return;
        }
        ui.runBackgroundTask("Joining", () -> {
            ui.invokeInEdtAndWait(lobby::forget);
            final WebGuiGame gui = new WebGuiGame(settings);
            gui.whenOpened(() -> guestMatchOpened(gui));
            eventSeat.newTable(gui);
            try {
                local.openGuest(seatName, gui, this::lobbyChanged, this::chatted, this::gameGone);
            } catch (final RuntimeException e) {
                netLog.error(e, "Could not take a seat");
                gui.close();
                move(joining, new Menu());
                // The waiting card supplies "Could not take a seat", so this is the reason alone
                tell(error(e.getMessage()));
                return;
            }
            if (!move(joining, new Setup(gui, true))) {
                // The game went while the seat was being taken
                gui.close();
                local.endMatch();
                return;
            }
            ui.invokeInEdtLater(() -> {
                applyChosenAvatar();
                final BrowserChannel b = browser;
                if (b != null) {
                    lobby.sendDecks(b);
                    lobby.sendState(b);
                }
            });
        });
    }

    /** The host started the match, so this guest's browser follows its seat into the game. */
    private void guestMatchOpened(final WebGuiGame gui) {
        final Stage now = stage;
        if (now instanceof Setup s && s.gui() == gui && move(now, new Playing(gui, true, false, null))) {
            attach(gui);
        }
    }

    /** The host's game ended under this guest, so its seat goes and its browser waits for the next one. */
    void gameGone() {
        final Stage now = stage;
        if (!(now instanceof Menu) && move(now, new Menu())) {
            local.endMatch();
        }
    }

    /** A new format, card pool or variant can change which decks a seat may take, so the list goes out again. */
    private void relistDecks(final BrowserChannel to) {
        if (lobby.restrictionsChanged()) {
            lobby.sendDecks(to);
        }
    }

    /** The table changed. The browser sees it only once it is set up; until then it is still being built. */
    void lobbyChanged() {
        ui.invokeInEdtLater(() -> {
            if (isHost) {
                sessions.seatsMayHaveChanged();
            }
            final BrowserChannel b = browser;
            if (b != null && stage instanceof Setup) {
                // A guest learns of the host's format or card pool only here, so its deck list is rebuilt here too
                relistDecks(b);
                lobby.sendState(b);
            }
        });
    }

    private void chatted(final String from, final String text) {
        if (from == null) {
            tell(new ChatLine(null, text, false));
        } else if (isHost) {
            // Every browser has its players' lines from the server's own chat (WebSessions.say), so the host passes on only the others'
            sessions.relay(from, text);
        }
    }

    /** Takes the name this browser asked for unless it has a problem, and a guest waiting for a name then takes its seat. */
    private void rename(final BrowserChannel channel, final String wanted, final Integer face) {
        final String problem = nameProblem(wanted);
        if (problem != null) {
            channel.send(error(problem));
            return;
        }
        netLog.info("{} takes the name {}.", label(), LogSafe.forLog(wanted.trim()));
        name = wanted.trim();
        if (face != null) {
            avatar = face;
        }
        ui.invokeInEdtLater(this::applyChosenAvatar);
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
            return Localizer.getInstance().getMessage("lblWebSessionNameEmpty");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            return Localizer.getInstance().getMessage("lblWebSessionNameTooLong", MAX_NAME_LENGTH);
        }
        if (sessions.nameTaken(trimmed, this)) {
            return Localizer.getInstance().getMessage("lblWebSessionNameTaken", trimmed);
        }
        return null;
    }

    private void applySeat(final BrowserChannel channel, final SetSeat seat) {
        // Your own name is the one you play under, so it follows the same rules as the one you chose first
        if (seat.name() != null && seat.index() == local.webSeat()) {
            final String problem = nameProblem(seat.name());
            if (problem != null) {
                // Match setup has no line for errors, and the seat keeps its old name, so this only needs saying
                channel.send(new Notice(Localizer.getInstance().getMessage("lblWebSessionNameNotChanged"), problem, false));
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
        if (seat.avatar() != null && lobby.setAvatar(seat.index(), seat.avatar()) && seat.index() == local.webSeat()) {
            // The face on your own seat is the one you are known by everywhere else too
            avatar = seat.avatar();
            sessions.announcePresence();
        }
        if (seat.sleeve() != null) {
            lobby.setSleeve(seat.index(), seat.sleeve());
        }
        if (seat.commander() != null) {
            lobby.setCommander(seat.index(), seat.commander());
        }
        if (seat.aiProfile() != null) {
            lobby.setAiProfile(seat.index(), seat.aiProfile());
        }
    }

    public Hello hello() {
        final Stage now = stage;
        return new Hello(screen(now), now instanceof Playing p && p.spectating(), isHost,
                // Nobody hosts by arriving, so a browser is offered the seat whenever it is free
                !isHost && sessions.hostSeatFree(this),
                // A game nobody was invited to has nobody to talk to, so the browser leaves the chat out altogether
                invited(now) || !isHost,
                playerName(),
                // Seat 0 is the player and seat 1 the opponent, as in the desktop lobby's saved choices
                seatIndices(FPref.UI_AVATARS), seatIndices(FPref.UI_SLEEVES),
                SkinSprites.avatarCount(), SkinSprites.sleeveCount(), DeckCatalog.savedSleeveArt(),
                now instanceof Event e ? e.pool() : null, isHost ? OfflineEvents.sealed().size() : 0,
                now instanceof Event e ? e.kind() : null, limited.draft() != null || (now instanceof Setup && eventSeat.drafting()),
                isHost ? OfflineEvents.storage(EventKind.draft).size() : 0,
                now instanceof InCampaign m ? m.mode() : null,
                // The open save is named in its matches too, so the result screen knows whose ending it shows
                now instanceof InCampaign c ? c.save() : now instanceof Playing p && p.back() instanceof InCampaign c ? c.save() : null,
                isHost ? campaigns.resume() : Map.of(),
                // The jar's manifest holds it, so a server run from classes has none
                WebSession.class.getPackage().getImplementationVersion(),
                // The menu's volume slider and music need them before any match sends them with its controls
                WebSettings.values(settings));
    }

    private static Screen screen(final Stage stage) {
        if (stage instanceof Opening) {
            return Screen.opening;
        }
        if (stage instanceof Setup) {
            return Screen.setup;
        }
        if (stage instanceof Event) {
            return Screen.event;
        }
        if (stage instanceof InCampaign) {
            return Screen.campaign;
        }
        return stage instanceof Playing ? Screen.playing : Screen.menu;
    }

    /** The draft this browser picks in: its own against the computer, or its seat's while the table drafts. */
    private Draft draft() {
        final OfflineDraft offline = limited.draft();
        return offline != null ? offline : stage instanceof Setup ? eventSeat.draft() : null;
    }

    /** Starts a match a campaign built, over netplay. Leaving it returns to the campaign's page. failed undoes what preparing it did. */
    private void startCampaignMatch(final InCampaign back, final Consumer<WebGuiGame> start, final Runnable failed, final Consumer<GameView> gameOver) {
        final Playing playing = new Playing(new WebGuiGame(settings), false, false, back);
        if (!move(back, playing)) {
            playing.gui().close();
            return;
        }
        attach(playing.gui());
        playing.gui().onGameOver(() -> {
            final HostedMatch match = local.hostedMatch();
            gameOver.accept(match == null ? null : match.getGameView());
        });
        try {
            start.accept(playing.gui());
        } catch (final RuntimeException ex) {
            netLog.error(ex, "Could not start the match");
            failed.run();
            local.endMatch();
            move(playing, back);
            tell(error(Localizer.getInstance().getMessage("lblWebSessionMatchFailed", String.valueOf(ex.getMessage()))));
            campaigns.show();
        }
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

    public static ErrorMessage error(final String message) {
        return new ErrorMessage(message);
    }

    private void start(final BrowserChannel channel, final Start msg) {
        if (!isHost) {
            channel.send(error(Localizer.getInstance().getMessage("lblWebSessionHostStarts")));
            return;
        }
        final Stage from = stage;
        if (!(from instanceof Setup setup)) {
            channel.send(error(Localizer.getInstance().getMessage("lblWebSessionNoLobby")));
            return;
        }
        final List<String> problems = lobby.problems();
        if (!problems.isEmpty()) {
            channel.send(error(problems.get(0)));
            return;
        }
        // Saved before the match so HostedMatch never reaches the first-run name prompt
        lobby.saveLooks();
        // Asked before leaving the lobby, so going back from desktop's illegal-deck question leaves the page where it was
        final Runnable begin;
        try {
            begin = local.prepare();
        } catch (final RuntimeException e) {
            netLog.error(e, "Could not start the match");
            channel.send(error(Localizer.getInstance().getMessage("lblWebSessionMatchFailed", String.valueOf(e.getMessage()))));
            return;
        }
        if (begin == null) {
            return;
        }
        final Playing playing = new Playing(setup.gui(), setup.invited(), msg.spectate(), null);
        if (!move(from, playing)) {
            return;
        }
        attach(playing.gui());
        try {
            begin.run();
            if (playing.spectating()) {
                local.spectate();
            }
        } catch (final RuntimeException e) {
            netLog.error(e, "Could not start the match");
            local.endMatch();
            move(playing, new Menu());
            // The table went with the match, and nothing else tells the guests seated at it
            sessions.hostGameClosed();
            channel.send(error(Localizer.getInstance().getMessage("lblWebSessionMatchFailed", String.valueOf(e.getMessage()))));
        }
    }

    /** Leaves the match: to the pool or campaign it came from, to a new table of the same kind for the host, or to the menu for a guest. */
    private void leave() {
        final Stage from = stage;
        if (!(from instanceof Playing playing)) {
            return;
        }
        final BrowserChannel b = browser;
        if (playing.back() != null) {
            limited.stopGauntlet();
            local.endMatch();
            if (playing.back() instanceof InCampaign c) {
                campaigns.left(playing, c);
            } else if (move(from, playing.back()) && b != null) {
                limited.sendLimited(b);
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
            // The new table is set as the old one was, so a rematch needs nothing chosen again
            final Lobby.Kept kept = event == null ? lobby.keep() : null;
            // Opening the lobby moves on from the match, and closes it
            openLobby(b, playing.invited(), null);
            if (event != null) {
                lobby.events().playEvent(event, type, decksOnly);
                relistDecks(b);
            } else if (kept != null) {
                lobby.restore(kept);
                relistDecks(b);
                lobby.sendState(b);
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

    /** What the campaign modes reach of this session. */
    private final class CampaignHost implements Campaigns.Host {
        @Override
        public boolean hosting() {
            return isHost;
        }

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public boolean move(final Stage from, final Stage next) {
            return WebSession.this.move(from, next);
        }

        @Override
        public void tell(final Record message) {
            WebSession.this.tell(message);
        }

        @Override
        public Record hello() {
            return WebSession.this.hello();
        }

        @Override
        public BrowserChannel browser() {
            return browser;
        }

        @Override
        public DeckSession decks() {
            return decks;
        }

        @Override
        public WebGuiBase ui() {
            return ui;
        }

        @Override
        public String playerName() {
            return WebSession.this.playerName();
        }

        @Override
        public int avatarIndex() {
            return WebSession.this.avatarIndex();
        }

        @Override
        public LocalGame local() {
            return local;
        }

        @Override
        public void startMatch(final InCampaign back, final Consumer<WebGuiGame> start, final Runnable failed, final Consumer<GameView> gameOver) {
            // A match is started on the host UI thread, as a table's is, and the campaign's work waits for it
            ui.invokeInEdtAndWait(() -> startCampaignMatch(back, start, failed, gameOver));
        }

        @Override
        public void startDraft(final BoosterDraft draft, final DraftOwner owner) {
            if (limited.beginDraft(() -> draft, true, owner)) {
                WebSession.this.tell(WebSession.this.hello());
            }
        }
    }
}
