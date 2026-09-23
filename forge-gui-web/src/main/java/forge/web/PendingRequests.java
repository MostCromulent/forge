package forge.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Blocking prompts waiting on the browser. A request survives a browser reload because only its thread waits on it. */
public final class PendingRequests {
    private record Pending(JsonObject request, JsonElement defaultAnswer, Predicate<JsonElement> valid, CompletableFuture<JsonElement> answer) {}

    private final Consumer<JsonObject> send;
    private final Map<Integer, Pending> open = new LinkedHashMap<>();
    private int nextId = 1;

    public PendingRequests(final Consumer<JsonObject> send) {
        this.send = send;
    }

    /** Sends one of the requests in {@link ToBrowser} and waits for its answer, or for its default when the
     *  game gives up waiting. */
    public JsonElement await(final Record payload, final Predicate<JsonElement> valid) {
        final int id;
        final Pending pending;
        synchronized (this) {
            id = nextId++;
            final JsonObject request = Wire.encodeRequest(payload);
            request.addProperty("id", id);
            final JsonElement defaultAnswer = ToBrowser.defaultOf(request);
            pending = new Pending(request, defaultAnswer, valid, new CompletableFuture<>());
            open.put(id, pending);
        }
        send.accept(pending.request());
        try {
            return pending.answer().get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return pending.defaultAnswer();
        } catch (final ExecutionException e) {
            return pending.defaultAnswer();
        } finally {
            synchronized (this) {
                open.remove(id);
            }
        }
    }

    public void complete(final int id, final JsonElement value) {
        final Pending pending;
        synchronized (this) {
            pending = open.get(id);
        }
        if (pending == null) {
            Logger.warn("Reply for unknown request {}", id);
            return;
        }
        if (value == null || !pending.valid().test(value)) {
            Logger.warn("Invalid reply for request {}: {}", id, value);
            return;
        }
        pending.answer().complete(value);
    }

    public void cancelAll() {
        final List<Pending> all;
        synchronized (this) {
            all = new ArrayList<>(open.values());
        }
        for (final Pending p : all) {
            p.answer().complete(p.defaultAnswer());
        }
    }

    public void replay(final Consumer<JsonObject> to) {
        final List<JsonObject> requests = new ArrayList<>();
        synchronized (this) {
            for (final Pending p : open.values()) {
                requests.add(p.request().deepCopy());
            }
        }
        requests.forEach(to);
    }
}
