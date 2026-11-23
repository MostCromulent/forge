package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.path.RoguePathNode;
import forge.localinstance.properties.ForgePreferences;
import forge.web.campaign.rogue.RogueGame;
import org.testng.annotations.BeforeMethod;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Opens the fixture's run in the server's Rogue game for a browser, and answers the flow's questions as a browser would. */
abstract class RogueSessionTest extends WebHubTest {
    final Set<Integer> handled = ConcurrentHashMap.newKeySet();
    RogueRun run;

    @Override
    void afterDisconnecting() {
        RogueFixture.clean();
    }

    @BeforeMethod
    public void clean() {
        RogueFixture.clean();
        ForgePreferences.DEV_MODE = false;
    }

    static RogueGame game() {
        return RogueGame.shared();
    }

    TestBrowser seat(final String id) {
        final TestBrowser browser = connect(id);
        sessions.onMessage(browser, TestBrowser.action("claimHost"));
        sessions.onMessage(browser, message("setName", "name", id));
        awaitTrue(() -> game().mayLeave(null), "the game's job never finished");
        return browser;
    }

    /** Opens the fixture's run at a node of the given type, with 3 removal credits and the gold given, with every question on the way answered. */
    TestBrowser atRun(final Class<? extends RoguePathNode> at, final int gold) throws InterruptedException {
        run = RogueFixture.run(at);
        run.setRemovalCredits(3);
        run.setCurrentGold(gold);
        return openRun();
    }

    /** Saves run and opens it, with every question on the way answered. */
    TestBrowser openRun() throws InterruptedException {
        RogueIO.saveRun(run);
        final TestBrowser host = seat("host");
        send(host, message("campaignOpen", "mode", RogueGame.MODE, "resume", false));
        host.awaitMatching("hello", h -> RogueGame.MODE.equals(str(h, "campaign")), "Rogue did not open");
        settle(host);
        send(host, message("campaignLoad", "name", RogueGame.RUN));
        host.awaitMatching("hello", h -> RogueGame.RUN.equals(str(h, "campaignSave")), "the run did not open");
        settle(host);
        return host;
    }

    static long snapshot(final TestBrowser browser) {
        return browser.last("rogueHome").get("snapshot").getAsLong();
    }

    JsonObject enter(final TestBrowser host, final int node) throws InterruptedException {
        sessions.onMessage(host, message("rogueEnter", "snapshot", snapshot(host), "node", node));
        return host.awaitMatching("rogueQuestion", q -> !handled.contains(q.get("id").getAsInt()), "the node asked nothing");
    }

    static JsonObject answer(final int id, final JsonElement value) {
        final JsonObject m = message("rogueAnswer", "id", id);
        m.add("value", value);
        return m;
    }

    /** An answer each shape accepts: the first enabled choice, the fewest cards, leaving a shop. A Tell is answered with null. */
    static JsonElement acceptable(final JsonObject question) {
        if (question.has("choice")) {
            final JsonArray options = question.getAsJsonObject("choice").getAsJsonArray("options");
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).getAsJsonObject().get("enabled").getAsBoolean()) {
                    return new JsonPrimitive(i);
                }
            }
            return options.isEmpty() ? JsonNull.INSTANCE : new JsonPrimitive("skip");
        }
        if (question.has("cards")) {
            final JsonArray picked = new JsonArray();
            for (int i = 0; i < question.getAsJsonObject("cards").get("min").getAsInt(); i++) {
                picked.add(i);
            }
            return picked;
        }
        if (question.has("shop")) {
            return leaveTheShop();
        }
        return JsonNull.INSTANCE;
    }

    static JsonObject leaveTheShop() {
        final JsonObject leave = new JsonObject();
        leave.add("selected", new JsonArray());
        leave.addProperty("reroll", false);
        leave.addProperty("tab", 0);
        return leave;
    }

    /** Answers every question until the flow is idle. A step that does nothing is accepted only then. */
    void settle(final TestBrowser browser) {
        final boolean[] idle = {false};
        awaitTrue(() -> {
            final JsonObject question = browser.last("rogueQuestion");
            if (question != null && handled.add(question.get("id").getAsInt())) {
                sessions.onMessage(browser, answer(question.get("id").getAsInt(), acceptable(question)));
                return false;
            }
            final JsonObject home = browser.last("rogueHome");
            idle[0] |= home != null && game().submit(home.get("snapshot").getAsLong(), -1, () -> { });
            return idle[0];
        }, "the flow never became idle");
        awaitTrue(() -> game().mayLeave(null), "the last step never finished");
    }
}
