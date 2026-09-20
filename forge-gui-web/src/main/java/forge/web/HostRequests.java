package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.tinylog.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Questions the host asks outside a match, such as which net deck category to download. The calling
 * thread waits for the browser's answer, the way the desktop client waits for its dialog.
 */
final class HostRequests {
    /** Long enough for someone to read the list and decide, short enough that a closed browser gives up. */
    private static final long ANSWER_TIMEOUT_MINUTES = 5;

    private final Map<Integer, CompletableFuture<JsonElement>> open = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger();
    private volatile Consumer<JsonObject> toBrowser = message -> { };

    void setSink(final Consumer<JsonObject> sink) {
        toBrowser = sink;
    }

    /** Sends the open questions again, so a browser that reloaded can still answer them. */
    void replay(final Consumer<JsonObject> sink) {
        for (final Map.Entry<Integer, CompletableFuture<JsonElement>> e : open.entrySet()) {
            final JsonObject again = pending.get(e.getKey());
            if (again != null) {
                sink.accept(again);
            }
        }
    }

    private final Map<Integer, JsonObject> pending = new ConcurrentHashMap<>();

    /** Asks the browser to pick from a list and waits. Returns null when nobody answers. */
    JsonElement ask(final String kind, final String message, final JsonArray options, final int min, final int max) {
        final int id = nextId.incrementAndGet();
        final JsonObject request = JsonCodec.message("hostChoice");
        request.addProperty("id", id);
        request.addProperty("kind", kind);
        request.addProperty("message", message);
        request.addProperty("min", min);
        request.addProperty("max", max);
        request.add("options", options);
        final CompletableFuture<JsonElement> answer = new CompletableFuture<>();
        open.put(id, answer);
        pending.put(id, request);
        toBrowser.accept(request);
        try {
            return answer.get(ANSWER_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final ExecutionException | TimeoutException e) {
            Logger.warn("No answer to the host's question: {}", message);
            return null;
        } finally {
            open.remove(id);
            pending.remove(id);
        }
    }

    /** The browser answered one of them. */
    void answer(final int id, final JsonElement value) {
        final CompletableFuture<JsonElement> waiting = open.get(id);
        if (waiting != null) {
            waiting.complete(value);
        }
    }

    /** Nothing is going to answer: release anything still waiting so no thread is stuck. */
    void abandon() {
        for (final CompletableFuture<JsonElement> waiting : open.values()) {
            waiting.complete(null);
        }
        open.clear();
        pending.clear();
    }
}
