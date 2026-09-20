package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.gamemodes.net.DeltaPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Java copy of the browser's object table; model.js implements the same apply-and-prune rules. */
public final class BrowserModel {
    private final Map<Integer, JsonObject> objects = new LinkedHashMap<>();
    private int root = -1;
    private JsonArray visible = new JsonArray();
    private JsonArray localPlayers = new JsonArray();

    public synchronized void reset(final int rootKey) {
        objects.clear();
        root = rootKey;
        visible = new JsonArray();
    }

    public synchronized void apply(final Map<Integer, JsonObject> newObjects, final Map<Integer, JsonObject> deltas) {
        for (final Map.Entry<Integer, JsonObject> e : newObjects.entrySet()) {
            objects.put(e.getKey(), e.getValue().deepCopy());
        }
        for (final Map.Entry<Integer, JsonObject> e : deltas.entrySet()) {
            final JsonObject target = objects.get(e.getKey());
            if (target == null) {
                continue;
            }
            for (final Map.Entry<String, JsonElement> field : e.getValue().entrySet()) {
                if (field.getValue().isJsonNull()) {
                    target.remove(field.getKey());
                } else {
                    target.add(field.getKey(), field.getValue().deepCopy());
                }
            }
        }
        prune();
    }

    public synchronized void applyStateMessage(final JsonObject msg) {
        if (msg.get("full").getAsBoolean()) {
            reset(msg.get("root").getAsInt());
        }
        apply(keyed(msg.getAsJsonObject("newObjects")), keyed(msg.getAsJsonObject("deltas")));
        visible = msg.getAsJsonArray("visible").deepCopy();
        localPlayers = msg.getAsJsonArray("localPlayers").deepCopy();
    }

    private static Map<Integer, JsonObject> keyed(final JsonObject byKey) {
        final Map<Integer, JsonObject> out = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> e : byKey.entrySet()) {
            out.put(Integer.parseInt(e.getKey()), e.getValue().getAsJsonObject());
        }
        return out;
    }

    public synchronized void setVisible(final Collection<Integer> keys) {
        visible = toArray(keys);
    }

    public synchronized void setLocalPlayers(final Collection<Integer> keys) {
        localPlayers = toArray(keys);
    }

    private static JsonArray toArray(final Collection<Integer> keys) {
        final JsonArray a = new JsonArray();
        keys.forEach(a::add);
        return a;
    }

    public synchronized List<Integer> keysOfType(final int type) {
        final List<Integer> keys = new ArrayList<>();
        for (final Integer key : objects.keySet()) {
            if (DeltaPacket.getTypeFromDeltaKey(key) == type) {
                keys.add(key);
            }
        }
        return keys;
    }

    public synchronized JsonObject stateMessage(final boolean full, final long seq, final Map<Integer, JsonObject> newObjects, final Map<Integer, JsonObject> deltas) {
        final JsonObject m = JsonCodec.message("state");
        m.addProperty("full", full);
        m.addProperty("seq", seq);
        m.addProperty("root", root);
        m.add("newObjects", byKey(newObjects));
        m.add("deltas", byKey(deltas));
        m.add("visible", visible.deepCopy());
        m.add("localPlayers", localPlayers.deepCopy());
        return m;
    }

    public synchronized JsonObject fullState() {
        return stateMessage(true, -1, objects, Map.of());
    }

    public synchronized Map<Integer, JsonObject> objectsCopy() {
        final Map<Integer, JsonObject> copy = new LinkedHashMap<>();
        objects.forEach((k, v) -> copy.put(k, v.deepCopy()));
        return copy;
    }

    private static JsonObject byKey(final Map<Integer, JsonObject> objects) {
        final JsonObject o = new JsonObject();
        objects.forEach((k, v) -> o.add(String.valueOf(k), v.deepCopy()));
        return o;
    }

    // Packets carry no removal signal, so anything the game no longer reaches is dropped here
    private void prune() {
        if (!objects.containsKey(root)) {
            return;
        }
        final Set<Integer> reachable = new HashSet<>();
        final Deque<Integer> todo = new ArrayDeque<>();
        todo.push(root);
        while (!todo.isEmpty()) {
            final int key = todo.pop();
            if (!reachable.add(key)) {
                continue;
            }
            final JsonObject o = objects.get(key);
            if (o != null) {
                collectRefs(o, todo);
            }
        }
        objects.keySet().retainAll(reachable);
    }

    private static void collectRefs(final JsonElement e, final Deque<Integer> out) {
        if (e.isJsonArray()) {
            for (final JsonElement item : e.getAsJsonArray()) {
                collectRefs(item, out);
            }
        } else if (e.isJsonObject()) {
            final JsonObject o = e.getAsJsonObject();
            if (o.size() == 1 && o.has("ref")) {
                out.push(o.get("ref").getAsInt());
                return;
            }
            for (final Map.Entry<String, JsonElement> field : o.entrySet()) {
                collectRefs(field.getValue(), out);
            }
        }
    }
}
