package forge.web;

import forge.gamemodes.net.server.PortForward;
import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.model.FModel;
import io.netty.handler.traffic.TrafficCounter;
import org.tinylog.Logger;

import java.util.function.Consumer;

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
    private volatile WebSessions sessions;
    private boolean quitWhenEmpty = true;
    private volatile PortForward forward;
    private volatile Forwarding forwarding = Forwarding.OFF;
    private volatile Consumer<Forwarding> onForwarding = f -> { };

    /** Where asking the router to forward the port stands. */
    enum Forwarding { OFF, ASKING, FORWARDED, REFUSED }

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
        if (forwardPort()) {
            openForward();
        }
    }

    /** Closes the port and drops every seat. Does nothing if it is already stopped. */
    synchronized void stop() {
        if (server == null) {
            return;
        }
        closeForward();
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

    /** Whether the router is asked to forward the port whenever the server starts. Kept between runs. */
    boolean forwardPort() {
        return Boolean.parseBoolean(FModel.getNetPreferences().getPref(FNetPref.WEB_PORT_FORWARD));
    }

    synchronized void forwardPort(final boolean value) {
        FModel.getNetPreferences().setPref(FNetPref.WEB_PORT_FORWARD, String.valueOf(value));
        FModel.getNetPreferences().save();
        if (!value) {
            closeForward();
        } else if (server != null) {
            openForward();
        }
    }

    /** Told of every change in where forwarding stands, on whatever thread the router's answer arrives. */
    synchronized void onForwarding(final Consumer<Forwarding> listener) {
        onForwarding = listener;
        listener.accept(forwarding);
    }

    Forwarding forwarding() {
        return forwarding;
    }

    private void openForward() {
        if (forward != null) {
            forward.close();
        }
        final PortForward asking = new PortForward(server.port());
        forward = asking;
        setForwarding(Forwarding.ASKING);
        // The answer arrives on a UPnP thread, which stop() may be waiting on while it holds this object's lock, so the
        // answer takes no lock. One for a forwarding already closed or replaced says nothing about the current one.
        asking.open(accepted -> {
            if (forward == asking) {
                if (accepted) {
                    Logger.info("The router is forwarding port {}.", asking.port());
                } else {
                    Logger.warn("The router did not forward port {}. Players on the internet cannot join until it is forwarded.", asking.port());
                }
                setForwarding(accepted ? Forwarding.FORWARDED : Forwarding.REFUSED);
            }
        });
    }

    private void closeForward() {
        if (forward != null) {
            forward.close();
            forward = null;
        }
        setForwarding(Forwarding.OFF);
    }

    private void setForwarding(final Forwarding value) {
        forwarding = value;
        if (sessions != null) {
            sessions.portForwarded(value == Forwarding.FORWARDED);
        }
        onForwarding.accept(value);
    }

    /** The host's own link, or null while stopped. */
    synchronized String url() {
        return server == null ? null : server.url();
    }

    /** A link for another player at one of this machine's addresses, or null while stopped. */
    synchronized String inviteUrl(final String address) {
        return server == null ? null : server.inviteUrl(address);
    }

    /** The bytes through the port since it was last started, or null while stopped. */
    synchronized TrafficCounter traffic() {
        return server == null ? null : server.traffic();
    }

    /** The port the browser connects on, or 0 while stopped. */
    synchronized int port() {
        return server == null ? 0 : server.port();
    }
}
