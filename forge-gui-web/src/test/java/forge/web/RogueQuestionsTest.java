package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.deck.CardPool;
import forge.gamemodes.rogue.RogueDeck;
import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueStats;
import forge.gamemodes.rogue.flow.RogueStart;
import forge.gamemodes.rogue.npc.NPC;
import forge.gamemodes.rogue.path.NodeBazaar;
import forge.gamemodes.rogue.path.NodeEvent;
import forge.gamemodes.rogue.path.NodeSanctum;
import forge.gamemodes.rogue.path.RoguePathNode;
import forge.item.PaperCard;
import forge.localinstance.achievements.Achievement;
import forge.localinstance.achievements.RogueCommanderAchievements;
import forge.localinstance.properties.ForgePreferences;
import forge.util.Localizer;
import forge.web.campaign.rogue.RogueGame;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Tests Rogue's questions as a browser sees them: they wait for an answer, survive reloads and leaving, and refuse a wrong answer.
 * Also tests the run's deck in the browser's deck editor: removal credits, undo, the run's basics, and the editor against the flow.
 */
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

    /** Opens the fixture's run at a node of the given type, with 3 removal credits and the gold given, with every question on the way answered. */
    private TestBrowser atRun(final Class<? extends RoguePathNode> at, final int gold) throws InterruptedException {
        run = RogueFixture.run(at);
        run.setRemovalCredits(3);
        run.setCurrentGold(gold);
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
        final TestBrowser host = atRun(NodeEvent.class, 8);
        final JsonObject asked = enter(host, EVENT);
        final TestBrowser reloaded = connect("host");
        final JsonObject again = reloaded.awaitMatching("rogueQuestion", q -> q.get("id").equals(asked.get("id")), "the reload lost the question");
        Assert.assertEquals(again.get("choice"), asked.get("choice"));
        reloaded.awaitMatching("rogueMap", m -> true, "the reload sent no pages under the question");
        settle(reloaded);
        Assert.assertTrue(RogueIO.loadRun(RogueIO.getRogueFile(run)).getPath().getNode(EVENT).isCompleted(), "the answer after the reload was not taken");
    }

    // Fails if leaving saves the run part-way through the question's step
    @Test(timeOut = 120_000)
    public void leavingDuringAQuestionSavesNothing() throws Exception {
        final TestBrowser host = atRun(NodeEvent.class, 8);
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
        final TestBrowser host = atRun(NodeEvent.class, 8);
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
        final TestBrowser host = atRun(NodeBazaar.class, 8);
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
        final TestBrowser host = atRun(NodeEvent.class, 8);
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

    // Fails if a run begun from the commanders the page was sent does not open on the run's pages once its start-of-run question is answered
    @Test(timeOut = 120_000)
    public void beginStartsTheRunOnThePage() throws Exception {
        final TestBrowser host = seat("host");
        send(host, message("campaignOpen", "mode", RogueGame.MODE, "resume", false));
        host.awaitMatching("hello", h -> RogueGame.MODE.equals(str(h, "campaign")), "Rogue did not open");
        settle(host);
        // Tyvar's third level offers a boon at the start of a run
        RogueMetaProgress.getInstance().setNPCLevel(NPC.TYVAR.id, 3);
        final JsonObject roster = host.awaitNewest("rogueRoster", "the commanders were not sent");
        final JsonObject begin = message("rogueBegin", "snapshot", snapshot(host),
                "deck", roster.getAsJsonArray("commanders").get(0).getAsJsonObject().get("deck").getAsString(), "custom", false, "descension", 0);
        begin.add("modifications", new JsonArray());
        sessions.onMessage(host, begin);
        final JsonObject boon = host.awaitMatching("rogueQuestion", q -> q.has("choice") && !handled.contains(q.get("id").getAsInt()),
                "the run asked nothing as it started");
        handled.add(boon.get("id").getAsInt());
        sessions.onMessage(host, answer(boon.get("id").getAsInt(), acceptable(boon)));
        host.awaitMatching("hello", h -> RogueGame.RUN.equals(str(h, "campaignSave")), "the begun run did not open");
        host.awaitMatching("rogueMap", m -> true, "the run's map was not sent");
    }

    // Fails if a custom run asked about is not ticked by the shared rule, or may start before Custom Run is unlocked
    @Test(timeOut = 120_000)
    public void aCustomRunIsTickedAndCheckedOnTheServer() throws Exception {
        final TestBrowser host = seat("host");
        send(host, message("campaignOpen", "mode", RogueGame.MODE, "resume", false));
        host.awaitMatching("hello", h -> RogueGame.MODE.equals(str(h, "campaign")), "Rogue did not open");
        settle(host);
        final RogueDeck deck = RogueStart.roster(false).get(0);
        final JsonObject rich = message("rogueChoose", "deck", deck.getName(), "descension", 0);
        rich.add("modifications", array("RICH"));
        sessions.onMessage(host, rich);
        Assert.assertFalse(host.awaitMatching("rogueChoice", c -> true, "the custom run was not answered").get("ready").getAsBoolean());

        RogueMetaProgress.getInstance().updateStat(RogueStats.RUNS_WON, 2, new RogueRun(deck));
        final JsonObject mania = message("rogueChoose", "deck", deck.getName(), "descension", 0, "toggle", "MANIA");
        mania.add("modifications", array("RICH"));
        sessions.onMessage(host, mania);
        final JsonObject answer = host.awaitMatching("rogueChoice", c -> c.getAsJsonArray("modifications").size() > 1, "the toggle was not answered");
        Assert.assertEquals(answer.getAsJsonArray("modifications"), array("MANIA", "RANDOM_START", "RANDOM_REWARDS", "RICH"));
        Assert.assertEquals(answer.getAsJsonArray("forced"), array("RANDOM_START", "RANDOM_REWARDS"));
        Assert.assertTrue(answer.get("ready").getAsBoolean());
    }

    private static JsonArray array(final String... values) {
        final JsonArray array = new JsonArray();
        for (final String value : values) {
            array.add(value);
        }
        return array;
    }

    private JsonObject openEditor(final TestBrowser host) throws InterruptedException {
        send(host, message("rogueEditDeck", "snapshot", snapshot(host)));
        final JsonObject state = editor(host);
        awaitTrue(() -> game().mayLeave(null), "opening the editor never finished");
        return state;
    }

    /** The editor's state sent in reply to the last message sent. */
    private static JsonObject editor(final TestBrowser host) throws InterruptedException {
        return host.awaitMatching("editor", e -> e.has("state") && !e.get("state").isJsonNull(), "the editor was not sent").getAsJsonObject("state");
    }

    private JsonObject edit(final TestBrowser host, final JsonObject command) throws InterruptedException {
        send(host, command);
        final JsonObject state = editor(host);
        awaitTrue(() -> game().mayLeave(null), "the edit never finished");
        return state;
    }

    private static JsonObject remove(final String name) {
        return message("editorEdit", "op", "remove", "name", name, "from", "Main", "count", 1);
    }

    /** A card of the run's main deck that is not a basic land. */
    private String nonbasic(final int skip) {
        return run.getCurrentDeck().getMain().toFlatList().stream().filter(c -> !c.getRules().getType().isBasicLand())
                .map(PaperCard::getName).distinct().skip(skip).findFirst().orElseThrow();
    }

    private RogueRun saved() {
        return RogueIO.loadRun(RogueIO.getRogueFile(run));
    }

    private static String counter(final int credits) {
        return Localizer.getInstance().getMessage("lblWebRogueRemovalCreditCount", credits);
    }

    // Fails if a removal is free, or the run on disk does not hold the smaller deck and the spent credit
    @Test(timeOut = 120_000)
    public void removingACardSpendsACreditAndSaves() throws Exception {
        final TestBrowser host = atRun(NodeSanctum.class, 8);
        Assert.assertEquals(str(openEditor(host), "counter"), counter(3));
        final String card = nonbasic(0);
        final int copies = run.getCurrentDeck().getMain().countByName(card);
        Assert.assertEquals(str(edit(host, remove(card)), "counter"), counter(2));
        Assert.assertEquals(saved().getRemovalCredits(), 2);
        Assert.assertEquals(saved().getCurrentDeck().getMain().countByName(card), copies - 1);
    }

    // Fails if undoing a removal does not give its credit back, on the page and on disk
    @Test(timeOut = 120_000)
    public void undoRefunds() throws Exception {
        final TestBrowser host = atRun(NodeSanctum.class, 8);
        openEditor(host);
        final String card = nonbasic(0);
        final int copies = run.getCurrentDeck().getMain().countByName(card);
        edit(host, remove(card));
        edit(host, landsTo("Forest", run.getCurrentDeck().getMain().countByName("Forest") + 1));
        edit(host, TestBrowser.action("editorUndo"));
        Assert.assertEquals(saved().getRemovalCredits(), 2, "undoing the added Forest gave a credit");
        Assert.assertEquals(str(edit(host, TestBrowser.action("editorUndo")), "counter"), counter(3));
        Assert.assertEquals(saved().getRemovalCredits(), 3);
        Assert.assertEquals(saved().getCurrentDeck().getMain().countByName(card), copies);
    }

    private static JsonObject landsTo(final String name, final int count) {
        final JsonArray lands = new JsonArray();
        final JsonObject land = new JsonObject();
        land.addProperty("name", name);
        land.addProperty("count", count);
        lands.add(land);
        final JsonObject m = message("editorEdit", "op", "lands", "count", 0);
        m.add("lands", lands);
        return m;
    }

    // Fails if a removal beyond the credits is taken, or refused with other words than desktop's
    @Test(timeOut = 120_000)
    public void aFourthRemovalIsRefusedWithDesktopsWords() throws Exception {
        final TestBrowser host = atRun(NodeSanctum.class, 8);
        openEditor(host);
        for (int i = 0; i < 3; i++) {
            edit(host, remove(nonbasic(i)));
        }
        final String fourth = nonbasic(3);
        final int copies = saved().getCurrentDeck().getMain().countByName(fourth);
        final JsonObject state = edit(host, remove(fourth));
        final JsonObject notice = host.awaitMatching("notice", n -> true, "the refusal was not told");
        Assert.assertEquals(str(notice, "title"), Localizer.getInstance().getMessage("lblRogueRemovalLimitHint"));
        Assert.assertTrue(state.get("counterSpent").getAsBoolean());
        Assert.assertEquals(saved().getCurrentDeck().getMain().countByName(fourth), copies);
        Assert.assertEquals(saved().getRemovalCredits(), 0);
    }

    // Fails if a node's step leaves the editor open, so a later edit writes back a deck from before the step
    @Test(timeOut = 120_000)
    public void enteringANodeClosesTheEditor() throws Exception {
        final TestBrowser host = atRun(NodeEvent.class, 8);
        openEditor(host);
        final int before = host.got.size();
        sessions.onMessage(host, message("rogueEnter", "snapshot", snapshot(host), "node", EVENT));
        host.awaitMatching("editor", e -> host.got.indexOf(e) >= before && (!e.has("state") || e.get("state").isJsonNull()), "the editor stayed open");
        settle(host);
        final CardPool afterEvent = saved().getCurrentDeck().getMain();
        send(host, remove(nonbasic(0)));
        awaitTrue(() -> game().mayLeave(null), "the edit never finished");
        Assert.assertEquals(saved().getCurrentDeck().getMain(), afterEvent, "an edit after the step changed the deck");
        Assert.assertTrue(saved().getPath().getNode(EVENT).isCompleted());
    }

    // Fails if the deck can be edited or its editor opened while a question waits, or closing the editor then disturbs the question
    @Test(timeOut = 120_000)
    public void aDeckEditDuringAQuestionIsRefusedButClosingIsNot() throws Exception {
        final TestBrowser host = atRun(NodeEvent.class, 8);
        openEditor(host);
        sessions.onMessage(host, message("rogueEnter", "snapshot", snapshot(host), "node", EVENT));
        final JsonObject question = host.awaitMatching("rogueQuestion", q -> !handled.contains(q.get("id").getAsInt()), "the node asked nothing");
        final long now = snapshot(host);
        final int before = host.got.size();
        sessions.onMessage(host, remove(nonbasic(0)));
        sessions.onMessage(host, message("rogueEditDeck", "snapshot", now));
        sessions.onMessage(host, TestBrowser.action("editorClose"));
        sessions.onMessage(host, TestBrowser.action("editorUndo"));
        Thread.sleep(500);
        Assert.assertTrue(host.got.subList(before, host.got.size()).stream().map(m -> str(m, "t"))
                .noneMatch(t -> "editor".equals(t) || "notice".equals(t)), "an editor command was taken during a question");
        Assert.assertEquals(saved().getRemovalCredits(), 3);
        Assert.assertEquals(host.last("rogueQuestion").get("id"), question.get("id"));
        settle(host);
        Assert.assertTrue(saved().getPath().getNode(EVENT).isCompleted(), "the question could not be answered after closing");
    }

    // Fails if leaving the editor does not evaluate the run's achievements, as desktop does
    @Test(timeOut = 120_000)
    public void leavingTheEditorEvaluatesAchievements() throws Exception {
        final Achievement hoarder = StreamSupport.stream(RogueCommanderAchievements.getInstance().spliterator(), false)
                .filter(a -> "GoldHoarder".equals(a.getKey())).findFirst().orElseThrow();
        final Element unearned = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument().createElement("a");
        unearned.setAttribute("best", "0");
        unearned.setAttribute("time", "0");
        hoarder.loadFromXml(unearned);
        final TestBrowser host = atRun(NodeSanctum.class, 15);
        openEditor(host);
        Assert.assertEquals(hoarder.getBest(), 0, "opening the editor earned it");
        send(host, TestBrowser.action("editorClose"));
        awaitTrue(() -> hoarder.getBest() > 0, "leaving the editor did not earn Gold Hoarder with 15 gold");
    }

    // Fails if a basic land outside the commander's colours can be added, or a colourless one the run does not list is refused as off colour
    @Test(timeOut = 120_000)
    public void basicsAreTheCommandersOnly() throws Exception {
        final TestBrowser host = atRun(NodeSanctum.class, 8);
        final JsonObject state = openEditor(host);
        final List<JsonObject> lands = StreamSupport.stream(state.getAsJsonArray("lands").spliterator(), false).map(JsonElement::getAsJsonObject).toList();
        final Set<String> allowed = lands.stream().filter(l -> !l.has("refusal") || l.get("refusal").isJsonNull()).map(l -> str(l, "name"))
                .collect(Collectors.toSet());
        Assert.assertEquals(allowed, Set.of("Plains", "Island", "Mountain", "Forest"));
        Assert.assertEquals(lands.stream().filter(l -> str(l, "name").equals("Wastes")).map(l -> str(l, "refusal")).findFirst().orElseThrow(),
                Localizer.getInstance().getMessage("lblWebCollectionNotOwned"));
        edit(host, landsTo("Swamp", 1));
        edit(host, message("editorEdit", "op", "add", "name", "Wastes", "to", "Main", "count", 1));
        Assert.assertEquals(saved().getCurrentDeck().getMain().countByName("Swamp"), 0);
        Assert.assertEquals(saved().getCurrentDeck().getMain().countByName("Wastes"), 0);
    }
}
