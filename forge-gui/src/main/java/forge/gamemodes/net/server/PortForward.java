package forge.gamemodes.net.server;

import forge.gui.GuiBase;
import forge.util.IHasForgeLog;
import org.jupnp.UpnpService;
import org.jupnp.UpnpServiceImpl;
import org.jupnp.model.meta.Device;
import org.jupnp.registry.Registry;
import org.jupnp.support.igd.PortMappingListener;
import org.jupnp.support.model.PortMapping;
import org.jupnp.util.SpecificationViolationReporter;

import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;

/**
 * Asks the router, over UPnP, to forward a TCP port to this machine, so players on the internet can reach it.
 * Closing removes the forwarding again.
 */
public final class PortForward implements IHasForgeLog {
    /** How long a router has to confirm the forwarding before it counts as refused. */
    private static final long ANSWER_MILLIS = 5000;

    private final int port;
    private UpnpService service;

    public PortForward(final int port) {
        this.port = port;
    }

    public int port() {
        return port;
    }

    /** Starts asking. result is told once: true when a router accepted the forwarding, false when none did. */
    public synchronized void open(final Consumer<Boolean> result) {
        close();
        try {
            final PortMapping mapping = new PortMapping(port, FServerManager.getLocalAddress(), PortMapping.Protocol.TCP, "Forge");
            // Gateways routinely break the UPnP spec in ways jupnp tolerates; don't log each one
            SpecificationViolationReporter.disableReporting();
            service = new UpnpServiceImpl(GuiBase.getInterface().getUpnpPlatformService());
            service.startup();
            final Listener listener = new Listener(mapping, result);
            service.getRegistry().addListener(listener);
            service.getControlPoint().search();
            new Timer("upnp-timeout", true).schedule(new TimerTask() {
                @Override
                public void run() {
                    if (listener.finish(false)) {
                        netLog.warn("UPnP: no gateway confirmed a mapping for port {} within 5 seconds", port);
                    }
                }
            }, ANSWER_MILLIS);
        } catch (LinkageError | Exception e) {
            // jupnp is unavailable on iOS/MobiVM (provided scope, no platform UPnP service), so its classes fail to
            // load there. NoClassDefFoundError is a LinkageError, so forwarding fails quietly instead of taking the
            // server down, while fatal Errors (OutOfMemoryError etc.) still propagate.
            netLog.error(e, "UPnP mapping unavailable");
            result.accept(false);
        }
    }

    /** Removes the forwarding, if there is one. */
    public synchronized void close() {
        if (service == null) {
            return;
        }
        try {
            // Shutting down tells the router to drop every mapping this service made
            service.shutdown();
        } catch (Exception | AssertionError e) {
            // The JDK wraps a failed multicast leave in AssertionError, which jupnp doesn't catch
            netLog.debug("UPnP shutdown incomplete: {}", e.toString());
        }
        service = null;
    }

    // The superclass maps the port inside deviceAdded, so by the time super.deviceAdded returns the outcome is known
    private static final class Listener extends PortMappingListener {
        private final Consumer<Boolean> result;
        private boolean finished;

        Listener(final PortMapping mapping, final Consumer<Boolean> result) {
            super(mapping);
            this.result = result;
        }

        @Override
        public synchronized void deviceAdded(final Registry registry, final Device device) {
            super.deviceAdded(registry, device);
            if (!activePortMappings.isEmpty()) {
                finish(true);
            }
        }

        @Override
        protected void handleFailureMessage(final String message) {
            super.handleFailureMessage(message);
            finish(false);
        }

        /** Reports the outcome the first time only. Returns whether this call was the one that reported. */
        synchronized boolean finish(final boolean accepted) {
            if (finished) {
                return false;
            }
            finished = true;
            result.accept(accepted);
            return true;
        }
    }
}
