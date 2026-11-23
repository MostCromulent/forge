package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.deck.CardPool;
import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueRun;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** Tests the run's deck in the browser's deck editor: removal credits, undo, the run's basics, and the editor against the flow. */
@Test(singleThreaded = true)
public class RogueDeckTest extends WebHubTest {
    private static final int EVENT = 8;
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

    /** Opens the fixture's run at a node of the given type, with the credits and gold given, with every question on the way answered. */
    private TestBrowser atRun(final Class<? extends RoguePathNode> at, final int credits, final int gold) throws InterruptedException {
        run = RogueFixture.run(at);
        run.setRemovalCredits(credits);
        run.setCurrentGold(gold);
        RogueIO.saveRun(run);
        final TestBrowser host = connect("host");
        sessions.onMessage(host, TestBrowser.action("claimHost"));
        sessions.onMessage(host, message("setName", "name", "host"));
        awaitTrue(() -> game().mayLeave(null), "the game's job never finished");
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

    private static JsonObject answer(final int id, final JsonElement value) {
        final JsonObject m = message("rogueAnswer", "id", id);
        m.add("value", value);
        return m;
    }

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
        return JsonNull.INSTANCE;
    }

    /** Answers every question until the flow is idle. */
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
        final TestBrowser host = atRun(NodeSanctum.class, 3, 8);
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
        final TestBrowser host = atRun(NodeSanctum.class, 3, 8);
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
        final TestBrowser host = atRun(NodeSanctum.class, 3, 8);
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
        final TestBrowser host = atRun(NodeEvent.class, 3, 8);
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
        final TestBrowser host = atRun(NodeEvent.class, 3, 8);
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
        final TestBrowser host = atRun(NodeSanctum.class, 3, 15);
        openEditor(host);
        Assert.assertEquals(hoarder.getBest(), 0, "opening the editor earned it");
        send(host, TestBrowser.action("editorClose"));
        awaitTrue(() -> hoarder.getBest() > 0, "leaving the editor did not earn Gold Hoarder with 15 gold");
    }

    // Fails if the editor lets the commander be replaced or taken out
    @Test(timeOut = 120_000)
    public void theCommanderCannotBeChanged() throws Exception {
        final TestBrowser host = atRun(NodeSanctum.class, 3, 8);
        openEditor(host);
        final List<String> commanders = run.getCurrentDeck().getCommanders().stream().map(PaperCard::getName).collect(Collectors.toList());
        edit(host, message("editorEdit", "op", "commander", "name", nonbasic(0), "from", "Main", "to", "Commander", "count", 1));
        edit(host, message("editorEdit", "op", "remove", "name", commanders.get(0), "from", "Commander", "count", 1));
        Assert.assertEquals(saved().getCurrentDeck().getCommanders().stream().map(PaperCard::getName).collect(Collectors.toList()), commanders);
        Assert.assertEquals(saved().getRemovalCredits(), 3);
    }

    // Fails if a basic land outside the commander's colours can be added
    @Test(timeOut = 120_000)
    public void basicsAreTheCommandersOnly() throws Exception {
        final TestBrowser host = atRun(NodeSanctum.class, 3, 8);
        final JsonObject state = openEditor(host);
        final Set<String> allowed = StreamSupport.stream(state.getAsJsonArray("lands").spliterator(), false).map(JsonElement::getAsJsonObject)
                .filter(l -> l.get("allowed").getAsBoolean()).map(l -> str(l, "name")).collect(Collectors.toSet());
        Assert.assertEquals(allowed, Set.of("Plains", "Island", "Mountain", "Forest"));
        edit(host, landsTo("Swamp", 1));
        edit(host, message("editorEdit", "op", "add", "name", "Wastes", "to", "Main", "count", 1));
        Assert.assertEquals(saved().getCurrentDeck().getMain().countByName("Swamp"), 0);
        Assert.assertEquals(saved().getCurrentDeck().getMain().countByName("Wastes"), 0);
    }
}
