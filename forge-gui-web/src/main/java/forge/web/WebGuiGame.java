package forge.web;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.ImageKeys;
import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameLog;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventGameOutcome;
import forge.game.GameState;
import forge.game.GameView;
import forge.game.card.CardFaceView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.event.GameEvent;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.item.PaperCard;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.match.YieldController;
import forge.gamemodes.match.YieldMarker;
import forge.gamemodes.match.YieldUpdate;
import forge.gamemodes.net.DeltaPacket;
import forge.gamemodes.net.NetworkGuiGame;
import forge.gamemodes.net.server.DeltaSyncManager;
import forge.gui.card.CardDetailUtil;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.skin.FSkinProp;
import forge.sound.EventVisualizer;
import forge.sound.SoundEffectType;
import forge.model.FModel;
import forge.player.AutoYieldStore.TriggerDecision;
import forge.player.PlayerZoneUpdate;
import forge.player.PlayerZoneUpdates;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableProperty;
import forge.trackable.TrackableTypes;
import forge.trackable.TrackableTypes.TrackableType;
import forge.trackable.Tracker;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;
import forge.util.Localizer;
import forge.web.FromBrowser.KeyCommand;
import forge.web.FromBrowser.NextGame;
import forge.web.FromBrowser.PhaseCommand;
import forge.web.FromBrowser.Reply;
import forge.web.FromBrowser.SelectCard;
import forge.web.FromBrowser.SetSetting;
import forge.web.FromBrowser.StackYield;
import forge.web.FromBrowser.UseMana;
import forge.web.FromBrowser.YieldAction;
import forge.web.ToBrowser.CardFace;
import forge.web.ToBrowser.ChoiceKind;
import forge.web.ToBrowser.ChoicesRequest;
import forge.web.ToBrowser.Controls;
import forge.web.ToBrowser.Detail;
import forge.web.ToBrowser.DistributeRequest;
import forge.web.ToBrowser.Flash;
import forge.web.ToBrowser.GameOver;
import forge.web.ToBrowser.ManipulateRequest;
import forge.web.ToBrowser.Notice;
import forge.web.ToBrowser.OptionRequest;
import forge.web.ToBrowser.OrderAnswer;
import forge.web.ToBrowser.OrderRequest;
import forge.web.ToBrowser.Playable;
import forge.web.ToBrowser.PlayerDetail;
import forge.web.ToBrowser.Prompt;
import forge.web.ToBrowser.PromptButton;
import forge.web.ToBrowser.Ref;
import forge.web.ToBrowser.RequestOption;
import forge.web.ToBrowser.ShownZone;
import forge.web.ToBrowser.SideboardEntry;
import forge.web.ToBrowser.SideboardRequest;
import forge.web.ToBrowser.Sound;
import forge.web.ToBrowser.StackMenu;
import forge.web.ToBrowser.TextRequest;
import forge.web.ToBrowser.TurnMarker;
import forge.web.ToBrowser.Zones;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/** Client-side GUI of a netplay game whose view is a browser. Every call from the host runs on one serial dispatch thread. */
public class WebGuiGame extends NetworkGuiGame {
    private final ReentrantLock mirrorLock = new ReentrantLock();
    private final ExecutorService dispatch = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "WebClient");
        t.setDaemon(true);
        return t;
    });
    private final BrowserModel model = new BrowserModel();
    private final PendingRequests requests = new PendingRequests(this::send);
    private final DeltaSyncManager snapshotter = new DeltaSyncManager();
    private final AtomicInteger skippedProperties = new AtomicInteger();
    // The prompt is written from the dispatch thread and from AbstractGuiGame's final timer methods on the host UI thread
    private final Object promptLock = new Object();
    private String promptMessage = "";
    private boolean priority;
    private Ref promptCard;
    private PromptButton ok = new PromptButton("", false);
    private PromptButton cancel = new PromptButton("", false);
    private boolean focusOk;
    private boolean paying;
    private List<Ref> selectable = List.of();
    private int selectableMin;
    private List<Ref> selectablePlayers = List.of();
    private final Set<Integer> highlighted = new LinkedHashSet<>();
    private final Map<String, ShownZone> shownZones = new LinkedHashMap<>();
    // Written on the dispatch thread, replayed to a reloading browser from the socket thread
    private final WebGameLog gameLog = new WebGameLog(this::mayView);
    private volatile BrowserChannel browser;
    private volatile boolean gameOver;

    /** Frees the thread that sends to the browser; the match it belongs to is over. */
    public void close() {
        dispatch.shutdown();
    }

    public Executor dispatchExecutor() {
        return task -> dispatch.execute(() -> {
            mirrorLock.lock();
            try {
                task.run();
            } catch (final RuntimeException e) {
                Logger.error(e, "Web client call failed");
            } finally {
                mirrorLock.unlock();
            }
        });
    }

    /** Sends on the caller's thread. A browser reconnects while a request is open, and the thread that applies
     *  deltas is blocked on that request until the new browser answers it, so waiting for it here would hang. */
    public void attach(final BrowserChannel channel) {
        sendFullState(channel);
    }

    private void sendFullState(final BrowserChannel channel) {
        browser = channel;
        channel.send(model.fullState());
        synchronized (promptLock) {
            channel.send(prompt());
            channel.send(zonesMessage());
        }
        channel.send(controlsMessage());
        channel.send(gameLog.all());
        requests.replay(channel::send);
        if (gameOver) {
            channel.send(new GameOver());
        }
    }

    public void detach(final BrowserChannel channel) {
        if (browser == channel) {
            browser = null;
        }
    }

    private void send(final JsonObject message) {
        final BrowserChannel b = browser;
        if (b != null) {
            b.send(message);
        }
    }

    private void send(final Record message) {
        send(Wire.encode(message));
    }

    int skippedProperties() {
        return skippedProperties.get();
    }

    BrowserModel freshSnapshot() {
        final GameView gv = getGameView();
        final BrowserModel fresh = new BrowserModel();
        final Map<Integer, Map<TrackableProperty, Object>> snapshot = snapshotter.snapshot(gv);
        if (gv != null && snapshot != null) {
            fresh.reset(rootKey(gv));
            fresh.apply(encode(snapshot), Map.of());
        }
        return fresh;
    }

    private Map<Integer, JsonObject> encode(final Map<Integer, Map<TrackableProperty, Object>> objects) {
        return JsonCodec.encodeAll(objects, name -> {
            skippedProperties.incrementAndGet();
            Logger.warn("Web client: no JSON form for property {}", name);
        });
    }

    private static int rootKey(final GameView gv) {
        return DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_GAME_VIEW, gv.getId());
    }

    @Override
    public void setGameView(final GameView gameView) {
        super.setGameView(gameView);
        final GameView gv = getGameView();
        if (gv == null) {
            return;
        }
        final Map<Integer, Map<TrackableProperty, Object>> snapshot = snapshotter.snapshot(gv);
        if (snapshot == null) {
            // A failed walk must never clear the browser: the old model is closer to the truth than nothing
            Logger.warn("Web client: game view snapshot failed; keeping the previous browser model");
            return;
        }
        gameOver = false;
        model.reset(rootKey(gv));
        model.apply(encode(snapshot), Map.of());
        model.setVisible(visibleCardKeys());
        send(model.fullState());
    }

    @Override
    public void applyDelta(final DeltaPacket packet) {
        super.applyDelta(packet);
        final GameView gv = getGameView();
        if (packet == null || gv == null) {
            return;
        }
        final Map<Integer, JsonObject> newObjects = encode(packet.getNewObjects());
        final Map<Integer, JsonObject> deltas = encode(packet.getObjectDeltas());
        if (!newObjects.isEmpty() || !deltas.isEmpty()) {
            model.apply(newObjects, deltas);
            model.setVisible(visibleCardKeys());
            send(model.stateMessage(false, packet.getSequenceNumber(), newObjects, deltas));
        }
        if (gv.isGameOver() && !gameOver) {
            // Replaces finishGame from FControlGameEventHandler, which does not run here
            gameOver = true;
            send(new GameOver());
        }
    }

    private List<Integer> visibleCardKeys() {
        final Tracker tracker = getGameView().getTracker();
        final List<Integer> visible = new ArrayList<>();
        if (tracker == null) {
            return visible;
        }
        for (final int key : model.keysOfType(DeltaPacket.TYPE_CARD_VIEW)) {
            final CardView card = tracker.getObj(TrackableTypes.CardViewType, DeltaPacket.getIdFromDeltaKey(key));
            if (card != null && mayView(card)) {
                visible.add(key);
            }
        }
        return visible;
    }

    @Override
    public void openView(final TrackableCollection<PlayerView> myPlayers) {
        final List<Integer> keys = new ArrayList<>();
        for (final PlayerView p : myPlayers) {
            keys.add(DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_PLAYER_VIEW, p.getId()));
        }
        model.setLocalPlayers(keys);
        send(model.fullState());
        // A remote seat skips phases only from what the client seeds (PlayerControllerHuman.isUiSetToSkipPhase)
        seedYieldStateOnHost();
    }

    @Override
    public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final PhaseType phase) {
        final int index = phase.ordinal();
        if (index == 0) {
            return false;
        }
        final FPref[] keys = isLocalPlayer(playerTurn) ? FPref.PHASES_HUMAN : FPref.PHASES_AI;
        return !FModel.getPreferences().getPrefBoolean(keys[index - 1]);
    }

    @Override
    public void handleGameEvent(final GameEvent event) {
        // Only the game log: FControlGameEventHandler would post to the host UI thread, and the rest comes from state
        final GameView gv = getGameView();
        final GameLog log = gv == null ? null : gv.getGameLog();
        if (log == null) {
            return;
        }
        log.getEventVisitor().recieve(event);
        final ToBrowser.LogMessage entries = gameLog.added(log);
        if (entries != null) {
            send(entries);
        }
        forwardSound(event);
    }

    // Desktop plays the same sounds from the same events; here the browser plays them, so only the name travels
    private final EventVisualizer sounds = new EventVisualizer(null) {
        @Override
        public SoundEffectType visit(final GameEventGameOutcome event) {
            final PlayerView local = getCurrentPlayer();
            return local != null && local.getLobbyPlayerName().equals(event.winningPlayerName())
                    ? SoundEffectType.WinDuel : SoundEffectType.LoseDuel;
        }

        @Override
        public SoundEffectType visit(final GameEventBlockersDeclared event) {
            // Your own blocks already made their sound as you declared them
            return isLocalPlayer(event.defendingPlayer()) ? null : SoundEffectType.Block;
        }
    };

    private void forwardSound(final GameEvent event) {
        if (!FModel.getPreferences().getPrefBoolean(FPref.UI_ENABLE_SOUNDS)
                || FModel.getPreferences().getPrefInt(FPref.UI_VOL_SOUNDS) <= 0) {
            return;
        }
        final SoundEffectType effect = event.visit(sounds);
        if (effect == null) {
            return;
        }
        final String name = effect == SoundEffectType.ScriptedEffect
                ? sounds.getScriptedSoundEffectName(event) : effect.getResourceFileName();
        if (name == null || name.isEmpty()) {
            return;
        }
        send(new Sound(name, effect.isSynced()));
    }

    // Phase stops are the desktop preferences: one row for the local player's turns, one for everyone else's
    private Controls controlsMessage() {
        final IGameController controller = getGameController();
        final YieldController yields = controller == null ? null : controller.getYieldController();
        final YieldMarker marker = yields == null ? null : yields.getAutoPassUntilMarker();
        return new Controls(WebSettings.stops(FPref.PHASES_HUMAN), WebSettings.stops(FPref.PHASES_AI),
                FModel.getPreferences().getPrefBoolean(FPref.YIELD_AUTO_PASS_NO_ACTIONS), getDayTime(),
                marker == null ? null : new TurnMarker(marker.getPhase(), isLocalPlayer(marker.getPhaseOwner())),
                WebSettings.values());
    }

    @Override
    public void refreshYieldUi(final PlayerView player) {
        send(controlsMessage());
    }

    @Override
    public void updateDayTime(final String daytime) {
        super.updateDayTime(daytime);
        send(controlsMessage());
    }

    private void toggleStop(final PhaseType phase, final boolean mine) {
        if (phase.ordinal() > 0) {
            setStop(phase, mine, !FModel.getPreferences().getPrefBoolean(WebSettings.stopKey(phase, mine)));
        }
    }

    private void setStop(final PhaseType phase, final boolean mine, final boolean stop) {
        final ForgePreferences prefs = FModel.getPreferences();
        prefs.setPref(WebSettings.stopKey(phase, mine), stop);
        prefs.save();
        for (final PlayerView p : getGameView().getPlayers()) {
            if (isLocalPlayer(p) == mine) {
                pushSkipPhaseToControllers(p, phase);
            }
        }
    }

    // Desktop's right-click on a phase: pass priority until that phase. The opponents' row marks the first opponent
    private void toggleMarker(final PhaseType phase, final boolean mine) {
        if (phase.ordinal() == 0) {
            return;
        }
        PlayerView owner = mine ? getCurrentPlayer() : null;
        for (final PlayerView p : getGameView().getPlayers()) {
            if (owner == null && !isLocalPlayer(p)) {
                owner = p;
            }
        }
        if (owner != null) {
            // A marker only fires at a phase the player stops at
            handleYieldMarkerToggle(owner, phase, () -> setStop(phase, mine, true));
        }
    }

    private static PlayerDetail playerDetailMessage(final PlayerView player) {
        final List<String> lines = new ArrayList<>();
        final String[] parts = player.getDetails().split("\n");
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isBlank()) {
                lines.add(parts[i]);
            }
        }
        return new PlayerDetail(DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_PLAYER_VIEW, player.getId()), player.getName(), lines);
    }

    // Desktop's right-click menu on a stack item: auto-yield to an ability, always accept or decline an optional
    // trigger of your own, and yield to the stack
    private StackMenu stackMenuMessage(final IGameController controller, final StackItemView item) {
        final String yieldKey = item.getKey();
        return new StackMenu(DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_STACK_ITEM_VIEW, item.getId()),
                item.isAbility() ? controller.shouldAutoYield(yieldKey) : null,
                item.isOptionalTrigger() && isLocalPlayer(item.getActivatingPlayer()) && !yieldKey.isEmpty()
                        ? controller.getTriggerDecision(yieldKey) : null);
    }

    private void stackYield(final IGameController controller, final StackItemView item, final YieldAction action) {
        final String yieldKey = item.getKey();
        final boolean abilityScope = controller.getYieldController().isAbilityScope();
        switch (action) {
            case autoYield -> controller.setShouldAutoYield(yieldKey, !controller.shouldAutoYield(yieldKey), abilityScope);
            case alwaysYes -> controller.setTriggerDecision(yieldKey,
                    controller.getTriggerDecision(yieldKey) == TriggerDecision.ACCEPT ? TriggerDecision.ASK : TriggerDecision.ACCEPT, abilityScope);
            case alwaysNo -> controller.setTriggerDecision(yieldKey,
                    controller.getTriggerDecision(yieldKey) == TriggerDecision.DECLINE ? TriggerDecision.ASK : TriggerDecision.DECLINE, abilityScope);
            case yieldToStack, yieldToEntireStack -> {
                final PlayerView local = getCurrentPlayer();
                if (local != null) {
                    controller.sendYieldUpdate(new YieldUpdate.StackYield(local, true, action == YieldAction.yieldToStack));
                }
            }
        }
    }

    // mayFlip hides an opponent's face-down card but shows the owner theirs, as on desktop
    private Detail detailMessage(final CardView card) {
        final List<CardFace> faces = new ArrayList<>();
        if (mayView(card)) {
            faces.add(face(card.getCurrentState()));
            if (card.isSplitCard() && card.hasLeftSplitState() && card.hasRightSplitState()) {
                faces.add(face(card.getLeftSplitState()));
                faces.add(face(card.getRightSplitState()));
            } else if (mayFlip(card)) {
                faces.add(face(card.getAlternateState()));
            }
        }
        return new Detail(DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, card.getId()), faces);
    }

    private CardFace face(final CardStateView state) {
        final String pt = state.isCreature() ? state.getPower() + "/" + state.getToughness()
                : state.isPlaneswalker() ? state.getLoyalty()
                : state.isBattle() ? state.getDefense() : null;
        return new CardFace(state.getName(), JsonCodec.manaCost(state.getManaCost()),
                state.getType() == null ? "" : state.getType().toString(), pt,
                CardDetailUtil.composeCardText(state, getGameView(), true).trim(), state.getImageKey());
    }

    @Override
    public void showWaitingTimer(final PlayerView forPlayer, final String waitingForPlayerName) {
        // Its timer posts through FThreads to the host UI thread
    }

    @Override
    protected void updateCurrentPlayer(final PlayerView player) {
    }

    /** The prompt as it stands. Called holding the prompt lock. */
    private Prompt prompt() {
        return new Prompt(promptMessage, priority, promptCard, ok, cancel, focusOk, paying, selectable, selectableMin,
                selectablePlayers, List.copyOf(highlighted));
    }

    private void sendPrompt() {
        send(prompt());
    }

    private static Ref cardRef(final CardView card) {
        return card == null ? null : Ref.card(card.getId());
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        synchronized (promptLock) {
            final String trimmed = withoutTurnState(message);
            promptMessage = trimmed;
            priority = !trimmed.equals(message);
            promptCard = cardRef(card);
            sendPrompt();
        }
    }

    // The phase pill and the stack pile carry the turn, the step and what is waiting, so the priority prompt
    // keeps only the lines that add something, such as the storm count or a macro being recorded
    private static String withoutTurnState(final String message) {
        final Localizer loc = Localizer.getInstance();
        if (!message.startsWith(loc.getMessage("lblPriority") + ":")) {
            return message;
        }
        final List<String> labels = List.of(loc.getMessage("lblPriority"), loc.getMessage("lblTurn"),
                loc.getMessage("lblPhase"), loc.getMessage("lblStack"));
        final StringBuilder kept = new StringBuilder();
        for (final String line : message.split("\n")) {
            if (line.isBlank() || labels.stream().anyMatch(label -> line.startsWith(label + ":"))) {
                continue;
            }
            kept.append(kept.isEmpty() ? "" : "\n").append(line);
        }
        return kept.toString();
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1, final String label2, final boolean enable1, final boolean enable2, final boolean focus1) {
        synchronized (promptLock) {
            ok = new PromptButton(label1, enable1);
            cancel = new PromptButton(label2, enable2);
            focusOk = focus1;
            // Only a mana payment offers Auto, and the browser holds the card being paid for while it does
            paying = Localizer.getInstance().getMessage("lblAuto").equals(label1);
            sendPrompt();
        }
    }

    @Override
    public void setSelectables(final Iterable<CardView> cards, final int min, final int max) {
        super.setSelectables(cards, min, max);
        final List<Ref> refs = new ArrayList<>();
        for (final CardView c : cards) {
            refs.add(cardRef(c));
        }
        synchronized (promptLock) {
            selectable = refs;
            selectableMin = min;
            sendPrompt();
        }
    }

    // Cards the engine says you can act on now, and, at strength two, the ones the Auto button would tap
    @Override
    public void setWeaklySelectable(final Iterable<CardView> cards) {
        super.setWeaklySelectable(cards);
        final List<Ref> playable = new ArrayList<>();
        final List<Ref> autoTap = new ArrayList<>();
        for (final CardView c : new HashSet<>(Lists.newArrayList(cards))) {
            playable.add(cardRef(c));
            if (getWeakSelectableStrength(c) >= 2) {
                autoTap.add(cardRef(c));
            }
        }
        sendPlayable(playable, autoTap);
    }

    @Override
    public void clearWeaklySelectable() {
        super.clearWeaklySelectable();
        sendPlayable(List.of(), List.of());
    }

    private void sendPlayable(final List<Ref> playable, final List<Ref> autoTap) {
        send(new Playable(playable, autoTap));
    }

    @Override
    public void setSelectablePlayers(final Iterable<PlayerView> players) {
        final List<Ref> keys = new ArrayList<>();
        for (final PlayerView p : players) {
            keys.add(Ref.player(p.getId()));
        }
        synchronized (promptLock) {
            selectablePlayers = keys;
            sendPrompt();
        }
    }

    @Override
    public void clearSelectables() {
        super.clearSelectables();
        synchronized (promptLock) {
            selectable = List.of();
            selectablePlayers = List.of();
            selectableMin = 0;
            sendPrompt();
        }
    }

    @Override
    public void setHighlighted(final Iterable<GameEntityView> entities, final boolean b) {
        super.setHighlighted(entities, b);
        synchronized (promptLock) {
            for (final GameEntityView e : entities) {
                final int key = DeltaPacket.makeDeltaKey(e instanceof CardView ? DeltaPacket.TYPE_CARD_VIEW : DeltaPacket.TYPE_PLAYER_VIEW, e.getId());
                if (b) {
                    highlighted.add(key);
                } else {
                    highlighted.remove(key);
                }
            }
            sendPrompt();
        }
    }

    @Override
    public void setCard(final CardView card) {
        synchronized (promptLock) {
            promptCard = cardRef(card);
            sendPrompt();
        }
    }

    @Override
    public void flashIncorrectAction() {
        send(new Flash());
    }

    @Override
    public void message(final String message, final String title) {
        send(notice(title, message, false));
    }

    @Override
    public void showErrorDialog(final String message, final String title) {
        send(notice(title, message, true));
    }

    private static Notice notice(final String title, final String message, final boolean error) {
        return new Notice(title, message, error);
    }

    private Zones zonesMessage() {
        return new Zones(List.copyOf(shownZones.values()));
    }

    private static String zoneKey(final PlayerView player, final ZoneType zone) {
        return player.getId() + "/" + zone.name();
    }

    @Override
    public Iterable<PlayerZoneUpdate> tempShowZones(final PlayerView controller, final Iterable<PlayerZoneUpdate> zonesToUpdate) {
        synchronized (promptLock) {
            for (final PlayerZoneUpdate update : zonesToUpdate) {
                for (final ZoneType zone : update.getZones()) {
                    // The browser always shows the battlefield and the viewer's own hand
                    if (zone == ZoneType.Battlefield || (zone == ZoneType.Hand && update.getPlayer().equals(controller))) {
                        continue;
                    }
                    shownZones.put(zoneKey(update.getPlayer(), zone), new ShownZone(Ref.player(update.getPlayer().getId()), zone));
                }
            }
            send(zonesMessage());
        }
        return zonesToUpdate;
    }

    @Override
    public void hideZones(final PlayerView controller, final Iterable<PlayerZoneUpdate> zonesToUpdate) {
        if (zonesToUpdate == null) {
            return;
        }
        synchronized (promptLock) {
            for (final PlayerZoneUpdate update : zonesToUpdate) {
                for (final ZoneType zone : update.getZones()) {
                    shownZones.remove(zoneKey(update.getPlayer(), zone));
                }
            }
            send(zonesMessage());
        }
    }

    @Override
    public PlayerZoneUpdates openZones(final PlayerView controller, final Collection<ZoneType> zones, final Map<PlayerView, Object> players, final boolean backupLastZones) {
        final PlayerZoneUpdates updates = new PlayerZoneUpdates();
        for (final PlayerView player : players.keySet()) {
            for (final ZoneType zone : zones) {
                if (zone == ZoneType.Battlefield || zone == ZoneType.Hand || zone == ZoneType.Stack) {
                    continue;
                }
                updates.add(new PlayerZoneUpdate(player, zone));
            }
        }
        tempShowZones(controller, updates);
        return updates;
    }

    @Override
    public void restoreOldZones(final PlayerView playerView, final PlayerZoneUpdates playerZoneUpdates) {
        hideZones(playerView, playerZoneUpdates);
    }

    @Override public void showCombat() { }
    @Override public void finishGame() { }
    @Override public void alertUser() { }
    @Override public void enableOverlay() { }
    @Override public void disableOverlay() { }
    @Override public void showManaPool(final PlayerView player) { }
    @Override public void hideManaPool(final PlayerView player) { }
    @Override public void updateShards(final Iterable<PlayerView> shardsUpdate) { }
    @Override public void setPanelSelection(final CardView hostCard) { }
    @Override public void setPlayerAvatar(final LobbyPlayer player, final IHasIcon ihi) { }
    @Override public GameState getGamestate() { return null; }

    // Parking releases the mirror lock so browser actions and replies can proceed while the player decides
    private JsonElement ask(final Record request, final Predicate<JsonElement> valid) {
        final int holds = mirrorLock.getHoldCount();
        for (int i = 0; i < holds; i++) {
            mirrorLock.unlock();
        }
        try {
            return requests.await(request, valid);
        } finally {
            for (int i = 0; i < holds; i++) {
                mirrorLock.lock();
            }
        }
    }

    private boolean isInMirror(final CardView card) {
        final GameView gv = getGameView();
        return gv != null && gv.getTracker() != null && gv.getTracker().getObj(TrackableTypes.CardViewType, card.getId()) == card;
    }

    private <T> List<RequestOption> options(final List<T> items, final FSerializableFunction<T, String> display) {
        final List<RequestOption> out = new ArrayList<>();
        for (final T item : items) {
            final String label = display != null ? display.apply(item) : String.valueOf(item);
            if (item instanceof CardView card) {
                // Ephemeral views (pile splits, getCardForUi copies, LKI) have no mirror entry to point at
                out.add(isInMirror(card)
                        ? new RequestOption(label, cardRef(card), null, null, null)
                        : new RequestOption(label, null, card.getName(),
                                card.getCurrentState() == null ? null : card.getCurrentState().getImageKey(), null));
            } else if (item instanceof PlayerView player) {
                out.add(new RequestOption(label, null, null, null, Ref.player(player.getId())));
            } else if (item instanceof PaperCard paper) {
                out.add(new RequestOption(label, null, paper.getName(), paper.getImageKey(false), null));
            } else if (item instanceof CardFaceView face) {
                out.add(new RequestOption(label, null, face.getName(), ImageKeys.CARD_PREFIX + face.getName(), null));
            } else {
                out.add(new RequestOption(label, null, null, null, null));
            }
        }
        return out;
    }

    private static <T> List<Integer> indicesOf(final List<T> items, final Collection<T> subset) {
        final List<Integer> out = new ArrayList<>();
        if (subset != null) {
            for (int i = 0; i < items.size(); i++) {
                if (subset.contains(items.get(i))) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    static List<Integer> range(final int from, final int to) {
        final List<Integer> a = new ArrayList<>();
        for (int i = from; i < to; i++) {
            a.add(i);
        }
        return a;
    }

    static Predicate<JsonElement> indexList(final int size, final int min, final int max) {
        return v -> {
            if (!v.isJsonArray()) {
                return false;
            }
            final Set<Integer> seen = new HashSet<>();
            for (final JsonElement e : v.getAsJsonArray()) {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                    return false;
                }
                final int i = e.getAsInt();
                if (i < 0 || i >= size || !seen.add(i)) {
                    return false;
                }
            }
            return seen.size() >= min && (max < 0 || seen.size() <= max);
        };
    }

    static Predicate<JsonElement> singleIndex(final int size) {
        return v -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsInt() >= 0 && v.getAsInt() < size;
    }

    static Predicate<JsonElement> amounts(final int count, final int total, final int perMin, final boolean maySkip) {
        return v -> {
            if (v.isJsonNull()) {
                return maySkip;
            }
            if (!v.isJsonArray() || v.getAsJsonArray().size() != count) {
                return false;
            }
            int sum = 0;
            for (final JsonElement e : v.getAsJsonArray()) {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || e.getAsInt() < perMin) {
                    return false;
                }
                sum += e.getAsInt();
            }
            return sum == total;
        };
    }

    private static <T> List<T> pick(final List<T> items, final JsonElement indices) {
        final List<T> out = new ArrayList<>();
        for (final JsonElement e : indices.getAsJsonArray()) {
            out.add(items.get(e.getAsInt()));
        }
        return out;
    }

    private static List<Integer> toList(final int[] values) {
        final List<Integer> a = new ArrayList<>();
        for (final int v : values) {
            a.add(v);
        }
        return a;
    }

    private void revealFirst(final DelayedReveal reveal) {
        if (reveal == null) {
            return;
        }
        final String prefix = reveal.getMessagePrefix() == null ? "" : reveal.getMessagePrefix();
        getChoices(prefix, -1, -1, new ArrayList<>(reveal.getCards()), null, null);
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final List<T> choices, final List<T> selected, final FSerializableFunction<T, String> display) {
        if (min < 0 && max < 0) {
            // AbstractGuiGame.reveal: display only, the return value is ignored
            ask(choicesRequest(ChoiceKind.reveal, message, min, max, choices, selected, display, null, null, List.of()), v -> true);
            return new ArrayList<>();
        }
        final int need = Math.min(Math.max(min, 0), choices.size());
        // Spells being chosen are already drawn on the stack, so they are picked there rather than from a list
        final ChoicesRequest request = choicesRequest(ChoiceKind.choices, message, need, max, choices, selected, display,
                stackKeysFor(choices), null, range(0, need));
        return pick(choices, ask(request, indexList(choices.size(), need, max)));
    }

    /** The stack item each choice is, in the same order, or null unless every choice is a spell on the stack. */
    private <T> List<Integer> stackKeysFor(final List<T> choices) {
        final GameView gv = getGameView();
        if (gv == null || gv.getStack() == null || choices.isEmpty()) {
            return null;
        }
        final List<Integer> keys = new ArrayList<>();
        for (final T choice : choices) {
            if (!(choice instanceof SpellAbilityView spell) || spell.getHostCard() == null) {
                return null;
            }
            Integer found = null;
            for (final StackItemView item : gv.getStack()) {
                if (item.getSourceCard() != null && item.getSourceCard().getId() == spell.getHostCard().getId()) {
                    found = DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_STACK_ITEM_VIEW, item.getId());
                    break;
                }
            }
            if (found == null) {
                return null;
            }
            keys.add(found);
        }
        return keys;
    }

    private <T> ChoicesRequest choicesRequest(final ChoiceKind kind, final String message, final int min, final int max,
            final List<T> items, final List<T> selected, final FSerializableFunction<T, String> display,
            final List<Integer> stackKeys, final BrowserClick at, final List<Integer> onDefault) {
        return new ChoicesRequest(kind, message, min, max, options(items, display), indicesOf(items, selected), stackKeys,
                at == null ? null : at.x(), at == null ? null : at.y(), onDefault);
    }

    /** Asks the browser to pick from a list; {@code onDefault} is the answer taken when it cannot. */
    private <T> List<T> askChoices(final String message, final int min, final int max, final List<T> items,
            final List<T> selected, final FSerializableFunction<T, String> display, final List<Integer> onDefault) {
        final ChoicesRequest request = choicesRequest(ChoiceKind.choices, message, min, max, items, selected, display,
                null, null, onDefault);
        return pick(items, ask(request, indexList(items.size(), min, max)));
    }

    @Override
    public <T> OrderResult<T> order(final String title, final String top, final int remainingObjectsMin, final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices, final CardView referenceCard, final boolean sideboardingMode, final boolean showRememberCheckbox) {
        final List<T> items = new ArrayList<>(sourceChoices);
        if (destChoices != null) {
            items.addAll(destChoices);
        }
        final int remainingMax = remainingObjectsMax < 0 ? items.size() : Math.min(remainingObjectsMax, items.size());
        final int min = Math.max(0, items.size() - remainingMax);
        final int max = Math.max(min, items.size() - Math.max(remainingObjectsMin, 0));
        final List<Integer> preselected = destChoices == null || destChoices.isEmpty() ? range(0, min) : range(sourceChoices.size(), items.size());
        final OrderRequest request = new OrderRequest(title, top, min, max, options(items, null), preselected,
                showRememberCheckbox, cardRef(referenceCard), new OrderAnswer(preselected, false));
        final Predicate<JsonElement> indicesOk = indexList(items.size(), min, max);
        final JsonElement reply = ask(request, v -> v.isJsonObject() && v.getAsJsonObject().has("indices") && indicesOk.test(v.getAsJsonObject().get("indices")));
        final JsonObject r = reply.getAsJsonObject();
        return new OrderResult<>(pick(items, r.get("indices")), r.has("remember") && r.get("remember").getAsBoolean());
    }

    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards, final Iterable<CardView> manipulable, final boolean toTop, final boolean toBottom, final boolean toAnywhere) {
        final List<CardView> list = Lists.newArrayList(cards);
        // The original order is always a valid answer; arrangeForMove throws on an empty list
        final ManipulateRequest request = new ManipulateRequest(title, options(list, null),
                indicesOf(list, Lists.newArrayList(manipulable)), toTop, toBottom, toAnywhere, range(0, list.size()));
        return pick(list, ask(request, indexList(list.size(), list.size(), list.size())));
    }

    private int askOption(final String title, final String message, final CardView card, final List<String> labels, final int defaultIndex) {
        final int def = Math.max(0, Math.min(defaultIndex, labels.size() - 1));
        final OptionRequest request = new OptionRequest(title, message, card != null && isInMirror(card) ? cardRef(card) : null,
                new ArrayList<>(labels), def);
        return ask(request, singleIndex(labels.size())).getAsInt();
    }

    @Override
    public boolean confirm(final CardView c, final String question, final boolean defaultIsYes, final List<String> options) {
        final Localizer loc = Localizer.getInstance();
        final List<String> labels = options == null || options.size() < 2 ? List.of(loc.getMessage("lblYes"), loc.getMessage("lblNo")) : options;
        return askOption("", question, c, labels, defaultIsYes ? 0 : 1) == 0;
    }

    @Override
    public boolean showConfirmDialog(final String message, final String title, final String yesButtonText, final String noButtonText, final boolean defaultYes) {
        return askOption(title, message, null, List.of(yesButtonText, noButtonText), defaultYes ? 0 : 1) == 0;
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon, final List<String> options, final int defaultOption) {
        return askOption(title, message, null, options, defaultOption);
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon, final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
        if (inputOptions != null && !inputOptions.isEmpty()) {
            return inputOptions.get(askOption(title, message, null, inputOptions, Math.max(0, inputOptions.indexOf(initialInput))));
        }
        final JsonElement reply = ask(new TextRequest(title, message, initialInput, isNumeric, initialInput), v -> {
            if (v.isJsonNull()) {
                return true;
            }
            if (!v.isJsonPrimitive()) {
                return false;
            }
            return !isNumeric || v.getAsString().matches("-?\\d+");
        });
        return reply.isJsonNull() ? null : reply.getAsString();
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(final String title, final List<? extends GameEntityView> optionList, final DelayedReveal delayedReveal, final boolean isOptional) {
        revealFirst(delayedReveal);
        final List<GameEntityView> list = new ArrayList<>(optionList);
        final int need = isOptional || list.isEmpty() ? 0 : 1;
        final List<GameEntityView> picked = askChoices(title, need, 1, list, null, null, range(0, need));
        return picked.isEmpty() ? null : picked.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title, final List<? extends GameEntityView> optionList, final int min, final int max, final DelayedReveal delayedReveal) {
        revealFirst(delayedReveal);
        final List<GameEntityView> list = new ArrayList<>(optionList);
        final int need = Math.min(Math.max(min, 0), list.size());
        return askChoices(title, need, max, list, null, null, range(0, need));
    }

    /** A click from the browser: the right button asks for the card's list of abilities, as it does on desktop. */
    private record BrowserClick(boolean menu, int x, int y) implements ITriggerEvent {
        @Override
        public int getButton() {
            return menu ? 3 : 1;
        }

        @Override
        public int getX() {
            return x;
        }

        @Override
        public int getY() {
            return y;
        }
    }

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities, final ITriggerEvent triggerEvent) {
        if (abilities.isEmpty()) {
            return null;
        }
        // One thing to do needs no asking, unless the ability itself says to ask, as on desktop
        if (abilities.size() == 1 && (triggerEvent == null || !abilities.get(0).promptIfOnlyPossibleAbility())) {
            return abilities.get(0);
        }
        // A left-click plays what the card leads with; the list is what the right button is for
        if (triggerEvent != null && triggerEvent.getButton() != 3) {
            return abilities.stream().filter(SpellAbilityView::canPlay).findFirst().orElse(null);
        }
        // No answer means no ability chosen, which is how a cancelled click reads
        final ChoicesRequest request = choicesRequest(ChoiceKind.choices, hostCard == null ? "" : hostCard.getName(), 0, 1,
                abilities, null, null, null, triggerEvent instanceof BrowserClick click ? click : null, List.of());
        final List<SpellAbilityView> picked = pick(abilities, ask(request, indexList(abilities.size(), 0, 1)));
        return picked.isEmpty() ? null : picked.get(0);
    }

    static int[] defaultCombatSplit(final List<CardView> blockers, final int damage, final boolean hasDefender) {
        final int n = blockers.size() + (hasDefender ? 1 : 0);
        final int[] split = new int[n];
        int remaining = damage;
        for (int i = 0; i < blockers.size() && remaining > 0; i++) {
            final CardView blocker = blockers.get(i);
            final int lethal = Math.max(0, blocker.getLethalDamage());
            final int assigned = Math.min(remaining, lethal);
            split[i] = assigned;
            remaining -= assigned;
        }
        if (n > 0) {
            split[n - 1] += remaining;
        }
        return split;
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers, final int damage, final GameEntityView defender, final boolean overrideOrder, final boolean maySkip) {
        final List<GameEntityView> recipients = new ArrayList<>(blockers);
        if (defender != null) {
            recipients.add(defender);
        }
        final DistributeRequest request = new DistributeRequest(attacker == null ? "" : attacker.getName(), damage, 0,
                options(recipients, null), cardRef(attacker), maySkip, toList(defaultCombatSplit(blockers, damage, defender != null)));
        final JsonElement reply = ask(request, amounts(recipients.size(), damage, 0, maySkip));
        if (reply.isJsonNull()) {
            return null;
        }
        final Map<CardView, Integer> result = new HashMap<>();
        final JsonArray a = reply.getAsJsonArray();
        for (int i = 0; i < recipients.size(); i++) {
            // PlayerControllerHuman reads a null key as the defender
            result.put(i < blockers.size() ? blockers.get(i) : null, a.get(i).getAsInt());
        }
        return result;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> target, final int amount, final boolean atLeastOne, final String amountLabel) {
        final List<Object> recipients = new ArrayList<>(target.keySet());
        final int perMin = atLeastOne ? 1 : 0;
        final int[] def = new int[recipients.size()];
        int remaining = amount;
        for (int i = 0; i < def.length; i++) {
            def[i] = perMin;
            remaining -= perMin;
        }
        if (def.length > 0) {
            def[0] += Math.max(0, remaining);
        }
        final DistributeRequest request = new DistributeRequest(amountLabel, amount, perMin, options(recipients, null),
                cardRef(effectSource), false, toList(def));
        final JsonArray reply = ask(request, amounts(recipients.size(), amount, perMin, false)).getAsJsonArray();
        final Map<Object, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < recipients.size(); i++) {
            result.put(recipients.get(i), reply.get(i).getAsInt());
        }
        return result;
    }

    // One entry per distinct card; the reply is how many copies of each go in the main deck.
    // PlayerControllerHuman checks deck sizes and asks again if the result is illegal
    @Override
    public List<PaperCard> sideboard(final CardPool sideboard, final CardPool main, final String message) {
        final Map<PaperCard, int[]> counts = new LinkedHashMap<>();
        for (final Map.Entry<PaperCard, Integer> e : main) {
            counts.computeIfAbsent(e.getKey(), k -> new int[2])[0] += e.getValue();
        }
        for (final Map.Entry<PaperCard, Integer> e : sideboard) {
            counts.computeIfAbsent(e.getKey(), k -> new int[2])[1] += e.getValue();
        }
        final List<PaperCard> cards = new ArrayList<>(counts.keySet());
        final List<SideboardEntry> entries = new ArrayList<>();
        final List<Integer> inMain = new ArrayList<>();
        final int[] totals = new int[cards.size()];
        for (int i = 0; i < cards.size(); i++) {
            final PaperCard card = cards.get(i);
            final int[] c = counts.get(card);
            totals[i] = c[0] + c[1];
            entries.add(new SideboardEntry(card.getName(),
                    ImageKeys.CARD_PREFIX + card.getName() + "|" + card.getEdition() + "|" + card.getArtIndex(), totals[i]));
            inMain.add(c[0]);
        }
        final JsonElement reply = ask(new SideboardRequest(message, entries, inMain, inMain), v -> {
            if (!v.isJsonArray() || v.getAsJsonArray().size() != totals.length) {
                return false;
            }
            for (int i = 0; i < totals.length; i++) {
                final JsonElement e = v.getAsJsonArray().get(i);
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || e.getAsInt() < 0 || e.getAsInt() > totals[i]) {
                    return false;
                }
            }
            return true;
        });
        final List<PaperCard> newMain = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            for (int n = reply.getAsJsonArray().get(i).getAsInt(); n > 0; n--) {
                newMain.add(cards.get(i));
            }
        }
        return newMain;
    }

    public void onBrowserMessage(final JsonObject msg) {
        final String type = msg.get("t").getAsString();
        if ("reply".equals(type)) {
            final Reply reply = Wire.decode(msg, Reply.class);
            requests.complete(reply.id(), reply.value());
            return;
        }
        final boolean leaving = "concede".equals(type)
                || ("nextGame".equals(type) && Wire.decode(msg, NextGame.class).decision() == NextGameDecision.QUIT);
        if (leaving) {
            // The host's engine thread waits on an open request with no timeout, so answer it before leaving
            requests.cancelAll();
        }
        mirrorLock.lock();
        try {
            if ("detail".equals(type)) {
                final CardView card = lookup(Wire.decode(msg, KeyCommand.class).key(), TrackableTypes.CardViewType);
                if (card != null) {
                    send(detailMessage(card));
                }
                return;
            }
            if ("playerDetail".equals(type)) {
                final PlayerView player = lookup(Wire.decode(msg, KeyCommand.class).key(), TrackableTypes.PlayerViewType);
                if (player != null) {
                    send(playerDetailMessage(player));
                }
                return;
            }
            final IGameController controller = getGameController();
            if (controller == null) {
                return;
            }
            switch (type) {
                case "selectCard" -> {
                    final SelectCard click = Wire.decode(msg, SelectCard.class);
                    final CardView card = lookup(click.key(), TrackableTypes.CardViewType);
                    if (card != null) {
                        // A right-click asks for the list of what the card can do; a left-click takes the first
                        controller.selectCard(card, null, new BrowserClick(click.menu(), click.x(), click.y()));
                    }
                }
                case "selectPlayer" -> {
                    final PlayerView player = lookup(Wire.decode(msg, KeyCommand.class).key(), TrackableTypes.PlayerViewType);
                    if (player != null) {
                        controller.selectPlayer(player, null);
                    }
                }
                case "ok" -> controller.selectButtonOk();
                case "cancel" -> controller.selectButtonCancel();
                case "concede" -> controller.concede();
                case "endTurn" -> YieldController.endTurn(controller, getCurrentPlayer());
                case "undo" -> controller.undoLastAction();
                case "autoPass" -> {
                    YieldController.toggleAutoPassNoActions(controller);
                    send(controlsMessage());
                }
                case "toggleStop" -> {
                    final PhaseCommand stop = Wire.decode(msg, PhaseCommand.class);
                    toggleStop(stop.phase(), stop.mine());
                    send(controlsMessage());
                }
                case "toggleMarker" -> {
                    final PhaseCommand marker = Wire.decode(msg, PhaseCommand.class);
                    toggleMarker(marker.phase(), marker.mine());
                }
                case "useMana" -> controller.useMana(Wire.decode(msg, UseMana.class).color());
                case "setSetting" -> {
                    final SetSetting setting = Wire.decode(msg, SetSetting.class);
                    WebSettings.set(controller, setting.key(), setting.value());
                    send(controlsMessage());
                }
                case "stackMenu" -> {
                    final StackItemView item = lookup(Wire.decode(msg, KeyCommand.class).key(), TrackableTypes.StackItemViewType);
                    if (item != null) {
                        send(stackMenuMessage(controller, item));
                    }
                }
                case "stackYield" -> {
                    final StackYield yield = Wire.decode(msg, StackYield.class);
                    final StackItemView item = lookup(yield.key(), TrackableTypes.StackItemViewType);
                    if (item != null && yield.action() != null) {
                        stackYield(controller, item, yield.action());
                        send(stackMenuMessage(controller, item));
                    }
                }
                case "nextGame" -> controller.nextGameDecision(Wire.decode(msg, NextGame.class).decision());
                default -> Logger.warn("Web client: unknown browser message {}", type);
            }
        } finally {
            mirrorLock.unlock();
        }
    }

    private <T> T lookup(final int key, final TrackableType<T> type) {
        final GameView gv = getGameView();
        if (gv == null || gv.getTracker() == null) {
            return null;
        }
        return gv.getTracker().getObj(type, DeltaPacket.getIdFromDeltaKey(key));
    }
}
