package forge.web;

import forge.gamemodes.net.server.PortForward;
import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.model.FModel;
import org.tinylog.Logger;

import java.util.function.Consumer;

/** The web server as something the console can stop and start again, with tokens that outlive a restart so old links still work. */
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
    /** Set at launch for a server that should outlast its browsers, such as one with no window to untick the choice in. */
    static final boolean KEEP_OPEN = Boolean.getBoolean("forge.web.keepOpen");
    private boolean quitWhenEmpty = !KEEP_OPEN;
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
        final WebServer bound;
        try {
            bound = new WebServer(fresh, hostToken, guestToken);
        } catch (final Exception e) {
            // The sessions' idle timer would otherwise quit Forge a few seconds after the port could not be bound
            fresh.shutdown();
            throw e;
        }
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
        } else {
            onForwarding.accept(forwarding);
        }
    }

    /** Told of every change in where forwarding stands, on whatever thread the router's answer arrives. */
    synchronized void onForwarding(final Consumer<Forwarding> listener) {
        onForwarding = listener;
        listener.accept(forwarding);
    }

    private void openForward() {
        if (forward != null) {
            forward.close();
        }
        final PortForward asking = new PortForward(server.port());
        forward = asking;
        setForwarding(Forwarding.ASKING);
        // The answer takes no lock, because it arrives on a UPnP thread that stop() may wait on while holding this object's lock
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
    synchronized ServerTraffic traffic() {
        return server == null ? null : server.traffic();
    }

    /** Takes no lock, so the console can ask on the event thread while a stop is under way. */
    int playersHere() {
        final WebSessions now = sessions;
        return now == null ? 0 : now.playersHere();
    }

    /** The port the browser connects on, or the one that will be asked for while stopped. */
    synchronized int port() {
        return server == null ? WebServer.configuredPort() : server.port();
    }
}
