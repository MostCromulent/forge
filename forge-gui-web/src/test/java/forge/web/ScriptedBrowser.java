package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plays the web seat to a script: waits at its first own main phase until released, then uses the named cards in
 * order (clicking a card in hand plays it, clicking one on the battlefield activates it) and passes priority after.
 * Every request is held open before it is answered, and the browser reloads once, on the third request.
 */
final class ScriptedBrowser implements BrowserChannel {
    final BrowserModel model = new BrowserModel();
    final CountDownLatch atOwnMain = new CountDownLatch(1);
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
    private final Deque<String> cardsToUse = new ConcurrentLinkedDeque<>();
    private final Set<Integer> answered = ConcurrentHashMap.newKeySet();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong promptVersion = new AtomicLong();
    private volatile boolean released;
    private volatile JsonObject lastPrompt;
    private volatile int root = -1;
    private volatile JsonArray localPlayers = new JsonArray();

    ScriptedBrowser(final WebGuiGame gui, final long holdMillis) {
        this.gui = gui;
        this.holdMillis = holdMillis;
    }

    void release(final String... cardNames) {
        for (final String name : cardNames) {
            cardsToUse.add(name);
        }
        released = true;
        actOnLatestPrompt();
    }

    @Override
    public void send(final JsonObject m) {
        switch (m.get("t").getAsString()) {
            case "state" -> {
                model.applyStateMessage(m);
                root = m.get("root").getAsInt();
                localPlayers = m.getAsJsonArray("localPlayers");
            }
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
            // A declined request leaves the same input open without a new prompt
            actOnLatestPrompt();
        }, holdMillis, TimeUnit.MILLISECONDS);
    }

    // An optional single choice defaults to "none"; taking the first option is what plays or activates the clicked card
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
        if (!released && inOwnFirstMain()) {
            atOwnMain.countDown();
            return;
        }
        // A rejected click sends no new prompt; act again if nothing arrives
        final long seen = promptVersion.get();
        actions.schedule(() -> {
            if (promptVersion.get() == seen) {
                actOnLatestPrompt();
            }
        }, 1500, TimeUnit.MILLISECONDS);
        if (!prompt.getAsJsonObject("ok").get("enabled").getAsBoolean()) {
            selectUnchosen(prompt);
            return;
        }
        // Cards are only used from the priority prompt with an empty stack; cost prompts such as "Sacrifice X?" are answered with OK
        final boolean priority = prompt.get("message").getAsString().startsWith("Priority");
        final Integer card = released && priority && inOwnFirstMain() && stackEmpty() ? nextScriptedCard() : null;
        if (card != null) {
            select(card);
        } else if (cardsToUse.isEmpty() || !priority || !inOwnFirstMain() || !stackEmpty()) {
            // Passing with something on the stack lets it resolve
            gui.onBrowserMessage(FakeBrowser.action("ok"));
        }
    }

    // Clicking a card that is already selected deselects it, so pick one that is not
    private void selectUnchosen(final JsonObject prompt) {
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
    }

    private void select(final int key) {
        final JsonObject msg = FakeBrowser.action("selectCard");
        msg.addProperty("key", key);
        gui.onBrowserMessage(msg);
    }

    private boolean inOwnFirstMain() {
        final JsonObject game = model.objectsCopy().get(root);
        return game != null && !localPlayers.isEmpty() && game.has("PlayerTurn") && game.has("Phase")
                && game.getAsJsonObject("PlayerTurn").get("ref").getAsInt() == localPlayers.get(0).getAsInt()
                && "MAIN1".equals(game.get("Phase").getAsString());
    }

    private boolean stackEmpty() {
        final JsonObject game = model.objectsCopy().get(root);
        return game != null && (!game.has("Stack") || game.getAsJsonArray("Stack").isEmpty());
    }

    // Entries are "Zone:Card Name". An entry is done once its card has left that zone (played, or sacrificed);
    // until then it is clicked again, because a click can be rejected while a trigger waits to go on the stack
    private Integer nextScriptedCard() {
        final Map<Integer, JsonObject> objects = model.objectsCopy();
        final JsonObject player = objects.get(localPlayers.get(0).getAsInt());
        while (!cardsToUse.isEmpty()) {
            final String[] entry = cardsToUse.peek().split(":", 2);
            final Integer key = findIn(objects, player, entry[0], entry[1]);
            if (key != null) {
                return key;
            }
            cardsToUse.poll();
        }
        return null;
    }

    private static Integer findIn(final Map<Integer, JsonObject> objects, final JsonObject player, final String zone, final String name) {
        if (player == null || !player.has(zone)) {
            return null;
        }
        for (final JsonElement ref : player.getAsJsonArray(zone)) {
            if (ref.isJsonObject()) {
                final int key = ref.getAsJsonObject().get("ref").getAsInt();
                if (name.equals(nameOf(objects, objects.get(key)))) {
                    return key;
                }
            }
        }
        return null;
    }

    private static String nameOf(final Map<Integer, JsonObject> objects, final JsonObject card) {
        if (card == null || !card.has("CurrentState")) {
            return null;
        }
        final JsonObject state = objects.get(card.getAsJsonObject("CurrentState").get("ref").getAsInt());
        return state == null || !state.has("Name") ? null : state.get("Name").getAsString();
    }
}
