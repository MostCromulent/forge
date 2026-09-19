package forge.web;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.ImageKeys;
import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameLog;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.GameLogVerbosity;
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
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.skin.FSkinProp;
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
    private final JsonObject prompt = initialPrompt();
    private final Set<Integer> highlighted = new LinkedHashSet<>();
    private final Map<String, JsonObject> shownZones = new LinkedHashMap<>();
    // Written on the dispatch thread, replayed to a reloading browser from the socket thread
    private final List<JsonObject> logEntries = new ArrayList<>();
    private GameLog loggedLog;
    private int loggedCount;
    private volatile BrowserChannel browser;
    private volatile JsonObject gameOver;

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

    public void attach(final BrowserChannel channel) {
        browser = channel;
        channel.send(model.fullState());
        synchronized (promptLock) {
            channel.send(prompt.deepCopy());
            channel.send(zonesMessage());
        }
        channel.send(controlsMessage());
        synchronized (logEntries) {
            channel.send(logMessage(logEntries, true));
        }
        requests.replay(channel::send);
        final JsonObject over = gameOver;
        if (over != null) {
            channel.send(over);
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
        gameOver = null;
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
        if (gv.isGameOver() && gameOver == null) {
            // Replaces finishGame from FControlGameEventHandler, which does not run here
            gameOver = JsonCodec.message("gameOver");
            send(gameOver);
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
        forwardNewLogEntries(log);
    }

    private void forwardNewLogEntries(final GameLog log) {
        final List<GameLogEntry> all = log.getAllEntries();
        final boolean newGame = log != loggedLog;
        if (newGame) {
            loggedLog = log;
            loggedCount = 0;
        }
        final Set<GameLogEntryType> shown = shownLogTypes();
        final List<JsonObject> added = new ArrayList<>();
        for (final GameLogEntry entry : all.subList(loggedCount, all.size())) {
            if (shown.contains(entry.type())) {
                final JsonObject e = new JsonObject();
                e.addProperty("type", entry.type().name());
                e.addProperty("message", entry.message());
                final CardView card = entry.sourceCard();
                if (card != null && card.getCurrentState() != null && mayView(card)) {
                    e.addProperty("card", DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, card.getId()));
                    e.addProperty("imageKey", card.getCurrentState().getImageKey());
                }
                added.add(e);
            }
        }
        loggedCount = all.size();
        synchronized (logEntries) {
            if (newGame) {
                logEntries.clear();
            }
            logEntries.addAll(added);
        }
        if (newGame || !added.isEmpty()) {
            send(logMessage(added, newGame));
        }
    }

    // The desktop log's verbosity preference
    private static Set<GameLogEntryType> shownLogTypes() {
        final ForgePreferences prefs = FModel.getPreferences();
        final GameLogVerbosity verbosity = GameLogVerbosity.fromString(prefs.getPref(FPref.DEV_LOG_ENTRY_TYPE));
        return verbosity == GameLogVerbosity.CUSTOM ? prefs.getCustomLogTypes() : verbosity.getIncludedTypes();
    }

    private static JsonObject logMessage(final List<JsonObject> entries, final boolean full) {
        final JsonObject m = JsonCodec.message("log");
        m.addProperty("full", full);
        final JsonArray a = new JsonArray();
        entries.forEach(a::add);
        m.add("entries", a);
        return m;
    }

    // Phase stops are the desktop preferences: one row for the local player's turns, one for everyone else's
    private JsonObject controlsMessage() {
        final JsonObject m = JsonCodec.message("controls");
        m.add("myStops", stops(FPref.PHASES_HUMAN));
        m.add("otherStops", stops(FPref.PHASES_AI));
        m.addProperty("autoPass", FModel.getPreferences().getPrefBoolean(FPref.YIELD_AUTO_PASS_NO_ACTIONS));
        m.addProperty("dayTime", getDayTime());
        m.add("settings", settings());
        final IGameController controller = getGameController();
        final YieldController yields = controller == null ? null : controller.getYieldController();
        final YieldMarker marker = yields == null ? null : yields.getAutoPassUntilMarker();
        if (marker != null) {
            final JsonObject mk = new JsonObject();
            mk.addProperty("phase", marker.getPhase().name());
            mk.addProperty("mine", isLocalPlayer(marker.getPhaseOwner()));
            m.add("marker", mk);
        }
        return m;
    }

    // Settings the options dialog shares with the desktop client, as their preference values
    private static final Map<String, FPref> SETTING_PREFS = Map.of(
            "interruptAttackers", FPref.YIELD_INTERRUPT_ON_ATTACKERS,
            "interruptOpponentSpell", FPref.YIELD_INTERRUPT_ON_OPPONENT_SPELL,
            "interruptTargeting", FPref.YIELD_INTERRUPT_ON_TARGETING,
            "interruptTriggers", FPref.YIELD_INTERRUPT_ON_TRIGGERS,
            "interruptMassRemoval", FPref.YIELD_INTERRUPT_ON_MASS_REMOVAL);

    private static JsonObject settings() {
        final ForgePreferences prefs = FModel.getPreferences();
        final JsonObject s = new JsonObject();
        SETTING_PREFS.forEach((key, pref) -> s.addProperty(key, prefs.getPrefBoolean(pref)));
        s.addProperty("autoPassNoActions", prefs.getPrefBoolean(FPref.YIELD_AUTO_PASS_NO_ACTIONS));
        s.addProperty("autoYieldMode", ForgeConstants.AUTO_DECISION_PER_CARD.equals(prefs.getPref(FPref.UI_AUTO_DECISION_MODE)) ? "card" : "ability");
        s.addProperty("logDetail", GameLogVerbosity.fromString(prefs.getPref(FPref.DEV_LOG_ENTRY_TYPE)).name());
        s.addProperty("arrows", prefs.getPref(FPref.UI_TARGETING_OVERLAY));
        return s;
    }

    private void setSetting(final IGameController controller, final String key, final String value) {
        final ForgePreferences prefs = FModel.getPreferences();
        final FPref pref = SETTING_PREFS.get(key);
        if (pref != null) {
            prefs.setPref(pref, Boolean.parseBoolean(value));
        } else if ("autoPassNoActions".equals(key)) {
            if (Boolean.parseBoolean(value) != prefs.getPrefBoolean(FPref.YIELD_AUTO_PASS_NO_ACTIONS)) {
                YieldController.toggleAutoPassNoActions(controller);
            }
            return;
        } else if ("autoYieldMode".equals(key)) {
            prefs.setPref(FPref.UI_AUTO_DECISION_MODE,
                    "card".equals(value) ? ForgeConstants.AUTO_DECISION_PER_CARD : ForgeConstants.AUTO_DECISION_PER_ABILITY);
        } else if ("logDetail".equals(key)) {
            prefs.setPref(FPref.DEV_LOG_ENTRY_TYPE, GameLogVerbosity.fromString(value).toString());
        } else if ("arrows".equals(key)) {
            prefs.setPref(FPref.UI_TARGETING_OVERLAY, value);
        } else {
            Logger.warn("Web client: unknown setting {}", key);
            return;
        }
        prefs.save();
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

    private static JsonArray stops(final FPref[] keys) {
        final JsonArray out = new JsonArray();
        final PhaseType[] phases = PhaseType.values();
        for (int i = 1; i < phases.length; i++) {
            if (FModel.getPreferences().getPrefBoolean(keys[i - 1])) {
                out.add(phases[i].name());
            }
        }
        return out;
    }

    private void toggleStop(final PhaseType phase, final boolean mine) {
        if (phase.ordinal() > 0) {
            setStop(phase, mine, !FModel.getPreferences().getPrefBoolean(stopKey(phase, mine)));
        }
    }

    private static FPref stopKey(final PhaseType phase, final boolean mine) {
        return (mine ? FPref.PHASES_HUMAN : FPref.PHASES_AI)[phase.ordinal() - 1];
    }

    private void setStop(final PhaseType phase, final boolean mine, final boolean stop) {
        final ForgePreferences prefs = FModel.getPreferences();
        prefs.setPref(stopKey(phase, mine), stop);
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

    // Desktop's avatar tooltip: life, counters, hand and land counts, commander damage and tax, and so on
    private static JsonObject playerDetailMessage(final PlayerView player) {
        final JsonObject m = JsonCodec.message("playerDetail");
        m.addProperty("key", DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_PLAYER_VIEW, player.getId()));
        m.addProperty("name", player.getName());
        final JsonArray lines = new JsonArray();
        final String[] parts = player.getDetails().split("\n");
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isBlank()) {
                lines.add(parts[i]);
            }
        }
        m.add("lines", lines);
        return m;
    }

    // Desktop's right-click menu on a stack item: auto-yield to an ability, always accept or decline an optional
    // trigger of your own, and yield to the stack
    private JsonObject stackMenuMessage(final IGameController controller, final StackItemView item) {
        final JsonObject m = JsonCodec.message("stackMenu");
        m.addProperty("key", DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_STACK_ITEM_VIEW, item.getId()));
        final String yieldKey = item.getKey();
        if (item.isAbility()) {
            m.addProperty("autoYield", controller.shouldAutoYield(yieldKey));
        }
        if (item.isOptionalTrigger() && isLocalPlayer(item.getActivatingPlayer()) && !yieldKey.isEmpty()) {
            m.addProperty("trigger", controller.getTriggerDecision(yieldKey).name());
        }
        return m;
    }

    private void stackYield(final IGameController controller, final StackItemView item, final String action) {
        final String yieldKey = item.getKey();
        final boolean abilityScope = controller.getYieldController().isAbilityScope();
        switch (action) {
            case "autoYield" -> controller.setShouldAutoYield(yieldKey, !controller.shouldAutoYield(yieldKey), abilityScope);
            case "alwaysYes" -> controller.setTriggerDecision(yieldKey,
                    controller.getTriggerDecision(yieldKey) == TriggerDecision.ACCEPT ? TriggerDecision.ASK : TriggerDecision.ACCEPT, abilityScope);
            case "alwaysNo" -> controller.setTriggerDecision(yieldKey,
                    controller.getTriggerDecision(yieldKey) == TriggerDecision.DECLINE ? TriggerDecision.ASK : TriggerDecision.DECLINE, abilityScope);
            case "yieldToStack", "yieldToEntireStack" -> {
                final PlayerView local = getCurrentPlayer();
                if (local != null) {
                    controller.sendYieldUpdate(new YieldUpdate.StackYield(local, true, "yieldToStack".equals(action)));
                }
            }
            default -> Logger.warn("Web client: unknown stack yield action {}", action);
        }
    }

    // mayFlip hides an opponent's face-down card but shows the owner theirs, as on desktop
    private JsonObject detailMessage(final CardView card) {
        final JsonObject m = JsonCodec.message("detail");
        m.addProperty("key", DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, card.getId()));
        final JsonArray faces = new JsonArray();
        if (mayView(card)) {
            faces.add(face(card.getCurrentState()));
            if (card.isSplitCard() && card.hasLeftSplitState() && card.hasRightSplitState()) {
                faces.add(face(card.getLeftSplitState()));
                faces.add(face(card.getRightSplitState()));
            } else if (mayFlip(card)) {
                faces.add(face(card.getAlternateState()));
            }
        }
        m.add("faces", faces);
        return m;
    }

    private JsonObject face(final CardStateView state) {
        final JsonObject f = new JsonObject();
        f.addProperty("name", state.getName());
        f.addProperty("cost", JsonCodec.manaCost(state.getManaCost()));
        f.addProperty("type", state.getType() == null ? "" : state.getType().toString());
        if (state.isCreature()) {
            f.addProperty("pt", state.getPower() + "/" + state.getToughness());
        } else if (state.isPlaneswalker()) {
            f.addProperty("pt", state.getLoyalty());
        } else if (state.isBattle()) {
            f.addProperty("pt", state.getDefense());
        }
        f.addProperty("text", CardDetailUtil.composeCardText(state, getGameView(), true).trim());
        f.addProperty("imageKey", state.getImageKey());
        return f;
    }

    @Override
    public void showWaitingTimer(final PlayerView forPlayer, final String waitingForPlayerName) {
        // Its timer posts through FThreads to the host UI thread
    }

    @Override
    protected void updateCurrentPlayer(final PlayerView player) {
    }

    private static JsonObject initialPrompt() {
        final JsonObject p = JsonCodec.message("prompt");
        p.addProperty("message", "");
        p.add("card", JsonNull.INSTANCE);
        p.add("ok", button("", false));
        p.add("cancel", button("", false));
        p.addProperty("focusOk", false);
        p.add("selectable", new JsonArray());
        p.add("selectablePlayers", new JsonArray());
        p.add("highlighted", new JsonArray());
        return p;
    }

    private static JsonObject button(final String label, final boolean enabled) {
        final JsonObject b = new JsonObject();
        b.addProperty("label", label);
        b.addProperty("enabled", enabled);
        return b;
    }

    private void sendPrompt() {
        send(prompt.deepCopy());
    }

    private static JsonElement cardRef(final CardView card) {
        return card == null ? JsonNull.INSTANCE : JsonCodec.ref(DeltaPacket.TYPE_CARD_VIEW, card.getId());
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        synchronized (promptLock) {
            final String trimmed = withoutTurnState(message);
            prompt.addProperty("message", trimmed);
            prompt.addProperty("priority", !trimmed.equals(message));
            prompt.add("card", cardRef(card));
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
            prompt.add("ok", button(label1, enable1));
            prompt.add("cancel", button(label2, enable2));
            prompt.addProperty("focusOk", focus1);
            sendPrompt();
        }
    }

    @Override
    public void setSelectables(final Iterable<CardView> cards, final int min, final int max) {
        super.setSelectables(cards, min, max);
        final JsonArray selectable = new JsonArray();
        for (final CardView c : cards) {
            selectable.add(cardRef(c));
        }
        synchronized (promptLock) {
            prompt.add("selectable", selectable);
            sendPrompt();
        }
    }

    @Override
    public void setSelectablePlayers(final Iterable<PlayerView> players) {
        final JsonArray keys = new JsonArray();
        for (final PlayerView p : players) {
            keys.add(JsonCodec.ref(DeltaPacket.TYPE_PLAYER_VIEW, p.getId()));
        }
        synchronized (promptLock) {
            prompt.add("selectablePlayers", keys);
            sendPrompt();
        }
    }

    @Override
    public void clearSelectables() {
        super.clearSelectables();
        synchronized (promptLock) {
            prompt.add("selectable", new JsonArray());
            prompt.add("selectablePlayers", new JsonArray());
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
            final JsonArray keys = new JsonArray();
            highlighted.forEach(keys::add);
            prompt.add("highlighted", keys);
            sendPrompt();
        }
    }

    @Override
    public void setCard(final CardView card) {
        synchronized (promptLock) {
            prompt.add("card", cardRef(card));
            sendPrompt();
        }
    }

    @Override
    public void flashIncorrectAction() {
        send(JsonCodec.message("flash"));
    }

    @Override
    public void message(final String message, final String title) {
        send(notice(title, message, false));
    }

    @Override
    public void showErrorDialog(final String message, final String title) {
        send(notice(title, message, true));
    }

    private static JsonObject notice(final String title, final String message, final boolean error) {
        final JsonObject n = JsonCodec.message("notice");
        n.addProperty("title", title);
        n.addProperty("message", message);
        n.addProperty("error", error);
        return n;
    }

    private JsonObject zonesMessage() {
        final JsonObject m = JsonCodec.message("zones");
        final JsonArray show = new JsonArray();
        shownZones.values().forEach(z -> show.add(z.deepCopy()));
        m.add("show", show);
        return m;
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
                    final JsonObject z = new JsonObject();
                    z.add("player", JsonCodec.ref(DeltaPacket.TYPE_PLAYER_VIEW, update.getPlayer().getId()));
                    z.addProperty("zone", zone.name());
                    shownZones.put(zoneKey(update.getPlayer(), zone), z);
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
    private JsonElement ask(final String kind, final JsonObject payload, final JsonElement defaultAnswer, final Predicate<JsonElement> valid) {
        final int holds = mirrorLock.getHoldCount();
        for (int i = 0; i < holds; i++) {
            mirrorLock.unlock();
        }
        try {
            return requests.await(kind, payload, defaultAnswer, valid);
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

    private <T> JsonArray options(final List<T> items, final FSerializableFunction<T, String> display) {
        final JsonArray out = new JsonArray();
        for (final T item : items) {
            final JsonObject o = new JsonObject();
            o.addProperty("label", display != null ? display.apply(item) : String.valueOf(item));
            if (item instanceof CardView card) {
                if (isInMirror(card)) {
                    o.add("card", cardRef(card));
                } else {
                    // Ephemeral views (pile splits, getCardForUi copies, LKI) have no mirror entry to point at
                    o.addProperty("name", card.getName());
                    o.addProperty("imageKey", card.getCurrentState() == null ? null : card.getCurrentState().getImageKey());
                }
            } else if (item instanceof PlayerView player) {
                o.add("player", JsonCodec.ref(DeltaPacket.TYPE_PLAYER_VIEW, player.getId()));
            } else if (item instanceof CardFaceView face) {
                // Naming a card: show the card itself
                o.addProperty("name", face.getName());
                o.addProperty("imageKey", ImageKeys.CARD_PREFIX + face.getName());
            }
            out.add(o);
        }
        return out;
    }

    private static <T> JsonArray indicesOf(final List<T> items, final Collection<T> subset) {
        final JsonArray out = new JsonArray();
        if (subset != null) {
            for (int i = 0; i < items.size(); i++) {
                if (subset.contains(items.get(i))) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    static JsonArray range(final int from, final int to) {
        final JsonArray a = new JsonArray();
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

    private static JsonArray toJson(final int[] values) {
        final JsonArray a = new JsonArray();
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
        final JsonObject p = new JsonObject();
        p.addProperty("message", message);
        p.addProperty("min", min);
        p.addProperty("max", max);
        p.add("options", options(choices, display));
        p.add("selected", indicesOf(choices, selected));
        if (min < 0 && max < 0) {
            // AbstractGuiGame.reveal: display only, the return value is ignored
            ask("reveal", p, new JsonArray(), v -> true);
            return new ArrayList<>();
        }
        final int need = Math.min(Math.max(min, 0), choices.size());
        return pick(choices, ask("choices", p, range(0, need), indexList(choices.size(), need, max)));
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
        final JsonObject p = new JsonObject();
        p.addProperty("title", title);
        p.addProperty("top", top);
        p.addProperty("min", min);
        p.addProperty("max", max);
        p.add("options", options(items, null));
        final JsonArray preselected = destChoices == null || destChoices.isEmpty() ? range(0, min) : range(sourceChoices.size(), items.size());
        p.add("selected", preselected);
        p.addProperty("remember", showRememberCheckbox);
        p.add("card", cardRef(referenceCard));
        final JsonObject def = new JsonObject();
        def.add("indices", preselected);
        def.addProperty("remember", false);
        final Predicate<JsonElement> indicesOk = indexList(items.size(), min, max);
        final JsonElement reply = ask("order", p, def, v -> v.isJsonObject() && v.getAsJsonObject().has("indices") && indicesOk.test(v.getAsJsonObject().get("indices")));
        final JsonObject r = reply.getAsJsonObject();
        return new OrderResult<>(pick(items, r.get("indices")), r.has("remember") && r.get("remember").getAsBoolean());
    }

    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards, final Iterable<CardView> manipulable, final boolean toTop, final boolean toBottom, final boolean toAnywhere) {
        final List<CardView> list = Lists.newArrayList(cards);
        final JsonObject p = new JsonObject();
        p.addProperty("title", title);
        p.add("options", options(list, null));
        p.add("movable", indicesOf(list, Lists.newArrayList(manipulable)));
        p.addProperty("toTop", toTop);
        p.addProperty("toBottom", toBottom);
        p.addProperty("toAnywhere", toAnywhere);
        // The original order is always a valid answer; arrangeForMove throws on an empty list
        return pick(list, ask("manipulate", p, range(0, list.size()), indexList(list.size(), list.size(), list.size())));
    }

    private int askOption(final String title, final String message, final CardView card, final List<String> labels, final int defaultIndex) {
        final JsonObject p = new JsonObject();
        p.addProperty("title", title);
        p.addProperty("message", message);
        p.add("card", card != null && isInMirror(card) ? cardRef(card) : JsonNull.INSTANCE);
        final JsonArray l = new JsonArray();
        labels.forEach(l::add);
        p.add("labels", l);
        final int def = Math.max(0, Math.min(defaultIndex, labels.size() - 1));
        return ask("option", p, new JsonPrimitive(def), singleIndex(labels.size())).getAsInt();
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
        final JsonObject p = new JsonObject();
        p.addProperty("title", title);
        p.addProperty("message", message);
        p.addProperty("initial", initialInput);
        p.addProperty("numeric", isNumeric);
        final JsonElement reply = ask("text", p, initialInput == null ? JsonNull.INSTANCE : new JsonPrimitive(initialInput), v -> {
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
        final JsonObject p = new JsonObject();
        p.addProperty("message", title);
        p.addProperty("min", need);
        p.addProperty("max", 1);
        p.add("options", options(list, null));
        p.add("selected", new JsonArray());
        final List<GameEntityView> picked = pick(list, ask("choices", p, range(0, need), indexList(list.size(), need, 1)));
        return picked.isEmpty() ? null : picked.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title, final List<? extends GameEntityView> optionList, final int min, final int max, final DelayedReveal delayedReveal) {
        revealFirst(delayedReveal);
        final List<GameEntityView> list = new ArrayList<>(optionList);
        final int need = Math.min(Math.max(min, 0), list.size());
        final JsonObject p = new JsonObject();
        p.addProperty("message", title);
        p.addProperty("min", need);
        p.addProperty("max", max);
        p.add("options", options(list, null));
        p.add("selected", new JsonArray());
        return pick(list, ask("choices", p, range(0, need), indexList(list.size(), need, max)));
    }

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities, final ITriggerEvent triggerEvent) {
        final JsonObject p = new JsonObject();
        p.addProperty("message", hostCard == null ? "" : hostCard.getName());
        p.addProperty("min", 0);
        p.addProperty("max", 1);
        p.add("options", options(abilities, null));
        p.add("selected", new JsonArray());
        final List<SpellAbilityView> picked = pick(abilities, ask("choices", p, new JsonArray(), indexList(abilities.size(), 0, 1)));
        return picked.isEmpty() ? null : picked.get(0);
    }

    static int[] defaultCombatSplit(final List<CardView> blockers, final int damage, final boolean hasDefender) {
        final int n = blockers.size() + (hasDefender ? 1 : 0);
        final int[] split = new int[n];
        int remaining = damage;
        for (int i = 0; i < blockers.size() && remaining > 0; i++) {
            final CardView blocker = blockers.get(i);
            final int toughness = blocker.getCurrentState() == null ? 0 : blocker.getCurrentState().getToughness();
            final int lethal = Math.max(0, toughness - blocker.getDamage());
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
        final JsonObject p = new JsonObject();
        p.addProperty("message", attacker == null ? "" : attacker.getName());
        p.addProperty("amount", damage);
        p.addProperty("perMin", 0);
        p.add("options", options(recipients, null));
        p.add("card", cardRef(attacker));
        p.addProperty("maySkip", maySkip);
        final JsonElement reply = ask("distribute", p, toJson(defaultCombatSplit(blockers, damage, defender != null)),
                amounts(recipients.size(), damage, 0, maySkip));
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
        final JsonObject p = new JsonObject();
        p.addProperty("message", amountLabel);
        p.addProperty("amount", amount);
        p.addProperty("perMin", perMin);
        p.add("options", options(recipients, null));
        p.add("card", cardRef(effectSource));
        p.addProperty("maySkip", false);
        final JsonArray reply = ask("distribute", p, toJson(def), amounts(recipients.size(), amount, perMin, false)).getAsJsonArray();
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
        final JsonArray entries = new JsonArray();
        final JsonArray inMain = new JsonArray();
        final int[] totals = new int[cards.size()];
        for (int i = 0; i < cards.size(); i++) {
            final PaperCard card = cards.get(i);
            final int[] c = counts.get(card);
            totals[i] = c[0] + c[1];
            final JsonObject o = new JsonObject();
            o.addProperty("name", card.getName());
            o.addProperty("imageKey", ImageKeys.CARD_PREFIX + card.getName() + "|" + card.getEdition() + "|" + card.getArtIndex());
            o.addProperty("total", totals[i]);
            entries.add(o);
            inMain.add(c[0]);
        }
        final JsonObject p = new JsonObject();
        p.addProperty("message", message);
        p.add("entries", entries);
        p.add("main", inMain);
        final JsonElement reply = ask("sideboard", p, inMain, v -> {
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
            requests.complete(msg.get("id").getAsInt(), msg.get("value"));
            return;
        }
        final boolean leaving = "concede".equals(type) || ("nextGame".equals(type) && "QUIT".equals(msg.get("decision").getAsString()));
        if (leaving) {
            // The host's engine thread waits on an open request with no timeout, so answer it before leaving
            requests.cancelAll();
        }
        mirrorLock.lock();
        try {
            if ("detail".equals(type)) {
                final CardView card = lookup(msg, TrackableTypes.CardViewType);
                if (card != null) {
                    send(detailMessage(card));
                }
                return;
            }
            if ("playerDetail".equals(type)) {
                final PlayerView player = lookup(msg, TrackableTypes.PlayerViewType);
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
                    final CardView card = lookup(msg, TrackableTypes.CardViewType);
                    if (card != null) {
                        controller.selectCard(card, null, null);
                    }
                }
                case "selectPlayer" -> {
                    final PlayerView player = lookup(msg, TrackableTypes.PlayerViewType);
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
                    toggleStop(PhaseType.valueOf(msg.get("phase").getAsString()), msg.get("mine").getAsBoolean());
                    send(controlsMessage());
                }
                case "toggleMarker" -> {
                    toggleMarker(PhaseType.valueOf(msg.get("phase").getAsString()), msg.get("mine").getAsBoolean());
                    send(controlsMessage());
                }
                case "useMana" -> controller.useMana(msg.get("color").getAsByte());
                case "setSetting" -> {
                    setSetting(controller, msg.get("key").getAsString(), msg.get("value").getAsString());
                    send(controlsMessage());
                }
                case "stackMenu" -> {
                    final StackItemView item = lookup(msg, TrackableTypes.StackItemViewType);
                    if (item != null) {
                        send(stackMenuMessage(controller, item));
                    }
                }
                case "stackYield" -> {
                    final StackItemView item = lookup(msg, TrackableTypes.StackItemViewType);
                    if (item != null) {
                        stackYield(controller, item, msg.get("action").getAsString());
                        send(stackMenuMessage(controller, item));
                    }
                }
                case "nextGame" -> controller.nextGameDecision(NextGameDecision.valueOf(msg.get("decision").getAsString()));
                default -> Logger.warn("Web client: unknown browser message {}", type);
            }
        } finally {
            mirrorLock.unlock();
        }
    }

    private <T> T lookup(final JsonObject msg, final TrackableType<T> type) {
        final GameView gv = getGameView();
        if (gv == null || gv.getTracker() == null || !msg.has("key")) {
            return null;
        }
        return gv.getTracker().getObj(type, DeltaPacket.getIdFromDeltaKey(msg.get("key").getAsInt()));
    }
}
