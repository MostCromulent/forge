package forge.web;

import org.tinylog.Logger;

/**
 * The web server as something that can be stopped and started again while the process lives on. The console
 * drives it; without a console it is started once and never stopped.
 *
 * <p>The tokens are made once and outlive a restart, so a link a player was already given still works after the
 * server has been off. Everything else is made afresh: stopping drops every seat, so a game in progress ends
 * with it and the host's seat is free again when the server comes back.
 */
final class WebService {
    private final WebGuiBase ui;
    private final long idleMillis;
    private final Runnable onQuit;
    /** True when a console is on screen, which is itself the sign that Forge is running and waiting. */
    private final boolean consoleShown;
    private final String hostToken;
    private final String guestToken;
    private WebServer server;
    private WebSessions sessions;
    private boolean quitWhenEmpty = true;

    WebService(final WebGuiBase ui, final long idleMillis, final Runnable onQuit, final boolean consoleShown,
            final String hostToken, final String guestToken) {
        this.ui = ui;
        this.idleMillis = idleMillis;
        this.onQuit = onQuit;
        this.consoleShown = consoleShown;
        this.hostToken = hostToken;
        this.guestToken = guestToken;
    }

    synchronized boolean running() {
        return server != null;
    }

    /** Binds the port. Does nothing if it is already bound. */
    synchronized void start() throws InterruptedException {
        if (server != null) {
            return;
        }
        final WebSessions fresh = new WebSessions(ui, idleMillis, onQuit);
        final WebServer bound = new WebServer(fresh, hostToken, guestToken);
        fresh.setServer(bound);
        if (consoleShown) {
            fresh.visibleElsewhere();
        }
        fresh.quitWhenEmpty(quitWhenEmpty);
        sessions = fresh;
        server = bound;
        Logger.info("Forge web UI: {}", bound.url());
    }

    /** Closes the port and drops every seat. Does nothing if it is already stopped. */
    synchronized void stop() {
        if (server == null) {
            return;
        }
        sessions.shutdown();
        server.close();
        sessions = null;
        server = null;
        Logger.info("The web server has stopped.");
    }

    synchronized void quitWhenEmpty(final boolean value) {
        quitWhenEmpty = value;
        if (sessions != null) {
            sessions.quitWhenEmpty(value);
        }
    }

    /** The host's own link, or null while stopped. */
    synchronized String url() {
        return server == null ? null : server.url();
    }

    /** A link for another player at one of this machine's addresses, or null while stopped. */
    synchronized String inviteUrl(final String address) {
        return server == null ? null : server.inviteUrl(address);
    }
}
