package forge.web;

import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.net.server.ServerGameLobby;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.JsonObject;
import forge.gamemodes.net.server.FServerManager;
import forge.util.Localizer;
import forge.web.ToBrowser.Address;
import forge.web.ToBrowser.ChatLine;
import forge.web.ToBrowser.Person;
import forge.web.ToBrowser.Presence;
import org.tinylog.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Every browser attached to this process, each with a session of its own, so only the web port has to be reachable from outside. */
final class WebSessions implements WebServer.Endpoint {
    private final WebGuiBase ui;
    private final long idleMillis;
    private final Runnable onQuit;
    /** Sessions by the id their browser keeps, so a reload returns to the seat it left rather than taking a new one. */
    private final Map<String, WebSession> byId = new ConcurrentHashMap<>();
    private final Map<BrowserChannel, WebSession> byChannel = new ConcurrentHashMap<>();
    private volatile WebSession host;
    private volatile WebServer server;
    /** An empty server that nobody has reached yet is waiting, not finished, so it does not close itself. */
    private boolean mayGiveUp;
    /** Set from the console's checkbox; without a console there is nothing else to show Forge is running. */
    private boolean quitWhenEmpty = true;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setNameFormat("WebStartIdle").setDaemon(true).build());
    /** The most browsers tracked at once, since anyone with an invite link can open one after another. */
    static final int MOST_SESSIONS = 64;
    /** How long the host's seat stays reserved for a browser that has gone, so a reload keeps it. */
    private static final long HOST_GRACE_MILLIS = 20_000;
    /** How long a drafting seat's browser may be gone before the draft host is told its player left. */
    private static final long DRAFT_HOLD_MILLIS = 15_000;
    /** The app players in the list of who is here as it was last sent, to tell when one has come or gone. */
    private volatile List<String> appSeatNames = List.of();
    /** Runs when no browser has been connected for a while, which is the only sign the game is over with. */
    private ScheduledFuture<?> idle;

    WebSessions(final WebGuiBase ui, final long idleMillis, final Runnable onQuit) {
        this.ui = ui;
        this.idleMillis = idleMillis;
        this.onQuit = onQuit;
        // Also covers a browser that never opens at all
        idle = timer.schedule(onQuit, idleMillis, TimeUnit.MILLISECONDS);
    }

    /** Whether the router has agreed to forward the port, which decides how the internet link is described. */
    private volatile boolean portForwarded;
    private volatile String cloudflare;

    /** The address Cloudflare gives for this server while players come in that way, which then stands in for the router's. */
    void throughCloudflare(final String address) {
        cloudflare = address;
    }

    void portForwarded(final boolean value) {
        portForwarded = value;
    }

    /** The server is built around this endpoint, so it can only be handed over once it exists. */
    void setServer(final WebServer server) {
        this.server = server;
    }

    @Override
    public void connected(final BrowserChannel channel, final String clientId, final boolean mayHost) {
        final WebSession session = sessionFor(channel, clientId, mayHost);
        if (session == null) {
            Logger.warn("Turned a browser away: {} are already here.", MOST_SESSIONS);
            channel.close();
            return;
        }
        byChannel.put(channel, session);
        holdOpen();
        session.connected(channel);
    }

    /** Stops the process giving up before anyone connects, for when the console shows Forge is running. */
    synchronized void visibleElsewhere() {
        holdOpen();
    }

    /** Whether this session could take the host's seat right now, which is what the browser offers. */
    synchronized boolean hostSeatFree(final WebSession asking) {
        return host == null && asking != null && asking.mayHost();
    }

    /** Takes the host's seat for this session if it is still free, so only one of two claims made at once wins. */
    synchronized boolean claimHost(final WebSession session) {
        if (!session.mayHost()) {
            return false;
        }
        if (host != null) {
            return host == session;
        }
        host = session;
        session.becomeHost();
        Logger.info("A browser has taken the host's seat.");
        announceSeat();
        announcePresence();
        return true;
    }

    /** Frees the host's seat once its browser has been gone a while and it holds no game. */
    private synchronized void releaseHostIfAbandoned() {
        if (host == null || host.attached() || host.hasGame()) {
            return;
        }
        Logger.info("The host's browser has not come back. The seat is free again.");
        host = null;
        announceSeat();
    }

    /** The process lives while any browser is attached, so the host closing theirs does not end a guest's game. */
    private synchronized void holdOpen() {
        mayGiveUp = true;
        if (idle != null) {
            idle.cancel(false);
            idle = null;
        }
    }

    /** Sets whether the process ends once the last browser has gone, which when turned on applies only the next time one leaves. */
    synchronized void quitWhenEmpty(final boolean value) {
        quitWhenEmpty = value;
        if (!value && idle != null) {
            idle.cancel(false);
            idle = null;
        }
    }

    private synchronized void letGo() {
        if (quitWhenEmpty && idle == null && mayGiveUp && byChannel.isEmpty()) {
            Logger.info("No browser is attached. Forge will close in {} seconds.", idleMillis / 1000);
            idle = timer.schedule(onQuit, idleMillis, TimeUnit.MILLISECONDS);
        }
    }

    /** The session a browser returns to or a new one, null when full, keyed by link kind so a guest's link never reaches a host session. */
    private synchronized WebSession sessionFor(final BrowserChannel channel, final String clientId, final boolean mayHost) {
        final String id = clientId.isEmpty() ? String.valueOf(System.identityHashCode(channel)) : clientId;
        final String key = (mayHost ? "host:" : "guest:") + id;
        final WebSession known = byId.get(key);
        if (known != null) {
            return known;
        }
        if (byId.size() >= MOST_SESSIONS) {
            byId.values().removeIf(s -> !s.attached() && !s.hasGame() && s != host);
            if (byId.size() >= MOST_SESSIONS) {
                return null;
            }
        }
        // Nobody hosts by arriving. A browser asks for the seat, and the first to ask while it is free gets it.
        final WebSession session = new WebSession(ui, this, onQuit, mayHost);
        byId.put(key, session);
        return session;
    }

    @Override
    public void disconnected(final BrowserChannel channel) {
        final WebSession session = byChannel.remove(channel);
        if (session != null) {
            session.disconnected(channel);
            announcePresence();
            timer.schedule(session::goneAWhile, DRAFT_HOLD_MILLIS, TimeUnit.MILLISECONDS);
        }
        letGo();
        // A seat held by a browser that never comes back would leave nobody able to set the table
        timer.schedule(this::releaseHostIfAbandoned, HOST_GRACE_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onMessage(final BrowserChannel channel, final JsonObject message) {
        final WebSession session = byChannel.get(channel);
        if (session != null) {
            session.onMessage(channel, message);
        }
    }

    /** The host's lobby, where the table's event and its draft run, or null when nobody is hosting one. */
    ServerGameLobby hostLobby() {
        final WebSession h = host;
        return h == null ? null : h.hostedLobby();
    }

    /** Every browser is sent the table again, after a change no lobby update carries. */
    void lobbyChanged() {
        byId.values().forEach(WebSession::lobbyChanged);
    }

    /** Every seat's dial reads again which pod seats are held, after a player went or came back. */
    void seatsChanged() {
        byId.values().forEach(WebSession::seatsChanged);
    }

    /** Drops every seat and stops the server, guests first so none of them outlives the game they were in. */
    synchronized void shutdown() {
        holdOpen();
        timer.shutdownNow();
        for (final WebSession session : byId.values()) {
            if (session != host) {
                session.shutdown();
            }
        }
        if (host != null) {
            host.shutdown();
            host = null;
        }
        byId.clear();
        byChannel.clear();
    }

    /** How many sessions have a browser attached right now. */
    int playersHere() {
        return (int) byId.values().stream().filter(WebSession::attached).count();
    }

    boolean hostHasGame() {
        final WebSession h = host;
        return h != null && h.hostsGame();
    }

    /** Whether another player here or a computer at the host's table has this name, ignoring case. */
    boolean nameTaken(final String name, final WebSession asking) {
        for (final WebSession session : byId.values()) {
            if (session != asking && (session.attached() || session.hasGame() || session == host)
                    && name.equalsIgnoreCase(session.playerName())) {
                return true;
            }
        }
        final WebSession h = host;
        return h != null && (h.computerNames().stream().anyMatch(name::equalsIgnoreCase)
                || appSeats().stream().anyMatch(s -> name.equalsIgnoreCase(s.getName())));
    }

    /** The seats at the host's table held from desktop or mobile Forge, which have no session here. */
    private List<LobbySlot> appSeats() {
        final WebSession h = host;
        if (h == null) {
            return List.of();
        }
        final List<LobbySlot> seats = new ArrayList<>(h.remoteSeats());
        seats.removeIf(seat -> byId.values().stream().anyMatch(s -> s.hasGame() && seat.getName().equalsIgnoreCase(s.playerName())));
        return seats;
    }

    /** The host's table changed, which is the only word of a desktop or mobile player arriving or going. */
    void seatsMayHaveChanged() {
        if (!appSeats().stream().map(LobbySlot::getName).toList().equals(appSeatNames)) {
            announcePresence();
        }
    }

    /** Enough of the conversation for a browser that arrives late to see what was being said. */
    private static final int CHAT_KEPT = 60;

    private final Deque<ChatLine> said = new ArrayDeque<>();

    /** Gives a typed line to every browser here, kept by the server so chat outlasts any one table. */
    synchronized void say(final WebSession from, final String text) {
        final String who = from.playerName();
        if (who == null) {
            return;
        }
        final ChatLine line = new ChatLine(who, text, false);
        said.addLast(line);
        while (said.size() > CHAT_KEPT) {
            said.removeFirst();
        }
        for (final WebSession session : byId.values()) {
            session.tell(line);
        }
    }

    /** A browser that has just arrived is given who is here and what has been said. */
    synchronized void greet(final WebSession session) {
        session.tell(presence());
        for (final ChatLine line : said) {
            session.tell(new ChatLine(line.from(), line.text(), true));
        }
    }

    /** Who is on this server, in the order they took a session. Anyone still unnamed is nobody yet. */
    private Presence presence() {
        final List<Person> people = new ArrayList<>();
        for (final WebSession session : byId.values()) {
            final String who = session.playerName();
            if (who != null && session.attached()) {
                people.add(new Person(who, session.avatarIndex(), session.doing(), session.hosting()));
            }
        }
        final WebSession h = host;
        final List<LobbySlot> apps = appSeats();
        for (final LobbySlot seat : apps) {
            people.add(new Person(seat.getName(), seat.getAvatarIndex(), h.doing(), false));
        }
        appSeatNames = apps.stream().map(LobbySlot::getName).toList();
        return new Presence(people);
    }

    /** Tells every browser who is here, whenever that changes. */
    void announcePresence() {
        final Presence now = presence();
        byId.values().forEach(session -> session.tell(now));
    }

    /** Tells every browser without a seat that the host's seat has changed hands, or come free. */
    private void announceSeat() {
        byId.values().forEach(WebSession::hostSeatChanged);
    }

    /** Tells the guests waiting on a game that there is now one to join. */
    void hostGameOpened() {
        forEachGuest(WebSession::joinHostGame);
    }

    /** Tells the guests their game has gone. The server stays up, so nothing else would tell them. */
    void hostGameClosed() {
        forEachGuest(WebSession::gameGone);
    }

    private void forEachGuest(final Consumer<WebSession> action) {
        for (final WebSession session : byId.values()) {
            if (session != host) {
                action.accept(session);
            }
        }
    }

    static String internetCaption(final int port, final boolean forwarded) {
        return forwarded ? Localizer.getInstance().getMessage("lblWebSessionsOverInternet")
                : Localizer.getInstance().getMessage("lblWebSessionsOverInternetForward", port);
    }

    /** Every link that would reach this machine, most likely first. */
    List<Address> inviteUrls() {
        final List<Address> list = new ArrayList<>();
        final WebServer s = server;
        if (s == null) {
            return list;
        }
        for (final Map.Entry<String, String> e : FServerManager.getAllLocalAddresses().entrySet()) {
            list.add(new Address(e.getKey(), s.inviteUrl(e.getValue())));
        }
        final String through = cloudflare;
        if (through != null) {
            list.add(new Address(Localizer.getInstance().getMessage("lblWebSessionsThroughCloudflare"), s.inviteUrlThrough(through)));
            return list;
        }
        // Last, because without a forwarded port this link reaches the router and stops there
        final String external = FServerManager.getExternalAddress();
        if (external != null) {
            list.add(new Address(internetCaption(s.port(), portForwarded), s.inviteUrl(external)));
        }
        return list;
    }
}
