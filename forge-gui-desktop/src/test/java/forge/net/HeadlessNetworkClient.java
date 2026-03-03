package forge.net;

import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.interfaces.ILobbyListener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless network client for automated testing with actual network traffic.
 *
 * Connects to a Forge server as a remote player, enabling:
 * - Full serialization testing over real TCP
 * - Protocol dispatch verification
 * - Multi-player game configuration
 */
public class HeadlessNetworkClient implements AutoCloseable {

    private static final String LOG_PREFIX = "[HeadlessClient]";

    private final String username;
    private final String hostname;
    private final int port;

    private FGameClient client;
    private ClientGameLobby lobby;
    private HeadlessNetworkGuiGame guiGame;

    // Connection state
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger assignedSlot = new AtomicInteger(-1);

    public HeadlessNetworkClient(String username, String hostname, int port) {
        this.username = username;
        this.hostname = hostname;
        this.port = port;
    }

    /**
     * Connect to the server and wait for lobby assignment.
     *
     * @param timeoutMs Maximum time to wait for connection
     * @return true if connected successfully
     */
    public boolean connect(long timeoutMs) {
        System.out.printf("%s Connecting to %s:%d as '%s'%n", LOG_PREFIX, hostname, port, username);

        try {
            guiGame = new HeadlessNetworkGuiGame();

            client = new FGameClient(username, "0", guiGame, hostname, port);

            lobby = new ClientGameLobby();

            client.addLobbyListener(new ClientLobbyListener());

            client.connect();

            boolean success = connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (success) {
                connected.set(true);
                System.out.printf("%s Connected successfully, assigned slot %d%n",
                        LOG_PREFIX, assignedSlot.get());
            } else {
                System.err.printf("%s Connection timeout after %dms%n", LOG_PREFIX, timeoutMs);
            }
            return success;

        } catch (Exception e) {
            System.err.printf("%s Connection failed: %s%n", LOG_PREFIX, e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public int getAssignedSlot() {
        return assignedSlot.get();
    }

    public ClientGameLobby getLobby() {
        return lobby;
    }

    public FGameClient getClient() {
        return client;
    }

    public HeadlessNetworkGuiGame getGuiGame() {
        return guiGame;
    }

    /**
     * Mark this client as ready in the lobby.
     */
    public void setReady() {
        if (client != null && connected.get()) {
            UpdateLobbyPlayerEvent event = UpdateLobbyPlayerEvent.isReadyUpdate(true);
            int slot = assignedSlot.get();
            if (slot >= 0 && lobby != null) {
                lobby.applyToSlot(slot, event);
            }
            client.send(event);
        }
    }

    @Override
    public void close() {
        System.out.printf("%s Disconnecting%n", LOG_PREFIX);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                // Ignore close errors
            }
        }
        connected.set(false);
    }

    /**
     * Lobby listener that handles server updates.
     */
    private class ClientLobbyListener implements ILobbyListener {
        @Override
        public void update(GameLobbyData state, int slot) {
            lobby.setData(state);

            if (slot >= 0) {
                int previousSlot = assignedSlot.get();
                assignedSlot.set(slot);
                lobby.setLocalPlayer(slot);

                // Signal connected once we have a valid slot assignment
                if (previousSlot == -1) {
                    connectedLatch.countDown();
                }
            }
        }

        @Override
        public void message(String source, String message) {
            System.out.printf("%s Chat: %s: %s%n", LOG_PREFIX, source, message);
        }

        @Override
        public void close() {
            System.out.printf("%s Connection closed by server%n", LOG_PREFIX);
            connected.set(false);
        }

        @Override
        public ClientGameLobby getLobby() {
            return lobby;
        }
    }
}
