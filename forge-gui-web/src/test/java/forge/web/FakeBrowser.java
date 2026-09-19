package forge.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Records what the web client sends. With autoPlay it passes priority; with answerRequests it answers every request
 * with its default. Without either it holds whatever the game asks.
 */
final class FakeBrowser implements BrowserChannel {
    final List<JsonObject> received = new CopyOnWriteArrayList<>();
    final BrowserModel model = new BrowserModel();
    final CountDownLatch gameOver = new CountDownLatch(1);
    private final WebGuiGame gui;
    private final boolean autoPlay;
    private final boolean answerRequests;
    private final ExecutorService actions = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "FakeBrowser");
        t.setDaemon(true);
        return t;
    });

    FakeBrowser(final WebGuiGame gui, final boolean autoPlay) {
        this(gui, autoPlay, autoPlay);
    }

    FakeBrowser(final WebGuiGame gui, final boolean autoPlay, final boolean answerRequests) {
        this.gui = gui;
        this.autoPlay = autoPlay;
        this.answerRequests = answerRequests;
    }

    @Override
    public void send(final JsonObject message) {
        received.add(message);
        switch (message.get("t").getAsString()) {
            case "state" -> model.applyStateMessage(message);
            case "gameOver" -> gameOver.countDown();
            case "prompt" -> {
                if (autoPlay) {
                    autoPlay(message);
                }
            }
            case "request" -> {
                if (answerRequests) {
                    later(reply(message.get("id").getAsInt(), message.get("default")));
                }
            }
            default -> { }
        }
    }

    // Passes priority; when an input needs a selection first (e.g. discard to hand size), picks a card not yet selected
    private void autoPlay(final JsonObject prompt) {
        if (prompt.getAsJsonObject("ok").get("enabled").getAsBoolean()) {
            later(action("ok"));
            return;
        }
        final Set<Integer> chosen = new HashSet<>();
        prompt.getAsJsonArray("highlighted").forEach(k -> chosen.add(k.getAsInt()));
        for (final JsonElement ref : prompt.getAsJsonArray("selectable")) {
            final int key = ref.getAsJsonObject().get("ref").getAsInt();
            if (!chosen.contains(key)) {
                final JsonObject select = action("selectCard");
                select.addProperty("key", key);
                later(select);
                return;
            }
        }
    }

    List<JsonObject> all(final String type) {
        return received.stream().filter(m -> type.equals(m.get("t").getAsString())).collect(Collectors.toList());
    }

    JsonObject last(final String type) {
        final List<JsonObject> matches = all(type);
        return matches.isEmpty() ? null : matches.get(matches.size() - 1);
    }

    JsonObject awaitLast(final String type, final long timeoutMillis) throws InterruptedException {
        final long end = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < end) {
            final JsonObject m = last(type);
            if (m != null) {
                return m;
            }
            Thread.sleep(10);
        }
        return null;
    }

    static JsonObject action(final String type) {
        return JsonCodec.message(type);
    }

    static JsonObject reply(final int id, final JsonElement value) {
        final JsonObject r = JsonCodec.message("reply");
        r.addProperty("id", id);
        r.add("value", value);
        return r;
    }

    private void later(final JsonObject msg) {
        actions.execute(() -> {
            try {
                Thread.sleep(20);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gui.onBrowserMessage(msg);
        });
    }
}
