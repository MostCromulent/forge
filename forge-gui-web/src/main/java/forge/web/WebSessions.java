package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.gamemodes.net.server.FServerManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "WebStartIdle");
        t.setDaemon(true);
        return t;
    });
    /** Covers a browser that never opens at all; once the host has one, its own session takes over. */
    private final ScheduledFuture<?> unopened;

    WebSessions(final WebGuiBase ui, final long idleMillis, final Runnable onQuit) {
        this.ui = ui;
        this.idleMillis = idleMillis;
        this.onQuit = onQuit;
        unopened = timer.schedule(onQuit, idleMillis, TimeUnit.MILLISECONDS);
    }

    /** The server is built around this endpoint, so it can only be handed over once it exists. */
    void setServer(final WebServer server) {
        this.server = server;
    }

    @Override
    public void connected(final BrowserChannel channel, final String clientId, final boolean local) {
        final WebSession session = sessionFor(channel, clientId, local);
        byChannel.put(channel, session);
        session.connected(channel);
    }

    private synchronized WebSession sessionFor(final BrowserChannel channel, final String clientId, final boolean local) {
        final String key = clientId.isEmpty() ? String.valueOf(System.identityHashCode(channel)) : clientId;
        final WebSession known = byId.get(key);
        if (known != null) {
            return known;
        }
        // The game belongs to the machine running it, so only a browser on that machine can host. A guest that
        // arrives before there is a game still gets a session, and is told to join once the host opens one.
        final boolean hosting = host == null && local;
        final WebSession session = new WebSession(ui, this, hosting, idleMillis, onQuit);
        if (hosting) {
            unopened.cancel(false);
            host = session;
        }
        byId.put(key, session);
        return session;
    }

    @Override
    public void disconnected(final BrowserChannel channel) {
        final WebSession session = byChannel.remove(channel);
        if (session != null) {
            session.disconnected(channel);
        }
    }

    @Override
    public void onMessage(final BrowserChannel channel, final JsonObject message) {
        final WebSession session = byChannel.get(channel);
        if (session != null) {
            session.onMessage(channel, message);
        }
    }

    /** Drops every seat and stops the server, guests first so none of them outlives the game they were in. */
    synchronized void shutdown() {
        unopened.cancel(false);
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

    boolean isHost(final WebSession session) {
        return host == session;
    }

    /** Tells the guests waiting on a game that there is now one to join. */
    void hostGameOpened() {
        forEachGuest(WebSession::joinHostGame);
    }

    /** Tells the guests their game has gone. The server stays up, so nothing else would tell them. */
    void hostGameClosed() {
        forEachGuest(WebSession::gameGone);
    }

    private void forEachGuest(final java.util.function.Consumer<WebSession> action) {
        for (final WebSession session : byId.values()) {
            if (session != host) {
                action.accept(session);
            }
        }
    }

    /** Every link that would reach this machine, most likely first. */
    JsonArray inviteUrls() {
        final JsonArray list = new JsonArray();
        final WebServer s = server;
        if (s == null) {
            return list;
        }
        final String external = FServerManager.getExternalAddress();
        if (external != null) {
            list.add(invite("Over the internet", s.inviteUrl(external)));
        }
        for (final Map.Entry<String, String> e : FServerManager.getAllLocalAddresses().entrySet()) {
            list.add(invite(e.getKey(), s.inviteUrl(e.getValue())));
        }
        return list;
    }

    private static JsonObject invite(final String label, final String url) {
        final JsonObject j = new JsonObject();
        j.addProperty("label", label);
        j.addProperty("url", url);
        return j;
    }
}
