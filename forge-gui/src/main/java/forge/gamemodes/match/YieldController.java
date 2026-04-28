/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.gamemodes.match;

import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.gui.interfaces.IGuiGame;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.trackable.TrackableTypes;
import forge.util.Localizer;

import java.util.Map;
import java.util.Set;

/**
 * Manages yield state and logic for the experimental yield system.
 * Handles automatic priority passing, interrupt conditions, and smart suggestions.
 *
 * Per-player state is a phase-targeted YieldMarker plus a boolean stack-yield flag;
 * either, both, or neither may be active.
 */
public class YieldController {

    private final IGuiGame gui;

    // Legacy turn-boundary auto-pass set; written by the End-Turn cancel button (any pref state).
    private final Set<PlayerView> autoPassUntilEndOfTurn = Sets.newConcurrentHashSet();

    // Immutable so map readers see a consistent snapshot via Map#compute.
    private static final class YieldState {
        final YieldMarker marker;          // null = no marker active
        final boolean stackYield;          // true = yield until stack empties
        final boolean hasLeftMarker;       // true once priority has been observed somewhere other than the marker location since activation
        final boolean activationOnMarker;  // true if priority was at marker location at the moment of activation

        private YieldState(YieldMarker marker, boolean stackYield, boolean hasLeftMarker, boolean activationOnMarker) {
            this.marker = marker;
            this.stackYield = stackYield;
            this.hasLeftMarker = hasLeftMarker;
            this.activationOnMarker = activationOnMarker;
        }

        static YieldState empty() {
            return new YieldState(null, false, false, false);
        }

        YieldState withMarker(YieldMarker m, boolean hasLeft, boolean activationOnMarker) {
            return new YieldState(m, this.stackYield, hasLeft, activationOnMarker);
        }

        YieldState withStackYield(boolean active) {
            return new YieldState(this.marker, active, this.hasLeftMarker, this.activationOnMarker);
        }

        YieldState withHasLeftMarker(boolean hasLeft) {
            return new YieldState(this.marker, this.stackYield, hasLeft, this.activationOnMarker);
        }

        boolean isEmpty() {
            return marker == null && !stackYield;
        }
    }

    private final Map<PlayerView, YieldState> yieldStates = Maps.newConcurrentMap();

    // Set on event arrival, consumed once per priority pass in mayAutoPass.
    // Lets a single interrupt event fire exactly one break, instead of polling
    // a state-holds condition every priority pass.
    private final Set<PlayerView> breakNextPriorityPass = Sets.newConcurrentHashSet();

    public YieldController(IGuiGame gui) {
        this.gui = gui;
    }

    public void autoPassUntilEndOfTurn(PlayerView player) {
        player = TrackableTypes.PlayerViewType.lookup(player);
        autoPassUntilEndOfTurn.add(player);
    }

    public void autoPassCancel(PlayerView player) {
        player = TrackableTypes.PlayerViewType.lookup(player);
        if (!autoPassUntilEndOfTurn.remove(player)) {
            return;
        }

        // Prevent prompt getting stuck on yielding message while actually waiting for next input opportunity
        gui.showPromptMessage(player, "");
        gui.updateButtons(player, false, false, false);
        gui.awaitNextInput();
    }

    public boolean mayAutoPass(PlayerView player) {
        player = TrackableTypes.PlayerViewType.lookup(player);

        // Single-shot break flag from interrupt events: consumed once per priority pass.
        if (breakNextPriorityPass.remove(player)) {
            // Active yield always breaks on a fresh interrupt event.
            YieldState state = yieldStates.get(player);
            if (state != null && !state.isEmpty()) {
                boolean hadMarker = state.marker != null;
                yieldStates.remove(player);
                gui.refreshYieldUi(player);
                if (hadMarker) {
                    gui.syncYieldMarkerCleared(player);
                }
                promptCleared(player);
            }
            // APINA breaks only when the user opted in.
            if (getInterruptPref(ForgePreferences.FPref.YIELD_AUTO_PASS_RESPECTS_INTERRUPTS)) {
                return false;
            }
        }

        // Yield states self-clear when their stop condition fires.
        // Must run before isAutoPassingNoActions or that short-circuits and the state never clears.
        if (shouldAutoYieldForPlayer(player)) {
            return true;
        }
        return isAutoPassingNoActions(player);
    }

    /** Persistent preference toggle (YIELD_AUTO_PASS_NO_ACTIONS), not a one-shot yield mode. */
    public boolean isAutoPassingNoActions(PlayerView player) {
        if (!isYieldExperimentalEnabled()) {
            return false;
        }
        boolean prefValue = getInterruptPref(ForgePreferences.FPref.YIELD_AUTO_PASS_NO_ACTIONS);
        if (!prefValue) {
            return false;
        }
        // Interrupt-driven breaks are handled by the event-driven break flag in mayAutoPass —
        // this method only governs the steady-state "no actions available" condition.
        // Respect phase-skip settings: pass through unmarked phases even if
        // the player has actions. Auto-pass should be additive to phase-skip,
        // not cause stops at phases the user explicitly set to skip.
        GameView gv = gui.getGameView();
        if (gv != null && gv.getStack() != null && gv.getStack().isEmpty()) {
            PlayerView turnPlayer = gv.getPlayerTurn();
            forge.game.phase.PhaseType phase = gv.getPhase();
            if (turnPlayer != null && phase != null
                    && gui.isUiSetToSkipPhase(turnPlayer, phase)) {
                return true;
            }
        }
        return !player.hasAvailableActions();
    }

    private boolean getInterruptPref(ForgePreferences.FPref pref) {
        forge.interfaces.IGameController controller = gui.getGameController();
        if (controller == null) {
            return "true".equals(pref.getDefault());
        }
        return controller.getYieldInterruptPref(pref);
    }

    public void updateAutoPassPrompt(PlayerView player) {
        player = TrackableTypes.PlayerViewType.lookup(player);

        if (autoPassUntilEndOfTurn.contains(player)) {
            gui.cancelAwaitNextInput();
            gui.showPromptMessage(player, Localizer.getInstance().getMessage("lblYieldingUntilEndOfTurn"));
            gui.updateButtons(player, false, true, false);
            return;
        }

        YieldState state = yieldStates.get(player);
        if (state != null && !state.isEmpty()) {
            gui.cancelAwaitNextInput();
            Localizer loc = Localizer.getInstance();
            final String message;
            if (state.stackYield) {
                message = loc.getMessage("lblYieldingUntilStackClears");
            } else if (state.marker != null) {
                message = loc.getMessage("lblYieldingUntilPhaseFmt", state.marker.getPhase().nameForUi);
            } else {
                message = loc.getMessage("lblYieldingUntilEndOfTurn");
            }
            gui.showPromptMessage(player, message);
            gui.updateButtons(player, false, true, false);
            return;
        }

        // Persistent auto-pass: clear stale prompt from previous input (e.g. Pay Mana Cost).
        if (isAutoPassingNoActions(player)) {
            gui.cancelAwaitNextInput();
            gui.showPromptMessage(player, Localizer.getInstance().getMessage("lblAutoPassingNoActions"));
            gui.updateButtons(player, false, false, false);
        }
    }

    public void setYieldMarker(PlayerView player, YieldMarker marker) {
        setYieldMarkerInternal(player, marker, true);
    }

    public void setYieldMarkerSilent(PlayerView player, YieldMarker marker) {
        setYieldMarkerInternal(player, marker, false);
    }

    public void clearYieldMarker(PlayerView player) {
        setYieldMarkerInternal(player, null, true);
    }

    public void clearYieldMarkerSilent(PlayerView player) {
        setYieldMarkerInternal(player, null, false);
    }

    private void setYieldMarkerInternal(PlayerView player, YieldMarker marker, boolean notifyGui) {
        final PlayerView key = TrackableTypes.PlayerViewType.lookup(player);
        // Setting a marker takes priority over the legacy auto-pass set.
        autoPassUntilEndOfTurn.remove(key);
        // If priority is already at, or past, the marker location in its owner's current turn,
        // require a full cycle before firing — otherwise the next priority pass would observe
        // pastTarget and fire immediately in the activation phase, which is never what the user wants.
        final boolean atOrPastMarkerNow = marker != null && isPriorityAtOrPastMarker(marker);
        yieldStates.compute(key, (p, prev) -> {
            YieldState base = (prev == null) ? YieldState.empty() : prev;
            YieldState next = base.withMarker(
                    marker,
                    marker == null ? false : !atOrPastMarkerNow,
                    marker != null && atOrPastMarkerNow);
            return next.isEmpty() ? null : next;
        });
        if (notifyGui) {
            gui.refreshYieldUi(key);
        }
    }

    private boolean isPriorityAtOrPastMarker(YieldMarker marker) {
        GameView gv = gui.getGameView();
        if (gv == null) {
            return false;
        }
        PlayerView turnPlayer = gv.getPlayerTurn();
        forge.game.phase.PhaseType phase = gv.getPhase();
        if (turnPlayer == null || phase == null || !turnPlayer.equals(marker.getPhaseOwner())) {
            return false;
        }
        return phase == marker.getPhase() || phase.isAfter(marker.getPhase());
    }

    public void setStackYield(PlayerView player, boolean active) {
        setStackYieldInternal(player, active, true);
    }

    public void setStackYieldSilent(PlayerView player, boolean active) {
        setStackYieldInternal(player, active, false);
    }

    private void setStackYieldInternal(PlayerView player, boolean active, boolean notifyGui) {
        final PlayerView key = TrackableTypes.PlayerViewType.lookup(player);
        if (active) {
            autoPassUntilEndOfTurn.remove(key);
        }
        yieldStates.compute(key, (p, prev) -> {
            YieldState base = (prev == null) ? YieldState.empty() : prev;
            YieldState next = base.withStackYield(active);
            return next.isEmpty() ? null : next;
        });
        if (notifyGui) {
            gui.refreshYieldUi(key);
        }
    }

    public YieldMarker getYieldMarker(PlayerView player) {
        YieldState s = yieldStates.get(TrackableTypes.PlayerViewType.lookup(player));
        return s == null ? null : s.marker;
    }

    public boolean isStackYieldActive(PlayerView player) {
        YieldState s = yieldStates.get(TrackableTypes.PlayerViewType.lookup(player));
        return s != null && s.stackYield;
    }

    public boolean shouldAutoYieldForPlayer(PlayerView player) {
        final PlayerView key = TrackableTypes.PlayerViewType.lookup(player);
        if (autoPassUntilEndOfTurn.contains(key)) {
            return true;
        }

        if (!isYieldExperimentalEnabled()) {
            return false;
        }

        YieldState state = yieldStates.get(key);
        if (state == null || state.isEmpty()) {
            return false;
        }

        // Interrupt-driven cancellation is handled by the event-driven break flag in mayAutoPass.

        GameView gameView = gui.getGameView();
        if (gameView == null) {
            return false;
        }

        boolean stillYielding = false;

        if (state.stackYield) {
            boolean stackEmpty = gameView.getStack() == null || gameView.getStack().isEmpty();
            if (stackEmpty) {
                yieldStates.compute(key, (p, prev) -> {
                    if (prev == null) return null;
                    YieldState next = prev.withStackYield(false);
                    return next.isEmpty() ? null : next;
                });
                gui.refreshYieldUi(key);
                state = yieldStates.get(key);
            } else {
                stillYielding = true;
            }
        }

        // Marker fires the first time priority reaches (phaseOwner, phase) AFTER activation,
        // OR — if a phase past the marker is observed in the same turn (game-rule skip,
        // e.g. DECLARE_BLOCKERS without attackers) — fires there too.
        // If activated while already at the marker location, must first leave AND return,
        // not just leave: a same-phase right-click means "next cycle", not "next phase".
        YieldMarker marker = state == null ? null : state.marker;
        if (marker != null) {
            PlayerView turnPlayer = gameView.getPlayerTurn();
            forge.game.phase.PhaseType currentPhase = gameView.getPhase();

            boolean inMarkerOwnerTurn = turnPlayer != null
                    && turnPlayer.equals(marker.getPhaseOwner());
            boolean atTarget = inMarkerOwnerTurn
                    && currentPhase == marker.getPhase();
            boolean pastTarget = inMarkerOwnerTurn
                    && currentPhase != null
                    && marker.getPhase() != null
                    && currentPhase.isAfter(marker.getPhase());

            // Activation-on-marker case waits for full cycle (atTarget only).
            // Activation-off-marker case fires on at-or-past target (handles game-rule skips).
            boolean shouldFire = state.hasLeftMarker
                    && (atTarget || (!state.activationOnMarker && pastTarget));

            if (shouldFire) {
                yieldStates.compute(key, (p, prev) -> {
                    if (prev == null) return null;
                    YieldState next = prev.withMarker(null, false, false);
                    return next.isEmpty() ? null : next;
                });
                gui.refreshYieldUi(key);
                gui.syncYieldMarkerCleared(key);
                if (!stillYielding) {
                    promptCleared(key);
                }
            } else {
                if (!atTarget && !state.hasLeftMarker) {
                    // First observation away from the marker location — record it.
                    yieldStates.compute(key, (p, prev) -> {
                        if (prev == null) return null;
                        YieldState next = prev.withHasLeftMarker(true);
                        return next.isEmpty() ? null : next;
                    });
                }
                stillYielding = true;
            }
        }

        return stillYielding;
    }

    private void promptCleared(PlayerView player) {
        gui.showPromptMessage(player, "");
        gui.updateButtons(player, false, false, false);
        gui.awaitNextInput();
    }

    /** Forwarded from AbstractGuiGame.handleGameEvent. */
    public void onGameEvent(GameEvent event) {
        if (!isYieldExperimentalEnabled()) {
            return;
        }
        if (event instanceof GameEventAttackersDeclared ev) {
            onAttackersDeclared(ev);
        } else if (event instanceof GameEventSpellAbilityCast ev) {
            onSpellAbilityCast(ev);
        }
    }

    private void onAttackersDeclared(GameEventAttackersDeclared event) {
        if (!getInterruptPref(ForgePreferences.FPref.YIELD_INTERRUPT_ON_ATTACKERS)) {
            return;
        }
        GameView gameView = gui.getGameView();
        if (gameView == null) {
            return;
        }
        Multimap<GameEntityView, CardView> attackers = event.attackersMap();
        if (attackers == null || attackers.isEmpty()) {
            return;
        }
        for (PlayerView p : gameView.getPlayers()) {
            if (isAttackerAgainstPlayer(attackers, p)) {
                breakNextPriorityPass.add(TrackableTypes.PlayerViewType.lookup(p));
            }
        }
    }

    private static boolean isAttackerAgainstPlayer(Multimap<GameEntityView, CardView> attackers, PlayerView player) {
        for (GameEntityView defender : attackers.keySet()) {
            if (player.equals(defender)) {
                return true;
            }
            if (defender instanceof CardView cv && player.equals(cv.getController())) {
                return true;
            }
        }
        return false;
    }

    private void onSpellAbilityCast(GameEventSpellAbilityCast event) {
        StackItemView si = event.si();
        if (si == null) {
            return;
        }
        GameView gameView = gui.getGameView();
        if (gameView == null) {
            return;
        }
        boolean prefTargeting    = getInterruptPref(ForgePreferences.FPref.YIELD_INTERRUPT_ON_TARGETING);
        boolean prefOpponent     = getInterruptPref(ForgePreferences.FPref.YIELD_INTERRUPT_ON_OPPONENT_SPELL);
        boolean prefMassRemoval  = getInterruptPref(ForgePreferences.FPref.YIELD_INTERRUPT_ON_MASS_REMOVAL);
        boolean prefTriggers     = getInterruptPref(ForgePreferences.FPref.YIELD_INTERRUPT_ON_TRIGGERS);
        if (!prefTargeting && !prefOpponent && !prefMassRemoval && !prefTriggers) {
            return;
        }
        // Mass-removal classification is computed once on the host at event emission and shipped on the event,
        // so this works on remote clients (where gameView.getGame() is null and a stack lookup wouldn't resolve).
        boolean isMassRemoval = prefMassRemoval && event.isMassRemoval();
        boolean isTrigger = si.isTrigger();
        PlayerView activator = si.getActivatingPlayer();

        for (PlayerView p : gameView.getPlayers()) {
            if (prefTriggers && isTrigger) {
                breakNextPriorityPass.add(TrackableTypes.PlayerViewType.lookup(p));
                continue;
            }
            boolean isOpponent = activator != null && !activator.equals(p);
            if (prefTargeting && targetsPlayerOrPermanents(si, p)) {
                breakNextPriorityPass.add(TrackableTypes.PlayerViewType.lookup(p));
                continue;
            }
            if (isOpponent && prefOpponent && targetsPlayerOrPermanents(si, p)) {
                breakNextPriorityPass.add(TrackableTypes.PlayerViewType.lookup(p));
                continue;
            }
            if (isOpponent && isMassRemoval) {
                breakNextPriorityPass.add(TrackableTypes.PlayerViewType.lookup(p));
            }
        }
    }

    /** Recurses into sub-instances (e.g. Oona, where targeting is in a sub-ability). */
    private boolean targetsPlayerOrPermanents(forge.game.spellability.StackItemView si, PlayerView player) {
        forge.util.collect.FCollectionView<PlayerView> targetPlayers = si.getTargetPlayers();
        if (targetPlayers != null) {
            for (PlayerView target : targetPlayers) {
                if (target.equals(player)) return true;
            }
        }

        forge.util.collect.FCollectionView<CardView> targetCards = si.getTargetCards();
        if (targetCards != null) {
            for (CardView target : targetCards) {
                if (target.getController() != null && target.getController().equals(player)) {
                    return true;
                }
            }
        }

        // Recursively check sub-instances for targeting (handles abilities like Oona)
        forge.game.spellability.StackItemView subInstance = si.getSubInstance();
        if (subInstance != null && targetsPlayerOrPermanents(subInstance, player)) {
            return true;
        }

        return false;
    }

    private boolean isYieldExperimentalEnabled() {
        return FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.YIELD_EXPERIMENTAL_OPTIONS);
    }

    public void removeFromLegacyAutoPass(PlayerView player) {
        autoPassUntilEndOfTurn.remove(player);
    }

    /** Clears all yield state. Called between games so state doesn't carry over. */
    public void reset() {
        autoPassUntilEndOfTurn.clear();
        yieldStates.clear();
        breakNextPriorityPass.clear();
    }

}
