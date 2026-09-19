package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plays the web seat with lands only: plays one per turn, activates Evolving Wilds, picks the first selectable card
 * when an input needs a selection, holds every request open before answering with its default, and reloads once.
 */
final class ScriptedBrowser implements BrowserChannel {
    final BrowserModel model = new BrowserModel();
    final CountDownLatch gameOver = new CountDownLatch(1);
    final Map<String, Integer> requestKinds = new ConcurrentHashMap<>();
    final Set<String> zonesShown = ConcurrentHashMap.newKeySet();
    volatile boolean reloaded;
    private final WebGuiGame gui;
    private final long holdMillis;
    private final ScheduledExecutorService actions = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "ScriptedBrowser");
        t.setDaemon(true);
        return t;
    });
    private final Set<Integer> answered = ConcurrentHashMap.newKeySet();
    private final Set<Integer> tried = ConcurrentHashMap.newKeySet();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong promptVersion = new AtomicLong();
    private volatile JsonObject lastPrompt;
    private volatile int root = -1;
    private volatile JsonArray localPlayers = new JsonArray();
    private volatile int turn = -1;

    ScriptedBrowser(final WebGuiGame gui, final long holdMillis) {
        this.gui = gui;
        this.holdMillis = holdMillis;
    }

    @Override
    public void send(final JsonObject m) {
        switch (m.get("t").getAsString()) {
            case "state" -> {
                model.applyStateMessage(m);
                root = m.get("root").getAsInt();
                localPlayers = m.getAsJsonArray("localPlayers");
            }
            case "gameOver" -> gameOver.countDown();
            case "zones" -> m.getAsJsonArray("show").forEach(z -> zonesShown.add(z.getAsJsonObject().get("zone").getAsString()));
            case "request" -> onRequest(m);
            case "prompt" -> {
                lastPrompt = m;
                actOnLatestPrompt();
            }
            default -> { }
        }
    }

    private void onRequest(final JsonObject m) {
        final int id = m.get("id").getAsInt();
        if (!answered.add(id)) {
            // Replays after a reload repeat open requests
            return;
        }
        requestKinds.merge(m.get("kind").getAsString(), 1, Integer::sum);
        if (requests.incrementAndGet() == 3 && !reloaded) {
            reloaded = true;
            actions.schedule(() -> {
                answered.remove(id);
                gui.attach(this);
            }, 20, TimeUnit.MILLISECONDS);
        }
        final JsonElement value = answerFor(m);
        actions.schedule(() -> {
            gui.onBrowserMessage(FakeBrowser.reply(id, value));
            // A declined request (e.g. no ability chosen) leaves the same input open without a new prompt
            actOnLatestPrompt();
        }, holdMillis, TimeUnit.MILLISECONDS);
    }

    // The default for an optional choice is "none"; picking the first option is what plays the land when a card offers abilities
    private static JsonElement answerFor(final JsonObject request) {
        final JsonElement def = request.get("default");
        if ("choices".equals(request.get("kind").getAsString()) && def.isJsonArray() && def.getAsJsonArray().isEmpty()
                && request.getAsJsonArray("options").size() > 0 && request.get("max").getAsInt() != 0) {
            final JsonArray first = new JsonArray();
            first.add(0);
            return first;
        }
        return def;
    }

    // Two prompt messages arrive per input (text, then buttons); act once, on the latest
    private void actOnLatestPrompt() {
        final long version = promptVersion.incrementAndGet();
        actions.schedule(() -> {
            final JsonObject prompt = lastPrompt;
            if (promptVersion.get() == version && prompt != null) {
                act(prompt);
            }
        }, 200, TimeUnit.MILLISECONDS);
    }

    private void act(final JsonObject prompt) {
        // A rejected click (flashIncorrectAction) sends no new prompt; try again if nothing arrives
        final long seen = promptVersion.get();
        actions.schedule(() -> {
            if (promptVersion.get() == seen) {
                actOnLatestPrompt();
            }
        }, 1500, TimeUnit.MILLISECONDS);
        final boolean okEnabled = prompt.getAsJsonObject("ok").get("enabled").getAsBoolean();
        if (!okEnabled) {
            // Clicking a card that is already selected deselects it, so pick one that is not
            final Set<Integer> chosen = new HashSet<>();
            prompt.getAsJsonArray("highlighted").forEach(k -> chosen.add(k.getAsInt()));
            for (final JsonElement ref : prompt.getAsJsonArray("selectable")) {
                final int key = ref.getAsJsonObject().get("ref").getAsInt();
                if (!chosen.contains(key)) {
                    select(key);
                    return;
                }
            }
            if (prompt.getAsJsonObject("cancel").get("enabled").getAsBoolean()) {
                gui.onBrowserMessage(FakeBrowser.action("cancel"));
            }
            return;
        }
        final Integer card = cardToTry();
        if (card != null) {
            select(card);
        } else {
            gui.onBrowserMessage(FakeBrowser.action("ok"));
        }
    }

    private void select(final int key) {
        final JsonObject msg = FakeBrowser.action("selectCard");
        msg.addProperty("key", key);
        gui.onBrowserMessage(msg);
    }

    // In its own first main phase: each hand card once per turn, then Evolving Wilds on the battlefield
    private Integer cardToTry() {
        final Map<Integer, JsonObject> objects = model.objectsCopy();
        final JsonObject game = objects.get(root);
        if (game == null || localPlayers.isEmpty() || !game.has("PlayerTurn") || !game.has("Turn") || !game.has("Phase")) {
            return null;
        }
        final int me = localPlayers.get(0).getAsInt();
        if (game.getAsJsonObject("PlayerTurn").get("ref").getAsInt() != me || !"MAIN1".equals(game.get("Phase").getAsString())) {
            return null;
        }
        final int currentTurn = game.get("Turn").getAsInt();
        if (currentTurn != turn) {
            turn = currentTurn;
            tried.clear();
        }
        final JsonObject player = objects.get(me);
        for (final String zone : new String[]{"Hand", "Battlefield"}) {
            if (player == null || !player.has(zone)) {
                continue;
            }
            for (final JsonElement ref : player.getAsJsonArray(zone)) {
                if (!ref.isJsonObject()) {
                    continue;
                }
                final int key = ref.getAsJsonObject().get("ref").getAsInt();
                final JsonObject card = objects.get(key);
                if (card == null || tried.contains(key) || ("Battlefield".equals(zone) && !isWilds(objects, card))) {
                    continue;
                }
                tried.add(key);
                return key;
            }
        }
        return null;
    }

    private static boolean isWilds(final Map<Integer, JsonObject> objects, final JsonObject card) {
        if (!card.has("CurrentState") || (card.has("Tapped") && card.get("Tapped").getAsBoolean())) {
            return false;
        }
        final JsonObject state = objects.get(card.getAsJsonObject("CurrentState").get("ref").getAsInt());
        return state != null && state.has("Name") && "Evolving Wilds".equals(state.get("Name").getAsString());
    }
}
