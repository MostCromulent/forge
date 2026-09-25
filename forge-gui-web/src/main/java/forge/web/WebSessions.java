package forge.web;

import forge.gamemodes.net.server.ServerGameLobby;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.JsonObject;
import forge.gamemodes.net.server.FServerManager;
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

/**
 * Every browser attached to this process. The first one on this machine hosts the game; anyone who opens the
 * invite link afterwards is a guest, gets a session of its own, and takes a seat in the host's game over the
 * loopback server. Only the web port is reachable from outside, so nothing else has to be forwarded.
 */
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
    /**
     * The most browsers kept track of at once. Anyone with an invite link can open one after another, so past this
     * the ones that have gone and hold nothing are forgotten, and if none has, the newcomer is turned away.
     */
    static final int MOST_SESSIONS = 64;
    /** How long the host's seat stays reserved for a browser that has gone, so a reload keeps it. */
    private static final long HOST_GRACE_MILLIS = 20_000;
    /** How long a drafting seat's browser may be gone before the draft host is told its player left. */
    long draftHoldMillis = 15_000;
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

    /**
     * Stops the process giving up before anyone has connected. The countdown exists because nothing else
     * showed Forge was running; with the console on screen that reason is gone, and a server started ahead
     * of the players who will use it has to keep waiting.
     */
    synchronized void visibleElsewhere() {
        holdOpen();
    }

    /** Whether this session could take the host's seat right now, which is what the browser offers. */
    synchronized boolean hostSeatFree(final WebSession asking) {
        return host == null && asking != null && asking.mayHost();
    }

    /**
     * Takes the host's seat for this session, if it is still free. One claim wins and the rest are told no,
     * so two browsers pressing at the same moment cannot both end up setting the table.
     */
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

    /**
     * Frees the host's seat once its browser has been gone a while and it is holding no game. A reload gets
     * the seat back, and a game in progress keeps it reserved, so only an abandoned server opens up.
     */
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

    /**
     * Whether the process ends once the last browser has gone. The console can hold it open instead, because
     * with the console on screen a server nobody is playing on is still plainly there and can be started again.
     *
     * <p>Turning it back on takes effect the next time a browser leaves, never at once: a server waiting for its
     * first player has nobody who has left yet, and must not count itself down before anyone arrives.
     */
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

    /**
     * The session a browser returns to, or a new one; null when there is no room for one. The link it came in on is
     * part of the key, so a browser on a guest's link can never pick up a session that may host.
     */
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
            timer.schedule(session::goneAWhile, draftHoldMillis, TimeUnit.MILLISECONDS);
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

    /** Every seat's dial reads again which pod seats are held, after a player went or came back. */
    void seatsChanged() {
        for (final WebSession session : byId.values()) {
            session.seatsChanged();
        }
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

    /** The loopback port a guest takes its seat on, or -1 while the host has no game open. */
    int hostPort() {
        final WebSession h = host;
        return h == null ? -1 : h.gamePort();
    }

    /**
     * Whether another player here already plays under this name, or the computer does at the host's table.
     * Case is ignored, because two players told apart only by it would be told apart by nobody. A browser that
     * has gone and holds no seat has given its name up.
     */
    boolean nameTaken(final String name, final WebSession asking) {
        for (final WebSession session : byId.values()) {
            if (session != asking && (session.attached() || session.hasGame() || session == host)
                    && name.equalsIgnoreCase(session.playerName())) {
                return true;
            }
        }
        final WebSession h = host;
        return h != null && h.computerNames().stream().anyMatch(name::equalsIgnoreCase);
    }

    /** Enough of the conversation for a browser that arrives late to see what was being said. */
    private static final int CHAT_KEPT = 60;

    private final Deque<ChatLine> said = new ArrayDeque<>();

    /**
     * A line one browser typed, given to every browser here. It belongs to the server rather than to a table,
     * so it survives a table opening, starting and closing, and people waiting for one can still talk.
     */
    synchronized void say(final WebSession from, final String text) {
        final String who = from.playerName();
        if (who == null) {
            return;
        }
        final ChatLine line = new ChatLine(who, text);
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
            session.tell(line);
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
        return new Presence(people);
    }

    /** Tells every browser who is here, whenever that changes. */
    void announcePresence() {
        final Presence now = presence();
        for (final WebSession session : byId.values()) {
            session.tell(now);
        }
    }

    /** Tells every browser without a seat that the host's seat has changed hands, or come free. */
    private void announceSeat() {
        for (final WebSession session : byId.values()) {
            session.hostSeatChanged();
        }
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
        return forwarded ? "Over the internet" : "Over the internet (forward port " + port + " first)";
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
        // Unless the router agreed to forward the port, the link reaches the router and stops there until somebody
        // forwards it by hand. Last, because it is the one least likely to work.
        final String external = FServerManager.getExternalAddress();
        if (external != null) {
            list.add(new Address(internetCaption(s.port(), portForwarded), s.inviteUrl(external)));
        }
        return list;
    }
}
