package forge.net;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.Timer;

import forge.LobbyPlayer;
import forge.ai.GameState;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.AbstractGuiGame;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerZoneUpdate;
import forge.player.PlayerZoneUpdates;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

/**
 * Headless implementation of AbstractGuiGame for automated network testing.
 * Provides minimal implementations for all abstract/unimplemented methods
 * and tracks client-side state for test assertions.
 */
public class HeadlessNetworkGuiGame extends AbstractGuiGame {

    // Client-side tracking fields for test assertions
    private final AtomicInteger setGameViewCount = new AtomicInteger(0);
    private final AtomicInteger errorDialogCount = new AtomicInteger(0);
    private final List<String> errorMessages = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean openViewCalled = false;

    // Auto-response fields for interactive prompts (race window before convertToAI)
    private volatile IGameController gameController;
    private final ScheduledExecutorService autoResponseExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "HeadlessAutoResponse");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> pendingAutoResponse;
    private List<CardView> pendingSelectables;
    private int selectableIndex;
    private final Object autoResponseLock = new Object();

    // DEBUG: EDT heartbeat timer
    private Timer edtHeartbeat;
    {
        edtHeartbeat = new Timer(2000, e ->
                System.out.println("[EDT-Heartbeat] alive at " + System.currentTimeMillis()
                        + " on " + Thread.currentThread().getName()));
        edtHeartbeat.setRepeats(true);
        edtHeartbeat.start();
    }

    public int getSetGameViewCount() {
        return setGameViewCount.get();
    }

    public boolean isOpenViewCalled() {
        return openViewCalled;
    }

    public int getErrorDialogCount() {
        return errorDialogCount.get();
    }

    public List<String> getErrorMessages() {
        return new ArrayList<>(errorMessages);
    }

    @Override
    public void setGameView(final GameView gameView) {
        System.out.println("[HeadlessAutoResponse] setGameView entering on " + Thread.currentThread().getName());
        super.setGameView(gameView);
        System.out.println("[HeadlessAutoResponse] setGameView exiting on " + Thread.currentThread().getName());
        setGameViewCount.incrementAndGet();
    }

    // ========================================
    // Auto-response scheduling
    // ========================================

    private void scheduleAutoResponse(Runnable action, long delayMs, String description) {
        synchronized (autoResponseLock) {
            if (pendingAutoResponse != null && !pendingAutoResponse.isDone()) {
                pendingAutoResponse.cancel(false);
                System.out.println("[HeadlessAutoResponse] Cancelled pending response (superseded by " + description + ")");
            }
            System.out.println("[HeadlessAutoResponse] Scheduling: " + description + " (delay=" + delayMs + "ms)");
            pendingAutoResponse = autoResponseExecutor.schedule(() -> {
                try {
                    IGameController ctrl = gameController;
                    if (ctrl != null) {
                        System.out.println("[HeadlessAutoResponse] Firing: " + description);
                        action.run();
                    } else {
                        System.out.println("[HeadlessAutoResponse] Skipped (no controller): " + description);
                    }
                } catch (Exception e) {
                    System.err.println("[HeadlessAutoResponse] Error in " + description + ": " + e.getMessage());
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelPendingAutoResponse() {
        synchronized (autoResponseLock) {
            if (pendingAutoResponse != null && !pendingAutoResponse.isDone()) {
                pendingAutoResponse.cancel(false);
                pendingAutoResponse = null;
                System.out.println("[HeadlessAutoResponse] Cancelled pending response (explicit cancel)");
            }
        }
    }

    private void selectNextCard() {
        IGameController ctrl = gameController;
        if (ctrl == null || pendingSelectables == null || selectableIndex >= pendingSelectables.size()) {
            return;
        }
        CardView card = pendingSelectables.get(selectableIndex);
        selectableIndex++;
        ctrl.selectCard(card, null, null);
        // If more cards remain and we're still selecting, schedule the next one
        if (selectableIndex < pendingSelectables.size() && isSelecting()) {
            scheduleAutoResponse(this::selectNextCard, 50, "selectNextCard");
        }
    }

    /**
     * Shut down the auto-response executor. Called from afterGameEnd and
     * can also be called explicitly during test cleanup.
     */
    public void shutdown() {
        System.out.println("[HeadlessAutoResponse] Shutting down auto-response executor");
        if (edtHeartbeat != null) { edtHeartbeat.stop(); }
        cancelPendingAutoResponse();
        autoResponseExecutor.shutdownNow();
    }

    // ========================================
    // Controller capture
    // ========================================

    @Override
    public void setOriginalGameController(PlayerView player, IGameController controller) {
        super.setOriginalGameController(player, controller);
        gameController = controller;
        System.out.println("[HeadlessAutoResponse] Controller captured (original) for " +
                (player != null ? player.getName() : "null") + ": " + controller.getClass().getSimpleName());
    }

    @Override
    public void setGameController(PlayerView player, IGameController controller) {
        super.setGameController(player, controller);
        if (controller != null) {
            gameController = controller;
            System.out.println("[HeadlessAutoResponse] Controller captured (updated) for " +
                    (player != null ? player.getName() : "null") + ": " + controller.getClass().getSimpleName());
        }
    }

    // ========================================
    // Abstract method from AbstractGuiGame
    // ========================================

    @Override
    protected void updateCurrentPlayer(PlayerView player) {
    }

    // ========================================
    // UI Lifecycle methods
    // ========================================

    @Override
    public void openView(TrackableCollection<PlayerView> myPlayers) {
        openViewCalled = true;
    }

    @Override
    public void showCombat() {
    }

    @Override
    public void finishGame() {
    }

    // ========================================
    // Prompts and Messages
    // ========================================

    @Override
    public void showPromptMessage(PlayerView playerView, String message) {
        if (message != null && message.contains("Click on the portrait")) {
            IGameController ctrl = gameController;
            if (ctrl != null && playerView != null) {
                scheduleAutoResponse(() -> ctrl.selectPlayer(playerView, null),
                        100, "selectPlayer-portrait");
            }
        }
    }

    @Override
    public void showCardPromptMessage(PlayerView playerView, String message, CardView card) {
    }

    @Override
    public void updateButtons(PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
        System.out.println("[HeadlessAutoResponse] updateButtons called on " + Thread.currentThread().getName()
                + " enable1=" + enable1 + " enable2=" + enable2);
        IGameController ctrl = gameController;
        if (ctrl == null) {
            return;
        }
        if (enable1) {
            scheduleAutoResponse(ctrl::selectButtonOk, 50, "selectButtonOk");
        } else if (enable2) {
            scheduleAutoResponse(ctrl::selectButtonCancel, 50, "selectButtonCancel");
        } else if (pendingSelectables != null && !pendingSelectables.isEmpty()) {
            // Neither button enabled — may be waiting for card selection
            scheduleAutoResponse(this::selectNextCard, 50, "selectNextCard-fromButtons");
        }
    }

    @Override
    public void flashIncorrectAction() {
    }

    @Override
    public void alertUser() {
    }

    // ========================================
    // UI Overlays
    // ========================================

    @Override
    public void enableOverlay() {
    }

    @Override
    public void disableOverlay() {
    }

    @Override
    public void showManaPool(PlayerView player) {
    }

    @Override
    public void hideManaPool(PlayerView player) {
    }

    // ========================================
    // Zone/Card Updates
    // ========================================

    @Override
    public Iterable<PlayerZoneUpdate> tempShowZones(PlayerView controller, Iterable<PlayerZoneUpdate> zonesToUpdate) {
        System.out.println("[HeadlessAutoResponse] tempShowZones called on " + Thread.currentThread().getName());
        return zonesToUpdate;
    }

    @Override
    public void hideZones(PlayerView controller, Iterable<PlayerZoneUpdate> zonesToUpdate) {
    }

    @Override
    public void updateShards(Iterable<PlayerView> shardsUpdate) {
    }

    // ========================================
    // Game State
    // ========================================

    @Override
    public GameState getGamestate() {
        return null;
    }

    // ========================================
    // Card Selection/Display
    // ========================================

    @Override
    public void setSelectables(final Iterable<CardView> cards) {
        System.out.println("[HeadlessAutoResponse] setSelectables called on " + Thread.currentThread().getName());
        super.setSelectables(cards);
        List<CardView> cardList = new ArrayList<>();
        for (CardView cv : cards) {
            cardList.add(cv);
        }
        synchronized (autoResponseLock) {
            pendingSelectables = cardList;
            selectableIndex = 0;
        }
        if (!cardList.isEmpty()) {
            IGameController ctrl = gameController;
            if (ctrl != null) {
                scheduleAutoResponse(this::selectNextCard, 100, "selectCard-initial");
            }
        }
    }

    @Override
    public void clearSelectables() {
        super.clearSelectables();
        synchronized (autoResponseLock) {
            pendingSelectables = null;
            selectableIndex = 0;
        }
    }

    @Override
    public void setPanelSelection(CardView hostCard) {
    }

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities, ITriggerEvent triggerEvent) {
        return abilities != null && !abilities.isEmpty() ? abilities.get(0) : null;
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers, int damage, GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        Map<CardView, Integer> result = new HashMap<>();
        if (blockers != null && !blockers.isEmpty()) {
            result.put(blockers.get(0), damage);
        }
        return result;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target, int amount, boolean atLeastOne, String amountLabel) {
        return target;
    }

    // ========================================
    // Dialogs
    // ========================================

    @Override
    public void message(String message, String title) {
    }

    @Override
    public void showErrorDialog(String message, String title) {
        errorDialogCount.incrementAndGet();
        errorMessages.add(title + ": " + message);
        System.err.println("[HeadlessNetworkGuiGame] " + title + ": " + message);
    }

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText, String noButtonText, boolean defaultYes) {
        return defaultYes;
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        return defaultOption;
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        return initialInput != null ? initialInput : (inputOptions != null && !inputOptions.isEmpty() ? inputOptions.get(0) : "");
    }

    @Override
    public boolean confirm(CardView c, String question, boolean defaultIsYes, List<String> options) {
        return defaultIsYes;
    }

    // ========================================
    // Choices
    // ========================================

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices, List<T> selected, FSerializableFunction<T, String> display) {
        if (choices == null || choices.isEmpty()) {
            return Collections.emptyList();
        }
        int actualMin = Math.max(0, min);
        int count = Math.min(actualMin, choices.size());
        if (count == 0 && min <= 0) {
            return Collections.emptyList();
        }
        return choices.subList(0, Math.max(count, 1));
    }

    @Override
    public <T> List<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices, CardView referenceCard, boolean sideboardingMode) {
        return sourceChoices != null ? sourceChoices : Collections.emptyList();
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        return Collections.emptyList();
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList, DelayedReveal delayedReveal, boolean isOptional) {
        if (optionList == null || optionList.isEmpty()) {
            return null;
        }
        return isOptional ? null : optionList.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList, int min, int max, DelayedReveal delayedReveal) {
        if (optionList == null || optionList.isEmpty()) {
            return Collections.emptyList();
        }
        int count = Math.min(min, optionList.size());
        return new ArrayList<>(optionList.subList(0, count));
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable, boolean toTop, boolean toBottom, boolean toAnywhere) {
        List<CardView> result = new ArrayList<>();
        if (cards != null) {
            for (CardView card : cards) {
                result.add(card);
            }
        }
        return result;
    }

    // ========================================
    // Player/Card Settings
    // ========================================

    @Override
    public void setCard(CardView card) {
    }

    @Override
    public void setPlayerAvatar(LobbyPlayer player, IHasIcon ihi) {
    }

    @Override
    public PlayerZoneUpdates openZones(PlayerView controller, Collection<ZoneType> zones,
            Map<PlayerView, Object> players, boolean backupLastZones) {
        return null;
    }

    @Override
    public void restoreOldZones(PlayerView playerView, PlayerZoneUpdates playerZoneUpdates) {
    }

    @Override
    public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) {
        return false;
    }

    // ========================================
    // Lifecycle
    // ========================================

    @Override
    public void afterGameEnd() {
        super.afterGameEnd();
        shutdown();
    }
}
