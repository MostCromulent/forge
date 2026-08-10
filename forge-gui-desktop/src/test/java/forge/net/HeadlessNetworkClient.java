package forge.net;

import forge.game.GameView;
import forge.gamemodes.net.DeltaPacket;
import forge.gamemodes.net.ChatMessage;
import forge.util.IHasForgeLog;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.interfaces.IGameController;
import forge.interfaces.ILobbyListener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Headless TCP client that connects to a Forge server as a remote player.
 * Receives delta sync packets and auto-responds to game prompts, enabling
 * true network traffic measurement without a display.
 *
 * <p>Used by {@link UnifiedNetworkHarness} as the remote player implementation.
 * Internally extends {@link HeadlessNetworkGuiGame} via {@code DeltaLoggingGuiGame}
 * to process delta packets and provide auto-response behavior.
 */
public class HeadlessNetworkClient implements AutoCloseable, IHasForgeLog {

    private final String username;
    private final String hostname;
    private final int port;

    private FGameClient client;
    private ClientGameLobby lobby;
    private DeltaLoggingGuiGame guiGame;
    // Set when the caller brings its own GUI, as the desktop match screen does. The
    // auto-responding one is built here only when nothing was supplied.
    private forge.gui.interfaces.IGuiGame suppliedGui;

    // Connection state
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private final CountDownLatch gameStartedLatch = new CountDownLatch(1);
    private final CountDownLatch gameFinishedLatch = new CountDownLatch(1);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean gameInProgress = new AtomicBoolean(false);
    private final AtomicInteger assignedSlot = new AtomicInteger(-1);

    // Metrics
    private final AtomicLong deltaPacketsReceived = new AtomicLong(0);
    private final AtomicLong totalDeltaBytes = new AtomicLong(0);
    private final AtomicLong eventStateMismatches = new AtomicLong(0);

    public HeadlessNetworkClient(String username, String hostname, int port) {
        this.username = username;
        this.hostname = hostname;
        this.port = port;
    }

    public boolean connect(long timeoutMs) {
        netLog.info("Connecting to {}:{} as '{}'", hostname, port, username);

        try {
            final forge.gui.interfaces.IGuiGame gui;
            if (suppliedGui != null) {
                gui = suppliedGui;
            } else {
                guiGame = new DeltaLoggingGuiGame(this);
                gui = guiGame;
            }
            client = new FGameClient(username, gui, hostname, port);
            lobby = new ClientGameLobby();
            client.addLobbyListener(new ClientLobbyListener());
            client.connect();

            boolean success = connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (success) {
                connected.set(true);
                netLog.info("Connected successfully, assigned slot {}",
                        assignedSlot.get());
            } else {
                netLog.error("Connection timeout after {}ms", timeoutMs);
            }
            return success;

        } catch (Exception e) {
            netLog.error("Connection failed: {}", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean waitForGameStart(long timeoutMs) {
        try {
            return gameStartedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean waitForGameFinish(long timeoutMs) {
        try {
            return gameFinishedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isGameInProgress() {
        return gameInProgress.get();
    }

    public int getAssignedSlot() {
        return assignedSlot.get();
    }

    public long getDeltaPacketsReceived() {
        return deltaPacketsReceived.get();
    }

    public long getTotalDeltaBytes() {
        return totalDeltaBytes.get();
    }

    public long getEventStateMismatches() {
        return eventStateMismatches.get();
    }

    /** Use this GUI instead of the auto-responding one. Must precede {@link #connect}. */
    public void useGui(final forge.gui.interfaces.IGuiGame gui) {
        this.suppliedGui = gui;
    }

    public forge.game.GameView getGameView() {
        if (suppliedGui != null) {
            return suppliedGui.getGameView();
        }
        return guiGame != null ? guiGame.getGameView() : null;
    }

    public boolean isOpenViewCalled() {
        return guiGame != null && guiGame.isOpenViewCalled();
    }

    public int getSetGameViewCount() {
        return guiGame != null ? guiGame.getSetGameViewCount() : 0;
    }

    public ClientGameLobby getLobby() {
        return lobby;
    }

    public FGameClient getClient() {
        return client;
    }

    public void setReady() {
        if (gameInProgress.get()) {
            // Reconnected into a live match: there is no lobby left to be ready in, and the
            // slot is already held.
            netLog.info("Game already in progress, skipping ready status");
            return;
        }
        if (client != null && connected.get()) {
            netLog.info("Sending ready status");
            UpdateLobbyPlayerEvent event = UpdateLobbyPlayerEvent.isReadyUpdate(true);
            // Apply to local lobby AND send to server
            int slot = assignedSlot.get();
            if (slot >= 0 && lobby != null) {
                lobby.applyToSlot(slot, event);
                netLog.info("Applied ready status to local lobby slot {}", slot);
            }
            client.send(event);
        } else {
            netLog.error("Cannot set ready - not connected");
        }
    }

    @Override
    public void close() {
        netLog.info("Disconnecting");
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                // Ignore close errors
            }
        }
        connected.set(false);
    }

    void onDeltaPacketReceived(DeltaPacket packet) {
        deltaPacketsReceived.incrementAndGet();
        totalDeltaBytes.addAndGet(packet.getApproximateSize());
        // State arriving at all is what starts the game, whether or not any of it came as a
        // full state: a client can be seeded entirely by the first delta.
        gameInProgress.set(true);
        gameStartedLatch.countDown();
        // And receiving it is what being connected means. A reconnecting client is never
        // assigned a lobby slot again — the server still holds the one it had — so waiting
        // for that assignment would wait for something that is not coming.
        connected.set(true);
        connectedLatch.countDown();

        netLog.info("Delta packet #{}: deltas={}, new={}, estimatedBytes={}",
                packet.getSequenceNumber(),
                packet.getObjectDeltas() != null ? packet.getObjectDeltas().size() : 0,
                packet.getNewObjects() != null ? packet.getNewObjects().size() : 0,
                packet.getApproximateSize());
    }

    void onGameEnd() {
        gameInProgress.set(false);
        gameFinishedLatch.countDown();
        netLog.info("Game ended. Deltas={}, FullSyncs={}, TotalBytes={}",
                deltaPacketsReceived.get(),
                totalDeltaBytes.get());
    }

    /**
     * Lobby listener that handles server updates.
     */
    private class ClientLobbyListener implements ILobbyListener {

        @Override
        public void update(GameLobbyData state, int slot) {
            lobby.setData(state);

            // First LobbyUpdateEvent may have slot=-1 before LoginEvent is processed
            if (slot >= 0) {
                int previousSlot = assignedSlot.get();
                assignedSlot.set(slot);
                lobby.setLocalPlayer(slot);

                netLog.info("Lobby update: assigned to slot {} (previous={})",
                        slot, previousSlot);

                // Signal connected once we have a valid slot assignment
                if (previousSlot == -1 && slot >= 0) {
                    connectedLatch.countDown();
                }
            } else {
                netLog.info("Lobby update: slot not yet assigned (slot={})",
                        slot);
            }
        }

        @Override
        public void message(String source, String message, ChatMessage.MessageType type) {
            netLog.info("Chat: {}: {}", source, message);
        }

        @Override
        public void close() {
            netLog.info("Connection closed by server");
            connected.set(false);
            onGameEnd();
        }

        @Override
        public ClientGameLobby getLobby() {
            return lobby;
        }
    }

    /**
     * GUI implementation that logs delta packets and auto-responds to input requests.
     * Extends HeadlessNetworkGuiGame to get proper delta packet processing
     * (deserialization, tracker updates, object creation) while providing
     * auto-response behavior for headless testing.
     *
     * IMPORTANT: All auto-responses are serialized through a single-threaded executor
     * to prevent race conditions where multiple response threads interfere with each other.
     * Each new prompt cancels any pending auto-response from the previous prompt.
     */
    private static class DeltaLoggingGuiGame extends HeadlessNetworkGuiGame {
        private final HeadlessNetworkClient client;
        private IGameController gameController;
        // Track selectable cards for multi-selection prompts (e.g., "discard 2 cards")
        private final java.util.List<forge.game.card.CardView> pendingSelectables = new java.util.ArrayList<>();
        private int selectableIndex = 0;

        /**
         * When set, this player acts on its own turns instead of passing every priority, so
         * that a board develops and the packets that carry one are exercised.
         *
         * <p><b>What decides what.</b> Everything reached through priority is decided by the
         * real AI: which spell to cast and which ability to activate come from
         * {@link AdviceServer}, and mana payment is the server's own {@code ComputerUtilMana},
         * reached by the production Auto button. What is <b>not</b> AI-decided: attacks are
         * "swing with everything able", blocks are never declared, and targeting, discards,
         * trigger ordering and damage assignment all take the first option the stub offers.
         * The split follows the engine rather than taste - one controller method sits behind
         * priority, while combat and each dialog have their own, so each would need its own
         * question asked. Combat is a worthwhile follow-up; the rest matter less.
         *
         * <p>So a game played this way develops a real board and resolves real spells, and its
         * combat is not a model of anything. Read win rates accordingly.
         */
        private static final boolean ACTIVE_PLAY = Boolean.getBoolean("test.activeRemotePlay");
        private final AtomicInteger promptGeneration = new AtomicInteger();
        private final AtomicInteger playsAttempted = new AtomicInteger();
        // Cards already attempted this turn; a refused selectCard gets no reply of any kind,
        // so never retrying within the turn is what prevents an infinite retry loop.
        private final java.util.Set<Integer> attemptedCards = new java.util.HashSet<>();
        private int attemptTurn = -1;
        private volatile int alphaStrikeTurn = -1;

        // Side channel to the host's AI. When the port is set, decisions come from the
        // real AI running against the host's game; only a player id goes out and only a
        // card id plus ability description comes back. Without it, the local heuristic
        // (land drop, then cheapest affordable spell) decides.
        private static final int ADVICE_PORT = Integer.getInteger("test.advicePort", -1);
        private java.net.Socket adviceSocket;
        private java.io.BufferedReader adviceIn;
        private java.io.PrintWriter adviceOut;
        // The ability the AI chose, matched when the server asks getAbilityToPlay
        private volatile String adviceAbilityDesc;

        // Single-threaded executor to serialize all auto-responses and prevent race conditions
        private final ScheduledExecutorService autoResponseExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HeadlessClient-AutoResponse");
            t.setDaemon(true);
            return t;
        });
        // Track the current pending auto-response so we can cancel it when a new prompt arrives
        private volatile ScheduledFuture<?> pendingAutoResponse = null;
        // Lock for coordinating auto-response scheduling
        private final Object autoResponseLock = new Object();

        DeltaLoggingGuiGame(HeadlessNetworkClient client) {
            this.client = client;
        }

        /**
         * Cancel any pending auto-response. Called when a new prompt arrives
         * to prevent stale responses from interfering with the new prompt.
         */
        private void cancelPendingAutoResponse(String reason) {
            synchronized (autoResponseLock) {
                if (pendingAutoResponse != null && !pendingAutoResponse.isDone()) {
                    pendingAutoResponse.cancel(false);
                    netLog.info("Cancelled pending auto-response: {}", reason);
                }
                pendingAutoResponse = null;
            }
        }

        /**
         * Schedule an auto-response action with the given delay.
         * Cancels any previously pending auto-response first.
         */
        private void scheduleAutoResponse(Runnable action, long delayMs, String description) {
            synchronized (autoResponseLock) {
                // Cancel any pending response first
                cancelPendingAutoResponse("scheduling new: " + description);

                pendingAutoResponse = autoResponseExecutor.schedule(() -> {
                    try {
                        // Verify we still have a controller before executing
                        if (gameController != null) {
                            action.run();
                        } else {
                            netLog.info("Skipping auto-response (no controller): {}", description);
                        }
                    } catch (Exception e) {
                        netLog.error("Error in auto-response '{}': {}", description, e.getMessage());
                    }
                }, delayMs, TimeUnit.MILLISECONDS);

                netLog.info("Scheduled auto-response in {}ms: {}", delayMs, description);
            }
        }

        @Override
        public void applyDelta(DeltaPacket packet) {
            // First, process the delta packet (deserialize, update tracker, etc.)
            super.applyDelta(packet);

            // Then notify the client for logging/verification
            client.onDeltaPacketReceived(packet);
        }

        @Override
        public void handleGameEvents(java.util.List<forge.game.event.GameEvent> events) {
            // Validate event-delta consistency AFTER delta is applied but BEFORE
            // super dispatches events to FControlGameEventHandler.
            // Only check the LAST tap event per card in the batch — earlier events
            // may reference intermediate states that the delta (final-state-only)
            // correctly doesn't include.
            java.util.Map<Integer, forge.game.event.GameEventCardTapped> lastTapPerCard = new java.util.LinkedHashMap<>();
            // A card that also changed zone in this batch is exempt: tapping it was a step on
            // the way somewhere, as when a Treasure is tapped for mana and sacrificed to pay a
            // cost, and where it ended up is all the delta carries. Its last tap event is then
            // as intermediate as the earlier ones, so comparing against it fails on correct
            // behaviour.
            final java.util.Set<Integer> movedZone = new java.util.HashSet<>();
            for (forge.game.event.GameEvent event : events) {
                if (event instanceof forge.game.event.GameEventCardTapped tapEvent) {
                    forge.game.card.CardView card = tapEvent.card();
                    if (card != null) {
                        lastTapPerCard.put(card.getId(), tapEvent);
                    }
                } else if (event instanceof forge.game.event.GameEventZone zoneEvent
                        && zoneEvent.card() != null) {
                    movedZone.add(zoneEvent.card().getId());
                }
            }
            lastTapPerCard.keySet().removeAll(movedZone);
            for (forge.game.event.GameEventCardTapped tapEvent : lastTapPerCard.values()) {
                forge.game.card.CardView card = tapEvent.card();
                if (card != null && card.getZone() == forge.game.zone.ZoneType.Battlefield && card.isTapped() != tapEvent.tapped()) {
                    client.eventStateMismatches.incrementAndGet();
                    netLog.warn("[EventDeltaCheck] MISMATCH: GameEventCardTapped says tapped={} but CardView.isTapped()={} for {}",
                            tapEvent.tapped(), card.isTapped(), card);
                }
            }
            super.handleGameEvents(events);
        }

        @Override
        public void setOriginalGameController(forge.game.player.PlayerView view, IGameController controller) {
            super.setOriginalGameController(view, controller);
            if (controller != null) {
                this.gameController = controller;
                netLog.info("Original game controller set for player: {}",
                        view != null ? view.getName() : "null");
            }
        }

        @Override
        public void setGameController(forge.game.player.PlayerView player, IGameController controller) {
            super.setGameController(player, controller);
            if (controller != null) {
                this.gameController = controller;
                netLog.info("Game controller set for player: {}",
                        player != null ? player.getName() : "null");
            }
        }

        @Override
        public void showPromptMessage(forge.game.player.PlayerView playerView, String message, forge.game.card.CardView cv) {
            netLog.info("Prompt: {}", message);

            // Detect player selection prompts (like "who goes first")
            // These contain "Click on the portrait" in the message
            if (message != null && message.contains("Click on the portrait") && gameController != null) {
                netLog.info("Detected player selection prompt, auto-selecting...");
                scheduleAutoResponse(() -> {
                    // Get the game view and select the first player (or self)
                    GameView gv = getGameView();
                    if (gv != null && gv.getPlayers() != null && !gv.getPlayers().isEmpty()) {
                        forge.game.player.PlayerView toSelect = null;
                        // Prefer to select self if possible
                        for (forge.game.player.PlayerView pv : gv.getPlayers()) {
                            if (pv.getName().equals(client.username)) {
                                toSelect = pv;
                                break;
                            }
                        }
                        // Otherwise select first player
                        if (toSelect == null) {
                            toSelect = gv.getPlayers().iterator().next();
                        }
                        netLog.info("Auto-selecting player: {}", toSelect.getName());
                        gameController.selectPlayer(toSelect, null);
                    } else {
                        netLog.error("Cannot auto-select player - no game view or players");
                    }
                }, 100, "player selection");
            }
        }

        @Override
        public void updateButtons(forge.game.player.PlayerView owner, boolean okEnabled, boolean cancelEnabled, boolean focusOk) {
            netLog.info("updateButtons(ok): okEnabled={}, cancelEnabled={}, controller={}",
                    okEnabled, cancelEnabled, gameController != null ? "set" : "null");
            // Auto-respond to button prompts (mulligan, priority, etc.)
            if (gameController != null && okEnabled) {
                netLog.info("Auto-clicking OK for player: {}",
                        owner != null ? owner.getName() : "unknown");
                scheduleAutoResponse(() -> gameController.selectButtonOk(), 50, "click OK button");
            }
        }

        @Override
        public void updateButtons(forge.game.player.PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
            netLog.info("updateButtons(labels): '{}'/{}, '{}'/{}, controller={}",
                    label1, enable1, label2, enable2, gameController != null ? "set" : "null");
            promptGeneration.incrementAndGet();
            if (ACTIVE_PLAY && gameController != null
                    && respondActively(owner, label1, label2, enable1, enable2)) {
                return;
            }
            // Auto-respond to labeled button prompts - click first enabled button
            if (gameController != null && (enable1 || enable2)) {
                String clickTarget = enable1 ? label1 : label2;
                netLog.info("Auto-clicking '{}' for player: {}",
                        clickTarget, owner != null ? owner.getName() : "unknown");
                if (enable1) {
                    scheduleAutoResponse(() -> gameController.selectButtonOk(), 50, "click '" + label1 + "'");
                } else {
                    scheduleAutoResponse(() -> gameController.selectButtonCancel(), 50, "click '" + label2 + "'");
                }
            } else if (gameController != null && !enable1) {
                // OK is disabled but we may have more cards to select (multi-selection prompt)
                synchronized (pendingSelectables) {
                    if (selectableIndex < pendingSelectables.size()) {
                        netLog.info("OK disabled, selecting next card ({}/{} remaining)",
                                pendingSelectables.size() - selectableIndex, pendingSelectables.size());
                        selectNextCard();
                    }
                }
            }
        }

        /**
         * Active-play dispatch on the button labels the server sent. Returns true when this
         * prompt was handled here; false falls through to the click-first-enabled default.
         */
        private boolean respondActively(forge.game.player.PlayerView owner,
                String label1, String label2, boolean enable1, boolean enable2) {
            // Mana payment (InputPayMana): label1 is "Auto", or "" when auto-pay is
            // unsupported. Auto starts disabled and enables shortly after, once the server
            // has computed that the cost is payable — clicking it then has the server's AI
            // tap mana sources. Don't cancel instantly; give Auto time to enable, and only
            // if it never does (cost not actually payable) let the delayed Cancel abort.
            if ("Auto".equals(label1) || label1 == null || label1.isEmpty()) {
                if (!enable1 && enable2) {
                    scheduleAutoResponse(() -> gameController.selectButtonCancel(),
                            2000, "cancel unpayable mana cost");
                    return true;
                }
                return false; // Auto enabled: default behavior clicks it
            }
            final GameView gv = getGameView();
            if (gv == null) {
                return false;
            }
            // Combat (InputAttack): the second button alpha-strikes while no attackers are
            // declared and becomes "Call Back" (undo) once they are. Strike once per turn,
            // then confirm with OK; treating both labels as "attack" declares and undoes
            // forever.
            if ("Alpha Strike".equals(label2) && enable2) {
                final int turn = gv.getTurn();
                if (turn != alphaStrikeTurn) {
                    // Mark the turn when the strike executes, not when scheduled: the input
                    // re-sends this prompt several times back-to-back, and each re-send
                    // cancels whatever is pending. A scheduled-time mark would let the
                    // duplicate knock out the strike and fall through to OK.
                    scheduleAutoResponse(() -> {
                        alphaStrikeTurn = turn;
                        gameController.selectButtonCancel();
                    }, 50, "alpha strike");
                    return true;
                }
                return false; // struck already; OK confirms (or declares nothing)
            }
            // Priority (InputPassPriority): second button is "End Turn", or "Undo (n)" when
            // something can be undone. Only this prompt can initiate a play.
            if (enable1 && ("End Turn".equals(label2)
                    || (label2 != null && label2.startsWith("Undo")))) {
                return tryInitiatePlay(owner, gv);
            }
            return false;
        }

        /**
         * Act in our own main phase with an empty stack, and pass priority everywhere else.
         *
         * <p>That gate is what keeps this affordable. Priority returns in every step and after
         * every action, and asking a question in each one costs a round trip that nearly
         * always ends in nothing: enough of them and the game runs slower than its own timeout.
         *
         * <p>With a host to ask, the choice is the AI's. Without one the fallback picks a land
         * while the drop is unused, else the cheapest spell the untapped lands could cover -
         * which ignores colour, so it offers plenty it cannot pay for.
         */
        private boolean tryInitiatePlay(forge.game.player.PlayerView owner, GameView gv) {
            final forge.game.player.PlayerView turnPlayer = gv.getPlayerTurn();
            final forge.game.phase.PhaseType phase = gv.getPhase();
            if (owner == null || turnPlayer == null || owner.getId() != turnPlayer.getId()) {
                return false;
            }
            if (phase != forge.game.phase.PhaseType.MAIN1 && phase != forge.game.phase.PhaseType.MAIN2) {
                return false;
            }
            if (!gv.getStack().isEmpty()) {
                return false;
            }
            forge.game.player.PlayerView me = null;
            for (forge.game.player.PlayerView pv : gv.getPlayers()) {
                if (pv.getId() == owner.getId()) {
                    me = pv;
                    break;
                }
            }
            if (me == null) {
                return false;
            }
            if (ADVICE_PORT > 0) {
                final forge.game.player.PlayerView self = me;
                final int gen = promptGeneration.get();
                // Off the Netty thread: the ask blocks until the host AI answers
                scheduleAutoResponse(() -> actOnAdvice(self, gen), 50, "ask host AI");
                return true;
            }
            final forge.game.card.CardView pick;
            synchronized (attemptedCards) {
                if (gv.getTurn() != attemptTurn) {
                    attemptTurn = gv.getTurn();
                    attemptedCards.clear();
                }
                int untappedLands = 0;
                for (forge.game.card.CardView c : me.getBattlefield()) {
                    final forge.game.card.CardView.CardStateView st = c.getCurrentState();
                    if (st != null && st.getType().isLand() && !c.isTapped()) {
                        untappedLands++;
                    }
                }
                final boolean canPlayLand = me.getNumLandThisTurn() < me.getMaxLandPlay();
                forge.game.card.CardView landPick = null;
                forge.game.card.CardView spellPick = null;
                int spellCmc = Integer.MAX_VALUE;
                for (forge.game.card.CardView c : me.getHand()) {
                    if (attemptedCards.contains(c.getId())) {
                        continue;
                    }
                    final forge.game.card.CardView.CardStateView st = c.getCurrentState();
                    if (st == null || st.getType() == null) {
                        continue;
                    }
                    if (st.getType().isLand()) {
                        if (canPlayLand && landPick == null) {
                            landPick = c;
                        }
                    } else {
                        final forge.card.mana.ManaCost mc = st.getManaCost();
                        final int cmc = mc == null ? 0 : mc.getCMC();
                        if (cmc <= untappedLands && cmc < spellCmc) {
                            spellPick = c;
                            spellCmc = cmc;
                        }
                    }
                }
                pick = landPick != null ? landPick : spellPick;
                if (pick == null) {
                    return false;
                }
                attemptedCards.add(pick.getId());
            }
            playsAttempted.incrementAndGet();
            final int gen = promptGeneration.get();
            netLog.info("[ActivePlay] attempt #{}: {} (turn {}, {})",
                    playsAttempted.get(), pick, gv.getTurn(), phase);
            scheduleAutoResponse(() -> {
                gameController.selectCard(pick, null, null);
                // A refused selection gets no reply and no fresh prompt, so nothing else
                // would ever wake us. If no prompt superseded this one, try the next
                // candidate (the refused card is in attemptedCards) or pass.
                scheduleAutoResponse(() -> {
                    if (promptGeneration.get() == gen) {
                        netLog.info("[ActivePlay] no response to {}; moving on", pick);
                        final GameView current = getGameView();
                        if (current == null || !tryInitiatePlay(owner, current)) {
                            gameController.selectButtonOk();
                        }
                    }
                }, 2500, "retry after silent refusal");
            }, 50, "play " + pick.getName());
            return true;
        }

        /** Ask the host AI what to play and act on the answer over the real protocol. */
        private void actOnAdvice(forge.game.player.PlayerView me, int gen) {
            final String reply = askAdvice(me.getId());
            if (promptGeneration.get() != gen) {
                // The prompt changed while the ask was in flight; whoever handled the
                // newer prompt owns the input now.
                netLog.info("[ActivePlay] advice arrived for a stale prompt; dropping");
                return;
            }
            if (reply == null || !reply.startsWith("CARD ")) {
                gameController.selectButtonOk();
                return;
            }
            final String payload = reply.substring("CARD ".length());
            final int sep = payload.indexOf('|');
            final int cardId;
            try {
                cardId = Integer.parseInt((sep < 0 ? payload : payload.substring(0, sep)).trim());
            } catch (NumberFormatException e) {
                gameController.selectButtonOk();
                return;
            }
            final GameView gv = getGameView();
            synchronized (attemptedCards) {
                if (gv != null && gv.getTurn() != attemptTurn) {
                    attemptTurn = gv.getTurn();
                    attemptedCards.clear();
                }
                // The AI re-advises a card whose play failed (it is still where it was);
                // once per turn keeps that from looping.
                if (!attemptedCards.add(cardId)) {
                    netLog.info("[ActivePlay] advised card {} already attempted this turn; passing", cardId);
                    gameController.selectButtonOk();
                    return;
                }
            }
            final forge.game.card.CardView card = findOwnCard(me, cardId);
            if (card == null) {
                netLog.info("[ActivePlay] advised card {} not visible here; passing", cardId);
                gameController.selectButtonOk();
                return;
            }
            playsAttempted.incrementAndGet();
            adviceAbilityDesc = sep < 0 ? null : payload.substring(sep + 1);
            netLog.info("[ActivePlay] attempt #{}: AI advises {} ({})",
                    playsAttempted.get(), card, adviceAbilityDesc);
            gameController.selectCard(card, null, null);
            // A refused selection gets no reply and no fresh prompt; don't hang on it
            scheduleAutoResponse(() -> {
                if (promptGeneration.get() == gen) {
                    netLog.info("[ActivePlay] no response to advised {}; passing", card);
                    gameController.selectButtonOk();
                }
            }, 2500, "pass after silent refusal of advised play");
        }

        private synchronized String askAdvice(int playerId) {
            try {
                if (adviceSocket == null || adviceSocket.isClosed()) {
                    adviceSocket = new java.net.Socket(client.hostname, ADVICE_PORT);
                    adviceSocket.setSoTimeout(20000);
                    adviceIn = new java.io.BufferedReader(new java.io.InputStreamReader(
                            adviceSocket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    adviceOut = new java.io.PrintWriter(
                            adviceSocket.getOutputStream(), true, java.nio.charset.StandardCharsets.UTF_8);
                }
                adviceOut.println("ADVICE " + playerId);
                return adviceIn.readLine();
            } catch (java.io.IOException e) {
                netLog.warn("[ActivePlay] advice channel failed: {}", e.getMessage());
                try {
                    if (adviceSocket != null) {
                        adviceSocket.close();
                    }
                } catch (java.io.IOException ignored) {
                    // reconnect next time
                }
                adviceSocket = null;
                return null;
            }
        }

        /** The AI can advise a card anywhere the player could act from, not just hand. */
        private forge.game.card.CardView findOwnCard(forge.game.player.PlayerView me, int cardId) {
            final java.util.List<Iterable<forge.game.card.CardView>> zones = java.util.List.of(
                    me.getHand(), me.getBattlefield(), me.getGraveyard(), me.getCommand(), me.getExile());
            for (final Iterable<forge.game.card.CardView> zone : zones) {
                for (final forge.game.card.CardView c : zone) {
                    if (c.getId() == cardId) {
                        return c;
                    }
                }
            }
            return null;
        }

        @Override
        public forge.game.spellability.SpellAbilityView getAbilityToPlay(forge.game.card.CardView hostCard,
                java.util.List<forge.game.spellability.SpellAbilityView> abilities, forge.util.ITriggerEvent triggerEvent) {
            final String desc = adviceAbilityDesc;
            if (desc != null && abilities != null) {
                for (final forge.game.spellability.SpellAbilityView sav : abilities) {
                    if (desc.equals(sav.getDescription())) {
                        netLog.info("[ActivePlay] matched advised ability: {}", desc);
                        return sav;
                    }
                }
            }
            return super.getAbilityToPlay(hostCard, abilities, triggerEvent);
        }

        /**
         * Forget what was selectable when the server says it no longer is.
         *
         * <p>Anything left in the list outlives the prompt that produced it, and the next
         * prompt to arrive with OK disabled drains it - selecting a card the game moved on
         * from turns ago, which the server then cannot find in any zone.
         */
        @Override
        public void clearSelectables() {
            super.clearSelectables();
            synchronized (pendingSelectables) {
                pendingSelectables.clear();
                selectableIndex = 0;
            }
        }

        @Override
        public void setSelectables(Iterable<forge.game.card.CardView> cards, int min, int max) {
            super.setSelectables(cards, min, max);
            synchronized (pendingSelectables) {
                // Track selectable cards for multi-selection prompts
                pendingSelectables.clear();
                selectableIndex = 0;
                if (cards != null) {
                    for (forge.game.card.CardView card : cards) {
                        pendingSelectables.add(card);
                    }
                }

                // Auto-select the first selectable card when cards become selectable
                if (gameController != null && !pendingSelectables.isEmpty()) {
                    selectNextCard();
                }
            }
        }

        /**
         * Select the next card from the pending list.
         * Uses the serialized auto-response executor to prevent race conditions.
         */
        private void selectNextCard() {
            synchronized (pendingSelectables) {
                if (selectableIndex < pendingSelectables.size()) {
                    forge.game.card.CardView card = pendingSelectables.get(selectableIndex);
                    selectableIndex++;
                    netLog.info("Auto-selecting card {}/{}: {}",
                            selectableIndex, pendingSelectables.size(), card.getName());
                    scheduleAutoResponse(() -> gameController.selectCard(card, null, null),
                            100, "select card " + card.getName());
                }
            }
        }

        @Override
        public void afterGameEnd() {
            super.afterGameEnd();
            autoResponseExecutor.shutdownNow();
            client.onGameEnd();
        }
    }

    public String getMetricsSummary() {
        return String.format("HeadlessClient[%s]: deltas=%d, bytes=%d, eventMismatches=%d, connected=%s, gameInProgress=%s",
                username,
                deltaPacketsReceived.get(),
                totalDeltaBytes.get(),
                eventStateMismatches.get(),
                connected.get(),
                gameInProgress.get());
    }
}
