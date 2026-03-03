package forge.net;

import forge.deck.Deck;
import forge.game.Game;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.interfaces.ILobbyListener;
import forge.interfaces.IUpdateable;
import forge.model.FModel;
import forge.localinstance.properties.ForgePreferences.FPref;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified network test harness for running automated games over real TCP.
 *
 * Follows the canonical server/client setup pattern from TestTrueNetworkTraffic:
 * 1. FServerManager.startServer(port)
 * 2. ServerGameLobby + setLobby + setLobbyListener
 * 3. Configure slots (AI for host, OPEN for remote)
 * 4. FGameClient.connect() with HeadlessNetworkGuiGame
 * 5. Wait for slot -> REMOTE
 * 6. Mark slots ready, lobby.startGame()
 * 7. convertToAI(remoteSlot) — remote players become server-side AI
 * 8. Poll for game.isGameOver()
 * 9. Cleanup
 *
 * Supports 2-4 players via builder pattern.
 */
public class UnifiedNetworkHarness {

    private static final String[] PLAYER_NAMES = {"Alice", "Bob", "Charlie", "Dave"};

    private final int playerCount;
    private final int port;
    private final long gameTimeoutMs;
    private final long connectionTimeoutMs;
    private final List<Deck> decks;

    private UnifiedNetworkHarness(Builder builder) {
        this.playerCount = builder.playerCount;
        this.port = builder.port;
        this.gameTimeoutMs = builder.gameTimeoutMs;
        this.connectionTimeoutMs = builder.connectionTimeoutMs;
        this.decks = builder.decks;
    }

    /**
     * Execute a full network game and return the result.
     */
    public GameResult execute() {
        long startTime = System.currentTimeMillis();
        FServerManager server = FServerManager.getInstance();
        List<HeadlessNetworkClient> clients = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean gameStarted = false;

        try {
            // 1. Start server
            server.startServer(port);
            if (!server.isHosting()) {
                return GameResult.failure("Server failed to start on port " + port,
                        System.currentTimeMillis() - startTime, playerCount);
            }

            // 2. Create lobby and configure server side
            ServerGameLobby lobby = new ServerGameLobby();
            server.setLobby(lobby);

            server.setLobbyListener(new ILobbyListener() {
                @Override public void message(String source, String message) { }
                @Override public void update(GameLobbyData state, int slot) { }
                @Override public void close() { }
                @Override public ClientGameLobby getLobby() { return null; }
            });

            lobby.setListener(new IUpdateable() {
                @Override public void update(boolean fullUpdate) { }
                @Override public void update(int slot, LobbySlotType type) { }
            });

            // 3. Configure slot 0: AI host
            LobbySlot slot0 = lobby.getSlot(0);
            slot0.setType(LobbySlotType.AI);
            slot0.setName(PLAYER_NAMES[0]);
            slot0.setDeck(decks.get(0));
            slot0.setIsReady(true);

            // 3b. Add extra OPEN slots for 3+ player games
            // ServerGameLobby starts with 2 slots (LOCAL + OPEN)
            for (int i = lobby.getNumberOfSlots(); i < playerCount; i++) {
                lobby.addSlot();
            }

            // 4. Pre-load decks on remote slots and connect clients
            int remoteCount = playerCount - 1;
            for (int i = 0; i < remoteCount; i++) {
                int slotIndex = i + 1;
                LobbySlot slot = lobby.getSlot(slotIndex);
                slot.setDeck(decks.get(slotIndex));

                // Connect a headless client
                // GameClientHandler sends FPref.PLAYER_NAME in LoginEvent, so set it
                String clientName = PLAYER_NAMES[slotIndex];
                FModel.getPreferences().setPref(FPref.PLAYER_NAME, clientName);
                HeadlessNetworkClient hClient = new HeadlessNetworkClient(clientName, "localhost", port);
                clients.add(hClient);

                boolean connected = hClient.connect(connectionTimeoutMs);
                if (!connected) {
                    return GameResult.failure("Client " + clientName + " failed to connect within " +
                            connectionTimeoutMs + "ms", System.currentTimeMillis() - startTime, playerCount);
                }

                // Wait for slot to become REMOTE
                long waitStart = System.currentTimeMillis();
                while (slot.getType() != LobbySlotType.REMOTE) {
                    if (System.currentTimeMillis() - waitStart > connectionTimeoutMs) {
                        return GameResult.failure("Slot " + slotIndex + " did not become REMOTE within timeout. " +
                                "Type: " + slot.getType(), System.currentTimeMillis() - startTime, playerCount);
                    }
                    Thread.sleep(50);
                }

                // Mark slot ready on server side
                slot.setIsReady(true);

                // Stagger client connections slightly to avoid race conditions
                if (i < remoteCount - 1) {
                    Thread.sleep(100);
                }
            }

            // 5. Start the game
            Runnable startGameRunnable = lobby.startGame();
            if (startGameRunnable == null) {
                return GameResult.failure("lobby.startGame() returned null",
                        System.currentTimeMillis() - startTime, playerCount);
            }
            startGameRunnable.run();

            HostedMatch hostedMatch = lobby.getHostedMatch();
            if (hostedMatch == null) {
                return GameResult.failure("HostedMatch is null after startGame",
                        System.currentTimeMillis() - startTime, playerCount);
            }

            Game game = hostedMatch.getGame();
            if (game == null) {
                return GameResult.failure("Game is null after startGame",
                        System.currentTimeMillis() - startTime, playerCount);
            }

            gameStarted = true;

            // 6. Convert all remote players to AI on server side
            for (int i = 0; i < remoteCount; i++) {
                int slotIndex = i + 1;
                LobbySlot slot = lobby.getSlot(slotIndex);
                server.convertToAI(slotIndex, slot.getName());
            }

            // 7. Poll for game completion
            while (!game.isGameOver()) {
                if (System.currentTimeMillis() - startTime > gameTimeoutMs) {
                    int turn = game.getPhaseHandler().getTurn();
                    return GameResult.timeout(turn, System.currentTimeMillis() - startTime, playerCount);
                }
                Thread.sleep(500);
            }

            // 8. Collect results
            int turnCount = game.getPhaseHandler().getTurn();
            int serverSendErrors = server.getTotalSendErrors();
            int serverDispatchErrors = server.getTotalDispatchErrors();
            int clientSendErrors = 0;
            for (HeadlessNetworkClient hc : clients) {
                clientSendErrors += hc.getClient().getSendErrorCount();
            }

            int serverDeserializationErrors = server.getTotalDeserializationErrors();

            if (serverDispatchErrors > 0) {
                errors.add("Server dispatch errors: " + serverDispatchErrors);
            }
            if (serverDeserializationErrors > 0) {
                errors.add("Server deserialization errors: " + serverDeserializationErrors);
            }

            // Check client received expected protocol calls
            for (HeadlessNetworkClient hc : clients) {
                HeadlessNetworkGuiGame gui = hc.getGuiGame();
                if (!gui.isOpenViewCalled()) {
                    errors.add("Client " + hc.getAssignedSlot() + " never received openView");
                }
                if (gui.getSetGameViewCount() == 0) {
                    errors.add("Client " + hc.getAssignedSlot() + " received 0 setGameView calls");
                }
                if (gui.getErrorDialogCount() > 0) {
                    for (String errMsg : gui.getErrorMessages()) {
                        errors.add("Client " + hc.getAssignedSlot() + " error dialog: " + errMsg);
                    }
                }
            }

            String winner = null;
            if (game.getOutcome() != null && game.getOutcome().getWinningPlayer() != null) {
                winner = game.getOutcome().getWinningPlayer().getPlayer().getName();
            }

            long duration = System.currentTimeMillis() - startTime;

            return new GameResult(true, gameStarted, turnCount,
                    serverSendErrors + clientSendErrors + serverDispatchErrors,
                    playerCount, winner,
                    null, errors, duration);

        } catch (Exception e) {
            errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
            long duration = System.currentTimeMillis() - startTime;
            return new GameResult(false, gameStarted, 0, 0, playerCount, null,
                    e.getMessage(), errors, duration);
        } finally {
            // Cleanup
            for (HeadlessNetworkClient hc : clients) {
                try { hc.close(); } catch (Exception ignored) { }
            }
            if (server.isHosting()) {
                server.stopServer();
            }
        }
    }

    // ========================================
    // Builder
    // ========================================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int playerCount = 2;
        private int port = -1;
        private long gameTimeoutMs = 120_000;
        private long connectionTimeoutMs = 10_000;
        private List<Deck> decks = new ArrayList<>();

        public Builder playerCount(int playerCount) {
            if (playerCount < 2 || playerCount > 4) {
                throw new IllegalArgumentException("playerCount must be 2-4, got " + playerCount);
            }
            this.playerCount = playerCount;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder gameTimeoutMs(long timeoutMs) {
            this.gameTimeoutMs = timeoutMs;
            return this;
        }

        public Builder connectionTimeoutMs(long timeoutMs) {
            this.connectionTimeoutMs = timeoutMs;
            return this;
        }

        public Builder decks(List<Deck> decks) {
            this.decks = new ArrayList<>(decks);
            return this;
        }

        public Builder addDeck(Deck deck) {
            this.decks.add(deck);
            return this;
        }

        public UnifiedNetworkHarness build() {
            if (port < 0) {
                port = PortAllocator.allocatePort();
            }
            // Ensure we have enough decks
            while (decks.size() < playerCount) {
                decks.add(TestDeckLoader.getRandomPrecon());
            }
            return new UnifiedNetworkHarness(this);
        }
    }

    // ========================================
    // GameResult
    // ========================================

    public static class GameResult {
        public final boolean success;
        public final boolean gameStarted;
        public final int turnCount;
        public final int sendErrors;
        public final int playerCount;
        public final String winner;
        public final String failureReason;
        public final List<String> errors;
        public final long durationMs;

        GameResult(boolean success, boolean gameStarted, int turnCount, int sendErrors,
                   int playerCount, String winner, String failureReason,
                   List<String> errors, long durationMs) {
            this.success = success;
            this.gameStarted = gameStarted;
            this.turnCount = turnCount;
            this.sendErrors = sendErrors;
            this.playerCount = playerCount;
            this.winner = winner;
            this.failureReason = failureReason;
            this.errors = errors != null ? errors : new ArrayList<>();
            this.durationMs = durationMs;
        }

        static GameResult failure(String reason, long durationMs, int playerCount) {
            return new GameResult(false, false, 0, 0, playerCount, null,
                    reason, new ArrayList<>(), durationMs);
        }

        static GameResult timeout(int turnCount, long durationMs, int playerCount) {
            return new GameResult(false, true, turnCount, 0, playerCount, null,
                    "Game did not complete within timeout (turn " + turnCount + ")",
                    new ArrayList<>(), durationMs);
        }

        @Override
        public String toString() {
            if (success) {
                return String.format("SUCCESS: %dp game completed in %d turns, %dms, winner=%s, sendErrors=%d",
                        playerCount, turnCount, durationMs, winner, sendErrors);
            } else {
                return String.format("FAILURE: %dp game, reason=%s, started=%s, turns=%d, %dms",
                        playerCount, failureReason, gameStarted, turnCount, durationMs);
            }
        }

        /**
         * Serialize to a parseable result line for multi-process execution.
         */
        public String toResultLine() {
            return String.format("RESULT:%s:%d:%d:%d:%d:%s:%s",
                    success ? "SUCCESS" : "FAILURE",
                    playerCount, turnCount, sendErrors, durationMs,
                    winner != null ? winner : "none",
                    failureReason != null ? failureReason.replace(":", ";") : "none");
        }

        /**
         * Parse a result line from multi-process execution.
         */
        public static GameResult fromResultLine(String line) {
            if (!line.startsWith("RESULT:")) {
                return failure("Invalid result line: " + line, 0, 0);
            }
            String[] parts = line.substring("RESULT:".length()).split(":", 7);
            if (parts.length < 7) {
                return failure("Malformed result line: " + line, 0, 0);
            }
            try {
                boolean success = "SUCCESS".equals(parts[0]);
                int playerCount = Integer.parseInt(parts[1]);
                int turnCount = Integer.parseInt(parts[2]);
                int sendErrors = Integer.parseInt(parts[3]);
                long durationMs = Long.parseLong(parts[4]);
                String winner = "none".equals(parts[5]) ? null : parts[5];
                String reason = "none".equals(parts[6]) ? null : parts[6].replace(";", ":");
                return new GameResult(success, true, turnCount, sendErrors, playerCount,
                        winner, reason, new ArrayList<>(), durationMs);
            } catch (NumberFormatException e) {
                return failure("Parse error: " + e.getMessage() + " in: " + line, 0, 0);
            }
        }
    }
}
