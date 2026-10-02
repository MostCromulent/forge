package forge.web;

import com.google.gson.JsonObject;
import org.testng.Assert;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** A browser that keeps everything the server sends it, and waits for what a test expects to arrive. */
final class Recorder implements BrowserChannel {
    private static final int WAIT_MILLIS = 60_000;
    private static final int POLL_MILLIS = 10;

    final List<JsonObject> got = new CopyOnWriteArrayList<>();
    /** Whether the server closed this browser's connection, as it does one past its limit. */
    volatile boolean closed;

    @Override
    public void send(final JsonObject message) {
        got.add(message);
    }

    @Override
    public void close() {
        closed = true;
    }

    /** The earliest message of a type that is wanted, or null if none arrived in time. */
    JsonObject awaitMatching(final String type, final Predicate<JsonObject> wanted) throws InterruptedException {
        for (int i = 0; i < WAIT_MILLIS / POLL_MILLIS; i++) {
            for (final JsonObject m : got) {
                if (type.equals(m.get("t").getAsString()) && wanted.test(m)) {
                    return m;
                }
            }
            Thread.sleep(POLL_MILLIS);
        }
        return null;
    }

    /** The newest message of a type, or null if none arrived in time. */
    JsonObject awaitNewest(final String type) throws InterruptedException {
        return awaitAfter(0, type, m -> true);
    }

    /** The newest message of a type that is wanted, or null if none arrived in time. */
    JsonObject awaitNewest(final String type, final Predicate<JsonObject> wanted) throws InterruptedException {
        return awaitAfter(0, type, wanted);
    }

    /** The newest wanted message among those that arrived at or after index from. */
    JsonObject awaitAfter(final int from, final String type, final Predicate<JsonObject> wanted) throws InterruptedException {
        for (int i = 0; i < WAIT_MILLIS / POLL_MILLIS; i++) {
            for (int j = got.size() - 1; j >= from; j--) {
                final JsonObject m = got.get(j);
                if (type.equals(m.get("t").getAsString()) && wanted.test(m)) {
                    return m;
                }
            }
            Thread.sleep(POLL_MILLIS);
        }
        return null;
    }

    /**
     * Waits until the table as it stands now is the one wanted, and answers it. The lobby is pushed on every change,
     * and one choice can travel as several changes (a deck, then being ready), so an earlier message can describe a
     * table that has already moved on; only the latest counts.
     */
    JsonObject awaitLobby(final Predicate<JsonObject> wanted) throws InterruptedException {
        for (int i = 0; i < WAIT_MILLIS / POLL_MILLIS; i++) {
            final JsonObject latest = latestTable();
            if (latest != null && wanted.test(latest)) {
                return latest;
            }
            Thread.sleep(POLL_MILLIS);
        }
        return null;
    }

    JsonObject awaitLobbyWithSeat() throws InterruptedException {
        return awaitLobby(l -> l.get("mySeat").getAsInt() >= 0);
    }

    JsonObject latestTable() {
        JsonObject latest = null;
        for (final JsonObject m : got) {
            if ("lobby".equals(m.get("t").getAsString()) && m.has("table")) {
                latest = m.getAsJsonObject("table");
            }
        }
        return latest;
    }

    // The same waits, failing with why when nothing wanted arrives in time

    JsonObject awaitMatching(final String type, final Predicate<JsonObject> wanted, final String why) throws InterruptedException {
        return found(awaitMatching(type, wanted), why);
    }

    JsonObject awaitNewest(final String type, final String why) throws InterruptedException {
        return found(awaitNewest(type), why);
    }

    JsonObject awaitNewest(final String type, final Predicate<JsonObject> wanted, final String why) throws InterruptedException {
        return found(awaitNewest(type, wanted), why);
    }

    JsonObject awaitLobby(final Predicate<JsonObject> wanted, final String why) throws InterruptedException {
        return found(awaitLobby(wanted), why);
    }

    private static JsonObject found(final JsonObject message, final String why) {
        Assert.assertNotNull(message, why);
        return message;
    }

    /** Drops what has been said so far, so a later wait cannot be satisfied by an earlier message. */
    void forget() {
        got.clear();
    }
}
