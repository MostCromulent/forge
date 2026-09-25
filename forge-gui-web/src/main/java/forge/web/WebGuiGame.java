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
import forge.game.GameState;
import forge.game.GameView;
import forge.game.card.CardFaceView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.item.PaperCard;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.DrawOfferMessage;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.match.YieldController;
import forge.gamemodes.match.YieldMarker;
import forge.gamemodes.match.YieldUpdate;
import forge.gamemodes.net.DeltaPacket;
import forge.gamemodes.net.NetworkGuiGame;
import forge.gamemodes.net.server.DeltaSyncManager;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.skin.FSkinProp;
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
import forge.web.FromBrowser.AutoDecisionCommand;
import forge.web.FromBrowser.DrawOfferCommand;
import forge.web.FromBrowser.KeyCommand;
import forge.web.FromBrowser.NextGame;
import forge.web.FromBrowser.PhaseCommand;
import forge.web.FromBrowser.Reply;
import forge.web.FromBrowser.SelectCard;
import forge.web.FromBrowser.SetSetting;
import forge.web.FromBrowser.SetStops;
import forge.web.FromBrowser.StackYield;
import forge.web.FromBrowser.UseMana;
import forge.web.FromBrowser.YieldAction;
import forge.web.ToBrowser.AutoPassRequest;
import forge.web.ToBrowser.ChoiceKind;
import forge.web.ToBrowser.ChoicesRequest;
import forge.web.ToBrowser.Controls;
import forge.web.ToBrowser.DistributeRequest;
import forge.web.ToBrowser.Flash;
import forge.web.ToBrowser.GameOver;
import forge.web.ToBrowser.ManipulateRequest;
import forge.web.ToBrowser.Notice;
import forge.web.ToBrowser.OptionRequest;
import forge.web.ToBrowser.OrderAnswer;
import forge.web.ToBrowser.OrderRequest;
import forge.web.ToBrowser.Playable;
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
import java.util.List;
import java.util.function.Supplier;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    // Netplay drops a click's trigger event on its way to the host, so the ability menu reads the click from here
    private final AtomicReference<BrowserClick> lastClick = new AtomicReference<>();
    private final PromptState prompt = new PromptState(this::send);
    /** Something the player would want to see has happened since they last held priority or watched a pass. */
    private volatile boolean unseen;
    // Zones are shown and hidden from the dispatch thread and replayed to a reloading browser from the socket thread
    private final Object zonesLock = new Object();
    private final Map<String, ShownZone> shownZones = new LinkedHashMap<>();
    // Written on the dispatch thread, replayed to a reloading browser from the socket thread
    private final WebGameLog gameLog;
    private final BrowserSounds sounds = new BrowserSounds(this::getCurrentPlayer, this::isLocalPlayer);
    /** The settings of the player this GUI is the view of, which are not the shared preferences unless it is the host. */
    private final PlayerSettings settings;
    private volatile BrowserChannel browser;
    private volatile boolean gameOver;
    /** What the game did since the last state message, in order. Filled and drained on the dispatch thread: a packet's
     *  events are handled inside its applyDelta, so they leave with the state change they explain. */
    private final List<Record> events = new ArrayList<>();

    /** A GUI for the host's own seat, whose settings are Forge's preferences. */
    public WebGuiGame() {
        this(PlayerSettings.saved());
    }

    WebGuiGame(final PlayerSettings settings) {
        this.settings = settings;
        this.gameLog = new WebGameLog(this::mayView, settings);
    }

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
        prompt.sendTo(channel::send);
        synchronized (zonesLock) {
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
        // A whole new state has nothing to animate from, so what led up to it is dropped
        events.clear();
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
        if (!newObjects.isEmpty() || !deltas.isEmpty() || !events.isEmpty()) {
            model.apply(newObjects, deltas);
            model.setVisible(visibleCardKeys());
            final List<Record> happened = List.copyOf(events);
            events.clear();
            send(model.stateMessage(false, packet.getSequenceNumber(), newObjects, deltas, happened));
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

    /** Told when the game opens, which for a guest happens on the host's say rather than its own. */
    private volatile Runnable onOpen = () -> { };

    public void whenOpened(final Runnable action) {
        onOpen = action;
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
        // The seed read the shared preferences for everything else, so this player's own follow it
        final IGameController controller = getGameController();
        if (controller != null) {
            WebSettings.applyAll(settings, controller);
        }
        onOpen.run();
    }

    @Override
    public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final PhaseType phase) {
        // The first phase has no stop of its own
        return phase.ordinal() > 0 && !settings.getBoolean(WebSettings.stopKey(phase, isLocalPlayer(playerTurn)));
    }

    @Override
    public void handleGameEvent(final GameEvent event) {
        // The log, the sound and what the browser animates. FControlGameEventHandler would post to the host UI
        // thread, and everything else comes from state
        if (BrowserEvents.worthSeeing(event, this::isLocalPlayer)) {
            unseen = true;
        }
        final Record forwarded = BrowserEvents.forwarded(event);
        if (forwarded != null) {
            events.add(forwarded);
        }
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
        if (settings.getBoolean(FPref.UI_ENABLE_SOUNDS) && settings.getInt(FPref.UI_VOL_SOUNDS) > 0) {
            final Sound sound = sounds.soundFor(event);
            if (sound != null) {
                send(sound);
            }
        }
    }

    // Phase stops are the desktop preferences: one row for the local player's turns, one for everyone else's
    private Controls controlsMessage() {
        final IGameController controller = getGameController();
        final YieldController yields = controller == null ? null : controller.getYieldController();
        final YieldMarker marker = yields == null ? null : yields.getAutoPassUntilMarker();
        return new Controls(WebSettings.stops(settings, FPref.PHASES_HUMAN), WebSettings.stops(settings, FPref.PHASES_AI),
                settings.getBoolean(FPref.YIELD_AUTO_PASS_NO_ACTIONS), getDayTime(),
                marker == null ? null : new TurnMarker(marker.getPhase(), isLocalPlayer(marker.getPhaseOwner())),
                yields != null && yields.autoPassUntilEndOfTurn(), yields != null && yields.autoPassUntilStackEmpty(),
                WebSettings.values(settings));
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
            setStop(phase, mine, !settings.getBoolean(WebSettings.stopKey(phase, mine)));
        }
    }

    private void setStop(final PhaseType phase, final boolean mine, final boolean stop) {
        settings.set(WebSettings.stopKey(phase, mine), stop);
        settings.save();
        pushStop(phase, mine);
    }

    /** Tells the game a stop changed. One set before the game has players is read when they are seeded instead. */
    private void pushStop(final PhaseType phase, final boolean mine) {
        if (getGameView() == null) {
            return;
        }
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

    @Override
    public void showWaitingTimer(final PlayerView forPlayer, final String waitingForPlayerName) {
        // AbstractGuiGame's timer posts through FThreads to the host UI thread, so none is started here
    }

    @Override
    protected void updateCurrentPlayer(final PlayerView player) {
    }

    private static Ref cardRef(final CardView card) {
        return PromptState.cardRef(card);
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        prompt.message(message, card);
        // Holding priority, the player is looking at the board as it stands
        if (prompt.priority()) {
            unseen = false;
        }
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1, final String label2, final boolean enable1, final boolean enable2, final boolean focus1) {
        prompt.buttons(label1, label2, enable1, enable2, focus1);
    }

    @Override
    public void setSelectables(final Iterable<CardView> cards, final int min, final int max) {
        super.setSelectables(cards, min, max);
        final List<Ref> refs = new ArrayList<>();
        for (final CardView c : cards) {
            refs.add(cardRef(c));
        }
        prompt.selectable(refs, min);
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
    public void setSelectablePlayers(final Supplier<Iterable<PlayerView>> players) {
        final List<Ref> keys = new ArrayList<>();
        for (final PlayerView p : players.get()) {
            keys.add(Ref.player(p.getId()));
        }
        prompt.selectablePlayers(keys);
    }

    @Override
    public void clearSelectables() {
        super.clearSelectables();
        prompt.clearSelectables();
    }

    @Override
    public void setHighlighted(final Iterable<GameEntityView> entities, final boolean b) {
        super.setHighlighted(entities, b);
        prompt.highlight(entities, b);
    }

    @Override
    public void setCard(final CardView card) {
        prompt.card(card);
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
        synchronized (zonesLock) {
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
        synchronized (zonesLock) {
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
            // The AI's unplayable cards are shown as the game is set up, and nothing depends on reading them, so the
            // game goes on and a notice offers them instead of a dialog it waits on
            if (message != null && message.startsWith(Localizer.getInstance().getMessage("lblAICantPlayCards"))) {
                send(new ToBrowser.Aside(message, options(choices, display)));
                return new ArrayList<>();
            }
            // AbstractGuiGame.reveal: display only, the return value is ignored
            ask(choicesRequest(ChoiceKind.reveal, message, min, max, choices, selected, display, null, null, List.of()), v -> true);
            return new ArrayList<>();
        }
        final int need = Math.min(Math.max(min, 0), choices.size());
        // Spells being chosen are already drawn on the stack, so they are picked there rather than from a list
        final ChoicesRequest request = choicesRequest(ChoiceKind.choices, message, need, max, choices, selected, display,
                stackKeysFor(choices), null, Answers.range(0, need));
        return Answers.pick(choices, ask(request, Answers.indexList(choices.size(), need, max)));
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
        return new ChoicesRequest(kind, message, min, max, options(items, display), Answers.indicesOf(items, selected), stackKeys,
                at == null ? null : at.x(), at == null ? null : at.y(), onDefault);
    }

    /** Asks the browser to pick from a list; {@code onDefault} is the answer taken when it cannot. */
    private <T> List<T> askChoices(final String message, final int min, final int max, final List<T> items,
            final List<T> selected, final FSerializableFunction<T, String> display, final List<Integer> onDefault) {
        final ChoicesRequest request = choicesRequest(ChoiceKind.choices, message, min, max, items, selected, display,
                null, null, onDefault);
        return Answers.pick(items, ask(request, Answers.indexList(items.size(), min, max)));
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
        final List<Integer> preselected = destChoices == null || destChoices.isEmpty() ? Answers.range(0, min) : Answers.range(sourceChoices.size(), items.size());
        final OrderRequest request = new OrderRequest(title, top, min, max, options(items, null), preselected,
                showRememberCheckbox, cardRef(referenceCard), new OrderAnswer(preselected, false));
        final Predicate<JsonElement> indicesOk = Answers.indexList(items.size(), min, max);
        final JsonElement reply = ask(request, v -> v.isJsonObject() && v.getAsJsonObject().has("indices") && indicesOk.test(v.getAsJsonObject().get("indices")));
        final JsonObject r = reply.getAsJsonObject();
        return new OrderResult<>(Answers.pick(items, r.get("indices")), r.has("remember") && r.get("remember").getAsBoolean());
    }

    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards, final Iterable<CardView> manipulable, final boolean toTop, final boolean toBottom, final boolean toAnywhere) {
        final List<CardView> list = Lists.newArrayList(cards);
        // The original order is always a valid answer; arrangeForMove throws on an empty list
        final ManipulateRequest request = new ManipulateRequest(title, options(list, null),
                Answers.indicesOf(list, Lists.newArrayList(manipulable)), toTop, toBottom, toAnywhere, Answers.range(0, list.size()));
        return Answers.pick(list, ask(request, Answers.indexList(list.size(), list.size(), list.size())));
    }

    private int askOption(final String title, final String message, final CardView card, final List<String> labels, final int defaultIndex) {
        final int def = Math.max(0, Math.min(defaultIndex, labels.size() - 1));
        final OptionRequest request = new OptionRequest(title, message, card != null && isInMirror(card) ? cardRef(card) : null,
                new ArrayList<>(labels), def);
        return ask(request, Answers.singleIndex(labels.size())).getAsInt();
    }

    /**
     * This is what paces the game. Priority does not pass for the player while something has happened they have not
     * seen: the pass is shown coming on the browser's pass button, and the game waits for it, or for the player to
     * stop it. With nothing new it goes by with only Forge's own pause. A yield the player asked for, such as End
     * Turn, is them skipping ahead on purpose, so it is not held either.
     */
    @Override
    public boolean confirmAutoPass(final int delayMs) {
        final IGameController controller = getGameController();
        final YieldController yields = controller == null ? null : controller.getYieldController();
        if (!unseen || (yields != null && yields.isYieldActive())) {
            return super.confirmAutoPass(delayMs);
        }
        unseen = false;
        return ask(new AutoPassRequest(delayMs, true), JsonElement::isJsonPrimitive).getAsBoolean();
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
        final List<GameEntityView> picked = askChoices(title, need, 1, list, null, null, Answers.range(0, need));
        return picked.isEmpty() ? null : picked.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title, final List<? extends GameEntityView> optionList, final int min, final int max, final DelayedReveal delayedReveal) {
        revealFirst(delayedReveal);
        final List<GameEntityView> list = new ArrayList<>(optionList);
        final int need = Math.min(Math.max(min, 0), list.size());
        return askChoices(title, need, max, list, null, null, Answers.range(0, need));
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

    /** Lists, forgets or switches off remembered decisions through the controller, as desktop's dialog does. */
    private void autoDecisions(final IGameController controller, final AutoDecisionCommand command) {
        final YieldController yields = controller.getYieldController();
        if (yields == null) {
            return;
        }
        final boolean abilityScope = yields.isAbilityScope();
        switch (command.action()) {
            case remove -> forget(controller, yields, command.key(), abilityScope);
            case clear -> {
                final List<String> keys = new ArrayList<>(Lists.newArrayList(yields.getAutoYields()));
                yields.getAutoTriggers().forEach(e -> keys.add(e.getKey()));
                keys.forEach(key -> forget(controller, yields, key, abilityScope));
            }
            case disableYields -> controller.setDisableAutoYields(command.on());
            case disableTriggers -> controller.setDisableAutoTriggers(command.on());
            case list -> { }
        }
        final List<ToBrowser.AutoDecision> entries = new ArrayList<>();
        yields.getAutoYields().forEach(key -> entries.add(new ToBrowser.AutoDecision(key, ToBrowser.AutoDecisionKind.yield)));
        yields.getAutoTriggers().forEach(e -> entries.add(new ToBrowser.AutoDecision(e.getKey(),
                e.getValue() == TriggerDecision.ACCEPT ? ToBrowser.AutoDecisionKind.accept : ToBrowser.AutoDecisionKind.decline)));
        entries.sort(java.util.Comparator.comparing(ToBrowser.AutoDecision::key, String.CASE_INSENSITIVE_ORDER));
        send(new ToBrowser.AutoDecisions(entries, yields.getDisableAutoYields(), yields.getDisableAutoTriggers()));
    }

    private static void forget(final IGameController controller, final YieldController yields, final String key,
            final boolean abilityScope) {
        if (key == null) {
            return;
        }
        if (Lists.newArrayList(yields.getAutoYields()).contains(key)) {
            controller.setShouldAutoYield(key, false, abilityScope);
        }
        final boolean decided = Lists.newArrayList(yields.getAutoTriggers()).stream().anyMatch(e -> key.equals(e.getKey()));
        if (decided) {
            controller.setTriggerDecision(key, TriggerDecision.ASK, abilityScope);
        }
    }

    @Override
    public void updateDrawOffer(final DrawOfferMessage.Status update) {
        if (update == null) {
            return;
        }
        final PlayerView offerer = update.offerer();
        final boolean open = update.result() == null;
        final boolean mine = offerer != null && isLocalPlayer(offerer);
        final boolean waitingOnMe = open && update.entries().stream()
                .anyMatch(e -> isLocalPlayer(e.player()) && e.vote() == forge.game.DrawOffer.Vote.PENDING);
        send(new ToBrowser.DrawOffer(offerer == null ? null : Ref.player(offerer.getId()), open, mine, waitingOnMe));
        if (open) {
            return;
        }
        if (update.result() == DrawOfferMessage.Result.ACCEPTED) {
            send(new Notice("Draw agreed", "Every player accepted the draw.", false));
            return;
        }
        final List<String> declined = update.entries().stream()
                .filter(e -> e.vote() == forge.game.DrawOffer.Vote.DECLINED && e.player() != null)
                .map(e -> isLocalPlayer(e.player()) ? "You" : e.player().getName()).toList();
        send(new Notice("Draw declined", declined.isEmpty() ? "The game goes on." : String.join(", ", declined) + " declined.", false));
    }

    /** An ability as a menu item: its first line, as desktop's menu shows it. */
    private static String firstLine(final SpellAbilityView ability) {
        final String text = String.valueOf(ability);
        final int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities, final ITriggerEvent event) {
        final ITriggerEvent triggerEvent = event != null ? event : lastClick.getAndSet(null);
        if (abilities.isEmpty()) {
            return null;
        }
        // One thing to do needs no asking, unless the ability itself says to ask, as on desktop
        if (abilities.size() == 1 && (triggerEvent == null || !abilities.get(0).promptIfOnlyPossibleAbility())) {
            return abilities.get(0);
        }
        // A click, with either button, offers what the card can do now in a menu where it was clicked, as desktop
        // does; one thing it can do is simply done
        final List<SpellAbilityView> offered = triggerEvent == null ? abilities
                : abilities.stream().filter(SpellAbilityView::canPlay).toList();
        if (offered.isEmpty()) {
            return null;
        }
        if (triggerEvent != null && offered.size() == 1 && !offered.get(0).promptIfOnlyPossibleAbility()) {
            return offered.get(0);
        }
        // No answer means no ability chosen, which is how a cancelled click reads
        final ChoicesRequest request = choicesRequest(ChoiceKind.choices, hostCard == null ? "" : hostCard.getName(), 0, 1,
                offered, null, WebGuiGame::firstLine, null, triggerEvent instanceof BrowserClick click ? click : null, List.of());
        final List<SpellAbilityView> picked = Answers.pick(offered, ask(request, Answers.indexList(offered.size(), 0, 1)));
        return picked.isEmpty() ? null : picked.get(0);
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers, final int damage, final GameEntityView defender, final boolean overrideOrder, final boolean maySkip) {
        final List<GameEntityView> recipients = new ArrayList<>(blockers);
        if (defender != null) {
            recipients.add(defender);
        }
        final DistributeRequest request = new DistributeRequest(attacker == null ? "" : attacker.getName(), damage, 0,
                options(recipients, null), cardRef(attacker), maySkip, Answers.toList(Answers.defaultCombatSplit(blockers, damage, defender != null)));
        final JsonElement reply = ask(request, Answers.amounts(recipients.size(), damage, 0, maySkip));
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
                cardRef(effectSource), false, Answers.toList(def));
        final JsonArray reply = ask(request, Answers.amounts(recipients.size(), amount, perMin, false)).getAsJsonArray();
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
                    send(CardDetails.card(card, getGameView(), mayView(card), mayFlip(card)));
                }
                return;
            }
            if ("setSetting".equals(type)) {
                // A setting belongs to the player, not the game, so one sent before the game has a controller still counts
                final SetSetting setting = Wire.decode(msg, SetSetting.class);
                WebSettings.set(settings, getGameController(), setting.key(), setting.value());
                send(controlsMessage());
                return;
            }
            if ("setStops".equals(type)) {
                // Stops are the player's too; the game, once it has players, is told of each one that changed
                final SetStops stops = Wire.decode(msg, SetStops.class);
                for (final PhaseType phase : WebSettings.setStops(settings, stops.mine(), stops.phases())) {
                    pushStop(phase, stops.mine());
                }
                send(controlsMessage());
                return;
            }
            if ("playerDetail".equals(type)) {
                final PlayerView player = lookup(Wire.decode(msg, KeyCommand.class).key(), TrackableTypes.PlayerViewType);
                if (player != null) {
                    send(CardDetails.player(player));
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
                        final BrowserClick at = new BrowserClick(click.menu(), click.x(), click.y());
                        lastClick.set(at);
                        controller.selectCard(card, null, at);
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
                case "drawOffer" -> controller.drawOfferAction(Wire.decode(msg, DrawOfferCommand.class).action());
                case "autoDecisions" -> autoDecisions(controller, Wire.decode(msg, AutoDecisionCommand.class));
                case "endTurn" -> YieldController.endTurn(controller, getCurrentPlayer());
                case "undo" -> controller.undoLastAction();
                case "autoPass" -> {
                    WebSettings.set(settings, controller, "autoPassNoActions",
                            String.valueOf(!settings.getBoolean(FPref.YIELD_AUTO_PASS_NO_ACTIONS)));
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
