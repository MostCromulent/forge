package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.DrawOfferCoordinator;
import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueRunHistoryEntry;
import forge.gamemodes.rogue.effect.CustomRunModification;
import forge.gamemodes.rogue.path.NodePlanebound;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.util.Localizer;
import forge.web.campaign.rogue.RogueGame;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.stream.StreamSupport;

/** Plays Rogue's battles in the browser: the result screen, what follows it, and a match left with no result. Each test plays a real game. */
@Test(singleThreaded = true)
public class RogueMatchTest extends RogueSessionTest {
    private static final int PLANE = 5;
    private static final int BOSS = 15;

    /** Rogue's progress announces through Forge's own GUI, so the sessions run on it. */
    @Override
    WebGuiBase gui() {
        return (WebGuiBase) GuiBase.getInterface();
    }

    @BeforeMethod
    public void onlyUnderStress() {
        WebTestSupport.skipUnlessStress();
    }

    @AfterMethod(alwaysRun = true)
    public void leaveTheMatch() throws InterruptedException {
        final TestBrowser host = connect("host");
        if ("playing".equals(str(host.hello, "screen"))) {
            send(host, TestBrowser.action("leave"));
            host.awaitMatching("hello", h -> !"playing".equals(str(h, "screen")), "the match was not left");
        }
        if (RogueGame.MODE.equals(str(host.hello, "campaign"))) {
            settle(host);
        }
    }

    private static String text(final String key) {
        return Localizer.getInstance().getMessage(key);
    }

    /** Enters the node, answers what is asked before the battle, and waits until the human may act. */
    private void toBattle(final TestBrowser host, final int node) {
        sessions.onMessage(host, message("rogueEnter", "snapshot", snapshot(host), "node", node));
        awaitTrue(() -> {
            final JsonObject question = host.last("rogueQuestion");
            if (question != null && handled.add(question.get("id").getAsInt())) {
                sessions.onMessage(host, answer(question.get("id").getAsInt(), acceptable(question)));
            }
            return "playing".equals(str(host.hello, "screen"));
        }, "the battle did not start");
        awaitPriority(host);
    }

    private Game playing() {
        return sessions.hostLobby().getHostedMatch().getGame();
    }

    private Player human() {
        return playing().getPlayers().stream().filter(WebTestSupport::isWebSeat).findFirst().orElseThrow();
    }

    /** The computer concedes while the human holds priority, as the browser's concede does for the human. */
    private void opponentConcedes() {
        final Game game = playing();
        game.getPlayers().stream().filter(p -> !WebTestSupport.isWebSeat(p)).findFirst().orElseThrow().concede();
        game.getAction().checkGameOverCondition();
        DrawOfferCoordinator.releaseInputQueues(game);
    }

    private static JsonObject result(final TestBrowser browser) throws InterruptedException {
        return browser.awaitMatching("campaignResult", r -> true, "no result was sent");
    }

    private static JsonObject button(final JsonObject result, final int index) {
        return result.getAsJsonArray("buttons").get(index).getAsJsonObject();
    }

    private void leave(final TestBrowser host, final String choice) throws InterruptedException {
        send(host, message("leave", "choice", choice));
        host.awaitMatching("hello", h -> RogueGame.MODE.equals(str(h, "campaign")) && !"playing".equals(str(h, "screen")), "the match was not left");
    }

    /** Waits for the battle's spoils that no card reward showed, and continues past them. */
    private JsonObject spoils(final TestBrowser host) throws InterruptedException {
        final JsonObject asked = host.awaitMatching("rogueQuestion", q -> q.has("tell") && "SPOILS".equals(str(q.getAsJsonObject("tell"), "kind")),
                "the battle's spoils were not shown");
        handled.add(asked.get("id").getAsInt());
        sessions.onMessage(host, answer(asked.get("id").getAsInt(), JsonNull.INSTANCE));
        return asked.getAsJsonObject("tell");
    }

    /** The titles of the spoils a reward or a spoils screen lists, each with its icon. */
    private static List<String> spoilTitles(final JsonObject shown) {
        return StreamSupport.stream(shown.getAsJsonArray("spoils").spliterator(), false).map(JsonElement::getAsJsonObject)
                .map(s -> str(s, "title") + " " + str(s, "icon")).toList();
    }

    private static int gold(final TestBrowser host) {
        return StreamSupport.stream(host.last("rogueMap").getAsJsonObject("bar").getAsJsonObject("bar").getAsJsonArray("balances").spliterator(), false)
                .map(b -> b.getAsJsonObject()).filter(b -> "ICO_QUEST_COIN".equals(str(b, "icon"))).findFirst().orElseThrow().get("amount").getAsInt();
    }

    private static RogueRunHistoryEntry lastRun() {
        final List<RogueRunHistoryEntry> history = RogueMetaProgress.getInstance().getRunHistory();
        return history.get(history.size() - 1);
    }

    /** Opens the fixture's run at the boss, with the node below it won. */
    private TestBrowser atBoss(final boolean custom) throws InterruptedException {
        run = RogueFixture.run(NodePlanebound.class);
        run.getPath().getNode(10).setCompleted(true);
        run.setCurrentNodeIndex(BOSS);
        if (custom) {
            run.addCustomRunModification(CustomRunModification.VOID);
        }
        return openRun();
    }

    // Fails if conceding is not a lost run, as on desktop
    @Test(timeOut = 300_000)
    public void concedingIsALoss() throws Exception {
        final TestBrowser host = atRun(NodePlanebound.class, 8);
        toBattle(host, PLANE);
        sessions.onMessage(host, TestBrowser.action("concede"));
        final JsonObject result = result(host);
        Assert.assertEquals(str(result, "line"), text("lblRogueRunLost"));
        Assert.assertFalse(result.get("won").getAsBoolean());
        Assert.assertEquals(str(button(result, 0), "label"), text("lblRogueEndRun"));
        Assert.assertEquals(lastRun().getOutcome(), "DEFEAT");
    }

    // Fails if a reward is asked over the result screen, leaving the result does not go on to the rewards, they are not taken into the run,
    // the reward does not list the gold won, or the gold it lists is not held back from the bar until the reward counts it in
    @Test(timeOut = 300_000)
    public void rewardsAreAskedAfterLeaving() throws Exception {
        final TestBrowser host = atRun(NodePlanebound.class, 8);
        final int before = gold(host);
        toBattle(host, PLANE);
        opponentConcedes();
        final JsonObject result = result(host);
        Assert.assertEquals(str(result, "line"), text("lblRogueBattleWon"));
        Assert.assertEquals(buttons(result), List.of("continue"));
        Assert.assertTrue(host.all("rogueQuestion").stream().noneMatch(q -> q.has("cards")), "a reward was asked over the result");
        leave(host, "continue");
        final JsonObject reward = host.awaitMatching("rogueQuestion", q -> q.has("cards") && "REWARD".equals(str(q.getAsJsonObject("cards"), "kind")),
                "no reward was asked");
        handled.add(reward.get("id").getAsInt());
        final JsonObject cards = reward.getAsJsonObject("cards");
        final int won = cards.getAsJsonObject("gains").get("gold").getAsInt();
        Assert.assertTrue(won > 0);
        Assert.assertTrue(spoilTitles(cards).contains(text("lblRogueGoldReward") + " ICO_QUEST_COIN"), spoilTitles(cards).toString());
        Assert.assertEquals(gold(host), before + won);
        Assert.assertEquals(cards.get("gold").getAsInt(), before + won);
        final JsonArray first = new JsonArray();
        first.add(0);
        sessions.onMessage(host, answer(reward.get("id").getAsInt(), first));
        settle(host);
        Assert.assertTrue(host.all("rogueQuestion").stream().noneMatch(q -> q.has("tell") && "SPOILS".equals(str(q.getAsJsonObject("tell"), "kind"))),
                "spoils the reward showed were shown again");
        Assert.assertTrue(RogueIO.loadRun(RogueIO.getRogueFile(run)).getPath().getNode(PLANE).isCompleted());
    }

    // Fails if the boss's result left without a choice ends the run, or the choice is not asked when the player comes back
    @Test(timeOut = 300_000)
    public void leavingTheBossWithoutChoosingAsks() throws Exception {
        final TestBrowser host = atBoss(true);
        toBattle(host, BOSS);
        opponentConcedes();
        final JsonObject result = result(host);
        Assert.assertEquals(str(result, "line"), text("lblRogueRunCompleted"));
        Assert.assertTrue(str(result, "detail").startsWith("Boss slain: "));
        // A custom run's win unlocks Endless Mode as the result is decided
        Assert.assertEquals(buttons(result), List.of("complete", "endless"));
        Assert.assertNull(str(button(result, 1), "disabled"));
        leave(host, null);
        host.awaitMatching("rogueAnnounce", a -> "Endless Mode Unlocked!".equals(str(a, "title")), "the unlock the battle earned was not announced");
        Assert.assertEquals(spoils(host).getAsJsonObject("gains").get("echoes").getAsInt(), 10);
        sessions.disconnected(host);
        final TestBrowser back = connect("host");
        final JsonObject ending = back.awaitMatching("rogueQuestion", q -> q.has("choice") && "ending".equals(str(q.getAsJsonObject("choice"), "kind")),
                "the ending was not asked");
        handled.add(ending.get("id").getAsInt());
        sessions.onMessage(back, answer(ending.get("id").getAsInt(), new JsonPrimitive(1)));
        settle(back);
        Assert.assertEquals(RogueIO.loadRun(RogueIO.getRogueFile(run)).getEndlessLevel(), 1);
    }

    // Fails if Go Endless can be chosen while it is locked, from the result or from the question that follows it
    @Test(timeOut = 300_000)
    public void aLockedEndlessCannotBeChosen() throws Exception {
        final TestBrowser host = atBoss(false);
        toBattle(host, BOSS);
        opponentConcedes();
        final JsonObject endless = button(result(host), 1);
        Assert.assertEquals(str(endless, "disabled"), text("lblRogueUnlockEndlessHint"));
        leave(host, "endless");
        spoils(host);
        final JsonObject ending = host.awaitMatching("rogueQuestion", q -> q.has("choice"), "the ending was not asked");
        final int id = ending.get("id").getAsInt();
        Assert.assertFalse(ending.getAsJsonObject("choice").getAsJsonArray("options").get(1).getAsJsonObject().get("enabled").getAsBoolean());
        send(host, answer(id, new JsonPrimitive(1)));
        host.awaitMatching("rogueQuestion", q -> q.get("id").getAsInt() == id, "a locked Go Endless closed the question");
        handled.add(id);
        sessions.onMessage(host, answer(id, new JsonPrimitive(0)));
        settle(host);
        Assert.assertEquals(lastRun().getOutcome(), "VICTORY");
    }

    // Fails if the Spark an Endless run wins as it ends is not shown, as no card reward follows the battle
    @Test(timeOut = 300_000)
    public void aSparkWonOnRunDefeatIsShown() throws Exception {
        run = RogueFixture.run(NodePlanebound.class);
        run.setEndlessLevel(1);
        run.setDescensionLevel(1);
        final TestBrowser host = openRun();
        toBattle(host, PLANE);
        sessions.onMessage(host, TestBrowser.action("concede"));
        leave(host, str(button(result(host), 0), "action"));
        final JsonObject shown = spoils(host);
        Assert.assertEquals(str(shown, "title"), text("lblRogueRunWon"));
        Assert.assertTrue(spoilTitles(shown).contains(text("lblRogueSparkReward") + " ICO_QUEST_ELIXIR"), spoilTitles(shown).toString());
    }

    // Fails if a carry card lost in a won battle is not listed with the card reward
    @Test(timeOut = 300_000)
    public void lostCarryCardsAreListed() throws Exception {
        run = RogueFixture.run(NodePlanebound.class);
        run.addCarryCard(FModel.getMagicDb().getCommonCards().getCard("Sol Ring"), RogueRun.CarryCardType.ITEM, "test");
        final TestBrowser host = openRun();
        toBattle(host, PLANE);
        final Card ring = human().getZone(ZoneType.Command).getCards().stream().filter(c -> "Sol Ring".equals(c.getName())).findFirst().orElseThrow();
        human().getZone(ZoneType.Command).remove(ring);
        opponentConcedes();
        leave(host, str(button(result(host), 0), "action"));
        final JsonObject reward = host.awaitMatching("rogueQuestion", q -> q.has("cards") && "REWARD".equals(str(q.getAsJsonObject("cards"), "kind")),
                "no reward was asked");
        Assert.assertTrue(spoilTitles(reward.getAsJsonObject("cards")).contains(text("lblRogueLostCarryCards") + " ICO_QUEST_MINUS"),
                spoilTitles(reward.getAsJsonObject("cards")).toString());
    }

    // Fails if a match left before its end keeps the Wrathful effects spent on it, or the map cannot be used after
    @Test(timeOut = 300_000)
    public void aMatchClosedWithNoResultReloadsTheSave() throws Exception {
        run = RogueFixture.run(NodePlanebound.class);
        ((NodePlanebound) run.getPath().getNode(PLANE)).setWrathfulCount(1);
        final TestBrowser host = openRun();
        toBattle(host, PLANE);
        leave(host, null);
        host.awaitMatching("rogueTraits", t -> StreamSupport.stream(t.getAsJsonArray("traits").spliterator(), false)
                .noneMatch(trait -> "WRATHFUL".equals(str(trait.getAsJsonObject(), "source"))), "the Wrathful effect stayed");
        settle(host);
        Assert.assertTrue(game().submit(snapshot(host), PLANE, () -> { }), "the plane cannot be entered again");
    }
}
