package forge.net;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import forge.gui.GuiBase;
import forge.GuiDesktop;
import forge.Singletons;
import forge.gamemodes.net.NetworkLogConfig;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.IHasForgeLog;

/**
 * Runs one remote player in its own JVM, drawing to the real desktop match screen.
 *
 * <p>Mirrors {@link HeadlessClientRunner}, and differs in the one way that matters: its GUI is
 * the screen a person would see, so opening a match, building the fields and painting the zones
 * all really happen. Those are the paths a headless client cannot reach, and the ones most
 * likely to break for a client seeded by a snapshot.
 *
 * <p>Any exception that reaches a thread's top level - the event dispatch thread included - is
 * recorded and reported as a failure. That is the point of the whole exercise: the defects this
 * is meant to catch are logged and swallowed by the running game, so completing is not evidence
 * of anything by itself.
 *
 * <p>Args: username hostname port staggerMs connectTimeoutMs gameTimeoutMs [batchId]
 */
public final class GuiClientRunner implements IHasForgeLog {

    private static final AtomicReference<Throwable> FIRST_ESCAPE = new AtomicReference<>();
    private static final AtomicReference<IGameController> CONTROLLER = new AtomicReference<>();
    private static final AtomicReference<forge.game.player.PlayerView> ME = new AtomicReference<>();
    private static final AtomicReference<HeadlessNetworkClient> CLIENT = new AtomicReference<>();
    private static final ScheduledExecutorService RESPONDER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "GuiClient-Responder");
                thread.setDaemon(true);
                return thread;
            });

    private GuiClientRunner() { }

    public static void main(final String[] args) {
        int exit = 1;
        HeadlessNetworkClient client = null;
        try {
            if (args.length < 6) {
                System.out.println("RESULT:FAIL:usage");
                System.exit(2);
            }
            final String username = args[0];
            final String hostname = args[1];
            final int port = Integer.parseInt(args[2]);
            final long staggerMs = Long.parseLong(args[3]);
            final long connectTimeoutMs = Long.parseLong(args[4]);
            final long gameTimeoutMs = Long.parseLong(args[5]);

            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                FIRST_ESCAPE.compareAndSet(null, error);
                netLog.error(error, "[GuiClient] uncaught on {}", thread.getName());
            });

            startDesktop();

            NetworkLogConfig.setTestMode(true);
            if (args.length > 6 && args[6] != null && !args[6].isEmpty()) {
                NetworkLogConfig.setBatchId(args[6]);
            }
            NetworkLogConfig.setInstanceSuffix("gui-" + username.replaceAll("\\W+", ""));

            Thread.sleep(staggerMs);

            client = new HeadlessNetworkClient(username, hostname, port);
            CLIENT.set(client);
            client.useGui(GuiMatchScreenClient.wrap(GuiBase.getInterface().getNewGuiGame(),
                    new HeadlessNetworkGuiGame(), GuiClientRunner::respond));

            if (!client.connect(connectTimeoutMs)) {
                System.out.println("RESULT:FAIL:connect");
                System.exit(1);
            }

            Thread.sleep(500);
            client.setReady();
            Thread.sleep(200);

            System.out.println("CONNECTED:slot=" + client.getAssignedSlot());
            System.out.flush();

            dumpThreadsIfStuck(client);

            client.waitForGameStart(gameTimeoutMs);
            client.waitForGameFinish(gameTimeoutMs);

            final Throwable escaped = FIRST_ESCAPE.get();
            if (escaped != null) {
                System.out.println("RESULT:FAIL:uncaught:" + escaped.getClass().getSimpleName()
                        + ":" + escaped.getMessage());
            } else {
                System.out.println("RESULT:OK"
                        + ":slot=" + client.getAssignedSlot()
                        + ":deltas=" + client.getDeltaPacketsReceived()
                        + ":bytes=" + client.getTotalDeltaBytes()
                        + ":mismatches=" + client.getEventStateMismatches());
                exit = 0;
            }
        } catch (final Throwable t) {
            System.out.println("RESULT:FAIL:" + t.getClass().getSimpleName() + ":" + t.getMessage());
            t.printStackTrace();
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (final Exception ignored) {
                    // shutting down anyway
                }
            }
            NetworkLogConfig.closeThreadLogger();
        }
        System.exit(exit);
    }

    /**
     * Bring up the desktop the way the application does, minus the parts a test must not have.
     *
     * <p>The startup version check reaches the network, which a batch of these would do once
     * per client for no benefit.
     */
    private static void startDesktop() {
        System.setProperty("sun.java2d.d3d", "false");
        GuiBase.setInterface(new GuiDesktop());
        Singletons.initializeOnce(true);
        FModel.getPreferences().setPref(FPref.CHECK_SNAPSHOT_AT_STARTUP, false);
        FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, false);
        Singletons.getControl().initialize();
    }

    /**
     * Answer what the screen has just been asked to show.
     *
     * <p>Deliberately the simplest thing that keeps a game moving: the host is an AI and
     * supplies the board this exists to draw, so the player being rendered does not also have
     * to play well.
     */
    private static void respond(final Method method, final Object[] args) {
        switch (method.getName()) {
            case "applyDelta":
                // The auto-responding GUI reports these itself, and it is not in use here. Without
                // this the run counts no traffic at all and reports that it never reached the
                // network, which is the opposite of what it just did.
                if (args.length > 0 && args[0] instanceof forge.gamemodes.net.DeltaPacket packet
                        && CLIENT.get() != null) {
                    CLIENT.get().onDeltaPacketReceived(packet);
                }
                break;
            case "afterGameEnd":
                if (CLIENT.get() != null) {
                    CLIENT.get().onGameEnd();
                }
                break;
            case "setGameController":
            case "setOriginalGameController":
                if (args.length > 1 && args[1] instanceof IGameController controller) {
                    CONTROLLER.set(controller);
                }
                if (args.length > 0 && args[0] instanceof forge.game.player.PlayerView player) {
                    ME.set(player);
                }
                break;
            case "showPromptMessage":
                // Choosing who goes first is answered by clicking a portrait, not a button, so
                // the buttons stay disabled and nothing else here would ever reply to it.
                if (args.length > 1 && args[1] instanceof String message
                        && message.contains("Click on the portrait") && ME.get() != null) {
                    later(() -> CONTROLLER.get().selectPlayer(ME.get(), null));
                }
                break;
            case "updateButtons":
                // The enabled-flags form and the labelled form differ in where they sit.
                final boolean enabled = args.length == 4 ? Boolean.TRUE.equals(args[1])
                        : args.length == 6 && Boolean.TRUE.equals(args[3]);
                if (enabled) {
                    later(() -> CONTROLLER.get().selectButtonOk());
                }
                break;
            case "setSelectables":
                if (args[0] instanceof Iterable<?> cards) {
                    for (final Object card : cards) {
                        if (card instanceof forge.game.card.CardView view) {
                            later(() -> CONTROLLER.get().selectCard(view, null, null));
                            break;
                        }
                    }
                }
                break;
            default:
                break;
        }
    }

    /**
     * Print every thread's stack once, if the game has not started shortly after connecting.
     *
     * <p>A client that stops drawing stops answering too, and the run then reports only that it
     * timed out. The screen does its work on the event dispatch thread, so whatever it is
     * waiting for is in one of these stacks.
     */
    private static void dumpThreadsIfStuck(final HeadlessNetworkClient client) {
        final Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(90000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (client.getGameView() != null && client.isGameInProgress()) {
                return;
            }
            final StringBuilder dump = new StringBuilder("STALLED: thread dump\n");
            Thread.getAllStackTraces().forEach((thread, frames) -> {
                dump.append("  \"").append(thread.getName()).append("\" ").append(thread.getState()).append('\n');
                for (final StackTraceElement frame : frames) {
                    dump.append("      ").append(frame).append('\n');
                }
            });
            System.out.println(dump);
            System.out.flush();
        }, "GuiClient-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void later(final Runnable action) {
        RESPONDER.schedule(() -> {
            if (CONTROLLER.get() == null) {
                return;
            }
            try {
                action.run();
            } catch (final RuntimeException e) {
                netLog.error(e, "[GuiClient] responding failed");
            }
        }, 50, TimeUnit.MILLISECONDS);
    }
}
