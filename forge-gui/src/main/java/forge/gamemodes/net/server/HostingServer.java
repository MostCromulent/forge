package forge.gamemodes.net.server;

import forge.gamemodes.match.input.InputSynchronized;
import forge.player.PlayerControllerHuman;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The server this process is hosting, if any. Match and input code ask here instead of
 * calling {@link FServerManager#getInstance()}, so a game that never hosts never loads
 * the network stack.
 */
public final class HostingServer {

    @FunctionalInterface
    public interface AfkTimeout {
        AfkTimeout NOOP = () -> {};
        void cancel();
    }

    /** What match and input code need from a running server. */
    public interface Server {
        AfkTimeout armAfkTimeout(PlayerControllerHuman controller, InputSynchronized input);
    }

    private static final Set<Server> active = new CopyOnWriteArraySet<>();

    private HostingServer() {
    }

    static void add(final Server server) {
        active.add(server);
    }

    static void remove(final Server server) {
        active.remove(server);
    }

    /** True while any server in this process is running. */
    public static boolean isHosting() {
        return !active.isEmpty();
    }

    /** Asks each running server. Only the one whose match holds the controller arms a timeout. */
    public static AfkTimeout armAfkTimeout(final PlayerControllerHuman controller, final InputSynchronized input) {
        for (final Server server : active) {
            final AfkTimeout timeout = server.armAfkTimeout(controller, input);
            if (timeout != AfkTimeout.NOOP) {
                return timeout;
            }
        }
        return AfkTimeout.NOOP;
    }
}
