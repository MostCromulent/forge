package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.GameLog;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.GameLogVerbosity;
import forge.game.card.CardView;
import forge.gamemodes.net.DeltaPacket;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** The game log as the browser sees it: the entries this seat may read, sent as they arrive and replayed on connect. */
final class WebGameLog {
    private final Predicate<CardView> mayView;
    private final List<JsonObject> entries = new ArrayList<>();
    private GameLog logged;
    private int loggedCount;

    WebGameLog(final Predicate<CardView> mayView) {
        this.mayView = mayView;
    }

    /** The entries added since the last call, or every entry when the match has moved to a new game. */
    JsonObject added(final GameLog log) {
        final List<GameLogEntry> all = log.getAllEntries();
        final boolean newGame = log != logged;
        if (newGame) {
            logged = log;
            loggedCount = 0;
        }
        final Set<GameLogEntryType> shown = shownTypes();
        final List<JsonObject> fresh = new ArrayList<>();
        for (final GameLogEntry entry : all.subList(loggedCount, all.size())) {
            if (shown.contains(entry.type())) {
                fresh.add(toJson(entry));
            }
        }
        loggedCount = all.size();
        synchronized (entries) {
            if (newGame) {
                entries.clear();
            }
            entries.addAll(fresh);
        }
        return newGame || !fresh.isEmpty() ? message(fresh, newGame) : null;
    }

    /** Everything this seat has seen so far, for a browser that has just connected. */
    JsonObject all() {
        synchronized (entries) {
            return message(entries, true);
        }
    }

    private JsonObject toJson(final GameLogEntry entry) {
        final JsonObject e = new JsonObject();
        e.addProperty("type", entry.type().name());
        e.addProperty("message", entry.message());
        final CardView card = entry.sourceCard();
        if (card != null && card.getCurrentState() != null && mayView.test(card)) {
            e.addProperty("card", DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, card.getId()));
            e.addProperty("imageKey", card.getCurrentState().getImageKey());
        }
        return e;
    }

    private static Set<GameLogEntryType> shownTypes() {
        final ForgePreferences prefs = FModel.getPreferences();
        final GameLogVerbosity verbosity = GameLogVerbosity.fromString(prefs.getPref(FPref.DEV_LOG_ENTRY_TYPE));
        return verbosity == GameLogVerbosity.CUSTOM ? prefs.getCustomLogTypes() : verbosity.getIncludedTypes();
    }

    private static JsonObject message(final List<JsonObject> list, final boolean full) {
        final JsonObject m = JsonCodec.message("log");
        m.addProperty("full", full);
        final JsonArray a = new JsonArray();
        list.forEach(a::add);
        m.add("entries", a);
        return m;
    }
}
