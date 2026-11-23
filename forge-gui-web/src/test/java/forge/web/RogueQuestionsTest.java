package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.flow.RogueStart;
import forge.gamemodes.rogue.npc.NPC;
import forge.gamemodes.rogue.path.NodeBazaar;
import forge.gamemodes.rogue.path.NodeEvent;
import forge.gamemodes.rogue.path.RoguePathNode;
import forge.localinstance.properties.ForgePreferences;
import forge.web.campaign.rogue.RogueGame;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tests Rogue's questions as a browser sees them: they wait for an answer, survive reloads and leaving, and refuse a wrong answer. */
@Test(singleThreaded = true)
public class RogueQuestionsTest extends WebHubTest {
    private static final int EVENT = 8;
    private static final int BAZAAR = 7;
    private final Set<Integer> handled = ConcurrentHashMap.newKeySet();
    private RogueRun run;

    @Override
    void afterDisconnecting() {
        RogueFixture.clean();
    }

    @BeforeMethod
    public void clean() {
        RogueFixture.clean();
        ForgePreferences.DEV_MODE = false;
    }

    private static RogueGame game() {
        return RogueGame.shared();
    }

    private TestBrowser seat(final String id) {
        final TestBrowser browser = connect(id);
        sessions.onMessage(browser, TestBrowser.action("claimHost"));
        sessions.onMessage(browser, message("setName", "name", id));
        awaitTrue(() -> game().mayLeave(null), "the game's job never finished");
        return browser;
    }

    /** Opens the fixture's run at a node of the given type, with the first story of a new profile answered. */
    private TestBrowser atRun(final Class<? extends RoguePathNode> at) throws InterruptedException {
        run = RogueFixture.run(at);
        final TestBrowser host = seat("host");
        send(host, message("campaignOpen", "mode", RogueGame.MODE, "resume", false));
        host.awaitMatching("hello", h -> RogueGame.MODE.equals(str(h, "campaign")), "Rogue did not open");
        settle(host);
        send(host, message("campaignLoad", "name", RogueGame.RUN));
        host.awaitMatching("hello", h -> RogueGame.RUN.equals(str(h, "campaignSave")), "the run did not open");
        settle(host);
        return host;
    }

    private static long snapshot(final TestBrowser browser) {
        return browser.last("rogueHome").get("snapshot").getAsLong();
    }

    private JsonObject enter(final TestBrowser host, final int node) throws InterruptedException {
        sessions.onMessage(host, message("rogueEnter", "snapshot", snapshot(host), "node", node));
        return host.awaitMatching("rogueQuestion", q -> !handled.contains(q.get("id").getAsInt()), "the node asked nothing");
    }

    private static JsonObject answer(final int id, final JsonElement value) {
        final JsonObject m = message("rogueAnswer", "id", id);
        m.add("value", value);
        return m;
    }

    /** An answer each shape accepts: the first enabled choice, the fewest cards, leaving a shop. A Tell is answered with null. */
    private static JsonElement acceptable(final JsonObject question) {
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

    private static JsonObject leaveTheShop() {
        final JsonObject leave = new JsonObject();
        leave.add("selected", new JsonArray());
        leave.addProperty("reroll", false);
        leave.addProperty("tab", 0);
        return leave;
    }

    /** Answers every question until the flow is idle. A step that does nothing is accepted only then. */
    private void settle(final TestBrowser browser) {
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

    /** The runs page asks its story again before it can be left. */
    private void toMenu(final TestBrowser browser) throws InterruptedException {
        for (int i = 0; i < 3 && str(browser.hello, "campaign") != null; i++) {
            if (str(browser.hello, "campaignSave") == null) {
                settle(browser);
            }
            send(browser, TestBrowser.action("campaignLeave"));
            browser.awaitMatching("hello", h -> true, "Back did nothing");
        }
        Assert.assertNull(str(browser.hello, "campaign"), "Back did not reach the menu");
    }

    private JsonObject reopen(final TestBrowser browser, final int question) throws InterruptedException {
        send(browser, message("campaignOpen", "mode", RogueGame.MODE, "resume", true));
        browser.awaitMatching("hello", h -> RogueGame.RUN.equals(str(h, "campaignSave")), "Rogue did not go straight to the run");
        return browser.awaitMatching("rogueQuestion", q -> q.get("id").getAsInt() == question, "the question did not come back");
    }

    private void releaseHostSeat() throws ReflectiveOperationException {
        final Method release = WebHub.class.getDeclaredMethod("releaseHostIfAbandoned");
        release.setAccessible(true);
        release.invoke(sessions);
    }

    // Fails if a reload loses the open question or sends it with other choices
    @Test(timeOut = 120_000)
    public void aReloadBringsBackTheSameQuestion() throws Exception {
        final TestBrowser host = atRun(NodeEvent.class);
        final JsonObject asked = enter(host, EVENT);
        final TestBrowser reloaded = connect("host");
        final JsonObject again = reloaded.awaitMatching("rogueQuestion", q -> q.get("id").equals(asked.get("id")), "the reload lost the question");
        Assert.assertEquals(again.get("choice"), asked.get("choice"));
        reloaded.awaitMatching("rogueMap", m -> true, "the reload sent no pages under the question");
        settle(reloaded);
        Assert.assertTrue(RogueIO.loadRun(RogueIO.getRogueFile(run)).getPath().getNode(EVENT).isCompleted(), "the answer after the reload was not taken");
    }

    // Fails if Back during a question goes to the runs page, or opening Rogue again does not return to the question
    @Test(timeOut = 120_000)
    public void leavingAndReturningBringsItBack() throws Exception {
        final TestBrowser host = atRun(NodeEvent.class);
        final int asked = enter(host, EVENT).get("id").getAsInt();
        send(host, TestBrowser.action("campaignLeave"));
        host.awaitMatching("hello", h -> str(h, "campaign") == null, "Back during a question did not go to the menu");
        reopen(host, asked);
        settle(host);
    }

    // Fails if leaving saves the run part-way through the question's step
    @Test(timeOut = 120_000)
    public void leavingDuringAQuestionSavesNothing() throws Exception {
        final TestBrowser host = atRun(NodeEvent.class);
        final int asked = enter(host, EVENT).get("id").getAsInt();
        final Path file = RogueIO.getRogueFile(run).toPath();
        final byte[] before = Files.readAllBytes(file);
        send(host, TestBrowser.action("campaignLeave"));
        host.awaitMatching("hello", h -> str(h, "campaign") == null, "Back during a question did not go to the menu");
        Assert.assertEquals(Files.readAllBytes(file), before);
        reopen(host, asked);
        settle(host);
    }

    // Fails if a browser that takes the host's seat cannot see or answer the question the last host left open
    @Test(timeOut = 120_000)
    public void anotherHostGetsTheQuestion() throws Exception {
        final TestBrowser host = atRun(NodeEvent.class);
        final int asked = enter(host, EVENT).get("id").getAsInt();
        send(host, TestBrowser.action("campaignLeave"));
        host.awaitMatching("hello", h -> str(h, "campaign") == null, "Back during a question did not go to the menu");
        sessions.disconnected(host);
        releaseHostSeat();
        final TestBrowser other = seat("other");
        try {
            Assert.assertTrue(other.hello.get("host").getAsBoolean(), "the second browser did not get the seat");
            reopen(other, asked);
            settle(other);
            Assert.assertTrue(RogueIO.loadRun(RogueIO.getRogueFile(run)).getPath().getNode(EVENT).isCompleted(), "the new host's answer was not taken");
        } finally {
            toMenu(other);
            sessions.disconnected(other);
            releaseHostSeat();
        }
    }

    // Fails if an answer the offer does not allow is taken, or a refused answer closes the question
    @Test(timeOut = 120_000)
    public void aWrongAnswerLeavesTheQuestionOpen() throws Exception {
        final TestBrowser host = atRun(NodeBazaar.class);
        sessions.onMessage(host, message("rogueEnter", "snapshot", snapshot(host), "node", BAZAAR));
        final JsonObject asked = host.awaitMatching("rogueQuestion", q -> q.has("shop"), "the bazaar opened no shop");
        final int id = asked.get("id").getAsInt();
        // The pages are sent before the question they are asked over
        final long now = snapshot(host);
        final JsonObject shop = asked.getAsJsonObject("shop");
        final JsonArray everything = new JsonArray();
        int total = 0;
        for (final JsonElement item : shop.getAsJsonArray("items")) {
            everything.add(item.getAsJsonObject().get("id"));
            total += item.getAsJsonObject().get("price").getAsInt();
        }
        Assert.assertTrue(total > shop.get("gold").getAsInt(), "the shop's items all fit in the gold, so buying them all is no wrong answer");

        final JsonObject tooDear = leaveTheShop();
        tooDear.add("selected", everything);
        final JsonObject noSuchItem = leaveTheShop();
        noSuchItem.add("selected", new JsonArray());
        noSuchItem.getAsJsonArray("selected").add(everything.size());
        for (final JsonObject wrong : new JsonObject[] {answer(id, tooDear), answer(id, noSuchItem), answer(id + 1000, leaveTheShop())}) {
            send(host, wrong);
            host.awaitMatching("rogueQuestion", q -> q.get("id").getAsInt() == id, "a refused answer did not send the question again");
            Assert.assertFalse(game().submit(now, -1, () -> { }), "a refused answer closed the question");
        }
        send(host, answer(id, leaveTheShop()));
        handled.add(id);
        settle(host);
        final RogueRun saved = RogueIO.loadRun(RogueIO.getRogueFile(run));
        Assert.assertTrue(saved.getPath().getNode(BAZAAR).isCompleted(), "the good answer was not taken");
        // The fixture's run has 8 gold
        Assert.assertEquals(saved.getCurrentGold(), 8);
    }

    // Fails if another campaign cannot be used while a Rogue question waits
    @Test(timeOut = 120_000)
    public void conquestWorksWhileAQuestionIsOpen() throws Exception {
        final TestBrowser host = atRun(NodeEvent.class);
        final int asked = enter(host, EVENT).get("id").getAsInt();
        send(host, TestBrowser.action("campaignLeave"));
        host.awaitMatching("hello", h -> str(h, "campaign") == null, "Back during a question did not go to the menu");
        send(host, message("campaignOpen", "mode", "conquest", "resume", false));
        host.awaitMatching("hello", h -> "conquest".equals(str(h, "campaign")), "Conquest did not open");
        host.awaitMatching("conquestSaves", s -> true, "Conquest's saves were not listed");
        send(host, TestBrowser.action("campaignLeave"));
        host.awaitMatching("hello", h -> str(h, "campaign") == null, "Conquest was not left");
        reopen(host, asked);
        settle(host);
    }

    // Fails if the time spent in a new run's start-of-run question is not counted, as desktop counts it, or a disconnect does not stop it
    @Test(timeOut = 120_000)
    public void timeInAStartOfRunQuestionCountsUntilTheBrowserGoes() throws Exception {
        final TestBrowser host = seat("host");
        send(host, message("campaignOpen", "mode", RogueGame.MODE, "resume", false));
        host.awaitMatching("hello", h -> RogueGame.MODE.equals(str(h, "campaign")), "Rogue did not open");
        settle(host);
        // Tyvar's third level offers a boon at the start of a run
        RogueMetaProgress.getInstance().setNPCLevel(NPC.TYVAR.id, 3);
        final JsonObject begin = message("rogueBegin", "snapshot", snapshot(host), "deck", RogueStart.roster(false).get(0).getName(),
                "custom", false, "descension", 0);
        begin.add("modifications", new JsonArray());
        sessions.onMessage(host, begin);
        final JsonObject boon = host.awaitMatching("rogueQuestion", q -> q.has("choice") && !handled.contains(q.get("id").getAsInt()),
                "the run asked nothing as it started");
        Thread.sleep(300);
        sessions.disconnected(host);
        Thread.sleep(600);
        final TestBrowser back = connect("host");
        back.awaitMatching("rogueQuestion", q -> q.get("id").equals(boon.get("id")), "the question did not come back");
        settle(back);
        final long elapsed = RogueIO.loadMostRecentContinuableRun().orElseThrow(AssertionError::new).getRunTimer().getElapsedMillis();
        Assert.assertTrue(elapsed >= 250 && elapsed < 700, "the run counted " + elapsed + " ms for 300 ms with the browser and 600 ms without");
    }
}
