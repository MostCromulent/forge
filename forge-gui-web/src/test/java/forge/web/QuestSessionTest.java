package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.game.Game;
import forge.game.GameType;
import forge.game.player.Player;
import forge.gamemodes.quest.QuestEventDuel;
import forge.gamemodes.quest.bazaar.QuestItemType;
import forge.gamemodes.quest.data.QuestData;
import forge.gamemodes.quest.data.QuestPreferences.QPref;
import forge.gamemodes.quest.io.QuestDataIO;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import forge.util.Localizer;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A quest as a browser drives it, and its duels as netplay plays them. */
public class QuestSessionTest extends SessionsTest {
    @Override
    WebGuiBase gui() {
        return (WebGuiBase) GuiBase.getInterface();
    }

    @Override
    void afterDisconnecting() {
        try {
            QuestFixture.cleanUp();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** How much of a balance the bar shows, by its icon. */
    private static int balance(final JsonObject bar, final String icon) {
        for (final JsonElement b : bar.getAsJsonArray("balances")) {
            if (icon.equals(b.getAsJsonObject().get("icon").getAsString())) {
                return b.getAsJsonObject().get("amount").getAsInt();
            }
        }
        throw new AssertionError("The bar has no balance of " + icon);
    }

    /** The host on the list of saved quests, wherever the last test left the session. */
    private Recorder onTheShelf() throws InterruptedException {
        final Recorder host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        host.awaitMatching("hello", h -> h.get("host").getAsBoolean(), "the host's seat was not given");
        sessions.onMessage(host, message("setName", "name", "Host"));
        send(host, message("campaignOpen", "mode", "quest", "resume", false));
        host.awaitMatching("hello", h -> "quest".equals(str(h, "campaign")) && str(h, "campaignSave") == null, "the shelf did not open");
        host.awaitMatching("questSaves", s -> true, "the quests were not listed");
        return host;
    }

    private Recorder hostInQuest(final QuestData data) throws InterruptedException {
        final Recorder host = onTheShelf();
        send(host, message("campaignLoad", "name", data.getName()));
        host.awaitMatching("hello", h -> data.getName().equals(str(h, "campaignSave")), "the quest did not open");
        return host;
    }

    // The session outlives a test, so a quest the cleanup removes would be shown to the next one's browser
    @AfterMethod(alwaysRun = true)
    public void leaveTheQuest() throws InterruptedException {
        Recorder host = null;
        for (final Recorder browser : browsers) {
            if (browser.hello != null && browser.hello.get("host").getAsBoolean()) {
                host = browser;
            }
        }
        if (host == null) {
            return;
        }
        if (host.hello.get("inMatch").getAsBoolean()) {
            send(host, JsonCodec.message("leave"));
            if (host.awaitMatching("hello", h -> !h.get("inMatch").getAsBoolean()) == null) {
                return;
            }
        }
        if (str(host.hello, "campaignSave") != null) {
            send(host, JsonCodec.message("campaignLeave"));
            // The shelf reads every save as it opens, and the cleanup must not remove one under that reading
            if (host.awaitMatching("questSaves", saves -> true) == null) {
                return;
            }
        }
        if ("quest".equals(str(host.hello, "campaign"))) {
            send(host, JsonCodec.message("campaignLeave"));
            host.awaitMatching("hello", h -> !"quest".equals(str(h, "campaign")));
        }
    }

    // Fails if a quest's save is not listed on its shelf with its record and credits
    @Test(timeOut = 120_000)
    public void theShelfListsAQuestWithItsRecord() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = onTheShelf();
        final JsonObject saves = host.awaitNewest("questSaves");
        JsonObject row = null;
        for (final JsonElement e : saves.getAsJsonArray("saves")) {
            if (data.getName().equals(e.getAsJsonObject().get("name").getAsString())) {
                row = e.getAsJsonObject();
            }
        }
        Assert.assertNotNull(row, "the fixture is not on the shelf");
        Assert.assertEquals(row.get("wins").getAsInt(), data.getAchievements().getWin());
        Assert.assertEquals(row.get("losses").getAsInt(), data.getAchievements().getLost());
        Assert.assertEquals(row.get("credits").getAsLong(), 250);
        Assert.assertEquals(row.get("mode").getAsString(), "Fantasy");
    }

    // Fails if a quest cannot be opened from its shelf, or opening it does not make it the current quest
    @Test(timeOut = 120_000)
    public void aQuestOpensFromItsShelf() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        Assert.assertEquals(FModel.getQuestPreferences().getPref(QPref.CURRENT_QUEST), data.getName() + ".dat");
        Assert.assertEquals(FModel.getQuest().getName(), data.getName());
        final JsonObject bar = host.awaitNewest("campaignBar", "the bar was not sent");
        Assert.assertEquals(balance(bar, "ICO_QUEST_COINSTACK"), 250);
        Assert.assertEquals(str(bar, "name"), data.getName());
    }

    /** The save as it is on disk, read as desktop would read it. */
    private static QuestData saved(final QuestData data) throws IOException {
        return QuestDataIO.loadData(QuestGame.find(data.getName()));
    }

    // Fails if the duels the page lists are not the duels manager's, in its order, with the random opponent's title hidden
    @Test(timeOut = 120_000)
    public void theDuelsAreTheManagers() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        final JsonObject page = host.awaitNewest("questDuels", "the duels were not sent");
        final List<QuestEventDuel> expected = FModel.getQuest().getDuelsManager().generateDuels();
        final JsonArray rows = page.getAsJsonArray("duels");
        Assert.assertEquals(rows.size(), expected.size());
        for (int i = 0; i < rows.size(); i++) {
            final JsonObject row = rows.get(i).getAsJsonObject();
            final QuestEventDuel duel = expected.get(i);
            Assert.assertEquals(row.get("index").getAsInt(), i);
            if (duel.showDifficulty()) {
                Assert.assertFalse(row.get("random").getAsBoolean());
                Assert.assertEquals(row.get("title").getAsString(), duel.getTitle());
                Assert.assertEquals(row.get("difficulty").getAsInt(), duel.getDifficulty().ordinal() + 1);
                Assert.assertNotNull(str(row, "face"), duel.getTitle() + " has no face");
            } else {
                Assert.assertTrue(row.get("random").getAsBoolean());
                Assert.assertEquals(row.get("difficulty").getAsInt(), 0);
                Assert.assertNull(str(row, "face"), "the random opponent's deck shows");
                Assert.assertNotEquals(row.get("title").getAsString(), duel.getOpponentName());
            }
        }
        Assert.assertEquals(page.get("deck").getAsString(), "Forest deck");
        Assert.assertEquals(page.get("matchLength").getAsInt(), 3);
    }

    // Fails if the match length chosen is not kept in the save, or a length no charm allows is accepted
    @Test(timeOut = 120_000)
    public void theMatchLengthIsLimitedByCharmsAndSaved() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        host.awaitNewest("questDuels", "the duels were not sent");
        send(host, message("questMatchLength", "games", 5));
        JsonObject page = host.awaitNewest("questDuels", "the page was not sent again");
        Assert.assertEquals(page.get("matchLength").getAsInt(), 3);
        Assert.assertEquals(saved(data).getMatchLength(), 3);
        FModel.getQuest().getAssets().setItemLevel(QuestItemType.CHARM, 1);
        send(host, message("questMatchLength", "games", 5));
        page = host.awaitNewest("questDuels", "the page was not sent again");
        Assert.assertEquals(page.get("matchLength").getAsInt(), 5);
        Assert.assertTrue(page.getAsJsonArray("matchLengths").contains(new JsonPrimitive(5)));
        Assert.assertEquals(saved(data).getMatchLength(), 5);
    }

    // Fails if choosing no plant does not empty its slot in the save, or choosing the Wolf does not fill the pet slot
    @Test(timeOut = 120_000)
    public void petChoicesAreSaved() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        host.awaitNewest("questDuels", "the duels were not sent");
        send(host, message("questPet", "slot", 0, "name", "Plant"));
        host.awaitNewest("questDuels", "the page was not sent again");
        Assert.assertEquals(saved(data).getPetSlots().get(0), "Plant");
        send(host, message("questPet", "slot", 0));
        host.awaitNewest("questDuels", "the page was not sent again");
        Assert.assertNull(saved(data).getPetSlots().get(0));
        send(host, message("questPet", "slot", 1, "name", "Wolf"));
        final JsonObject page = host.awaitNewest("questDuels", "the page was not sent again");
        Assert.assertEquals(saved(data).getPetSlots().get(1), "Wolf");
        Assert.assertEquals(page.getAsJsonArray("pets").get(1).getAsJsonObject().get("chosen").getAsString(), "Wolf");
        // A pet the quest does not own is not summoned
        send(host, message("questPet", "slot", 1, "name", "Bird"));
        host.awaitNewest("questDuels", "the page was not sent again");
        Assert.assertEquals(saved(data).getPetSlots().get(1), "Wolf");
    }

    private boolean devModeBefore;

    @BeforeClass
    public void allowTheGameToBeWon() {
        WebTestSupport.initModel();
        devModeBefore = FModel.getPreferences().getPrefBoolean(FPref.DEV_MODE_ENABLED);
        FModel.getPreferences().setPref(FPref.DEV_MODE_ENABLED, true);
    }

    @AfterClass(alwaysRun = true)
    public void restoreDevMode() {
        FModel.getPreferences().setPref(FPref.DEV_MODE_ENABLED, devModeBefore);
    }

    /** The host in the first duel of the quest, at its first priority. */
    private Recorder inDuel(final QuestData data) throws InterruptedException {
        final Recorder host = hostInQuest(data);
        host.awaitNewest("questDuels", "the duels were not sent");
        send(host, message("questDuel", "index", 0));
        host.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean(), "the duel did not start");
        awaitPriority(host);
        return host;
    }

    private void answer(final Recorder host, final JsonObject question, final int option) {
        final JsonObject reply = message("hostChoice", "id", question.get("id").getAsInt());
        final JsonArray value = new JsonArray();
        value.add(option);
        reply.add("value", value);
        sessions.onMessage(host, reply);
    }

    /** Answers each question the reward script asks with its first option until the reward arrives. */
    private JsonObject untilRewarded(final Recorder host) throws InterruptedException {
        final Set<Integer> asked = new HashSet<>();
        final long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end) {
            for (final JsonObject m : host.got) {
                final String type = m.get("t").getAsString();
                if ("reward".equals(type)) {
                    return m;
                }
                if ("hostChoice".equals(type) && asked.add(m.get("id").getAsInt())) {
                    answer(host, m, 0);
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the reward was not sent: " + host.got.stream().map(m -> m.get("t").getAsString()).distinct().toList());
    }

    private static JsonObject step(final JsonObject reward, final String kind, final String title) {
        for (final JsonElement e : reward.getAsJsonArray("steps")) {
            final JsonObject s = e.getAsJsonObject();
            if (kind.equals(s.get("kind").getAsString()) && str(s, "title") != null && str(s, "title").contains(title)) {
                return s;
            }
        }
        throw new AssertionError("no " + kind + " step titled " + title + " in " + reward);
    }

    // Fails if a duel does not start with the quest's deck against the chosen opponent
    @Test(timeOut = 180_000)
    public void aDuelStartsAgainstTheChosenOpponent() throws Exception {
        WebTestSupport.skipUnlessStress();
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        final JsonObject row = host.awaitNewest("questDuels", "the duels were not sent").getAsJsonArray("duels").get(0).getAsJsonObject();
        send(host, message("questDuel", "index", 0));
        host.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean(), "the duel did not start");
        awaitPriority(host);
        final Game game = sessions.hostLobby().getHostedMatch().getGame();
        Assert.assertEquals(game.getRules().getGameType(), GameType.Quest);
        final List<String> names = game.getPlayers().stream().map(Player::getName).toList();
        Assert.assertTrue(names.contains(row.get("title").getAsString()), "the opponents are " + names);
        for (final Player p : game.getPlayers()) {
            if (p.getController() instanceof PlayerControllerHuman) {
                Assert.assertEquals(p.getRegisteredPlayer().getDeck().getName(), "Forest deck");
            }
        }
    }

    // Fails if a won duel is not recorded once, or its credits are not in the save when the reward is shown
    @Test(timeOut = 240_000)
    public void aWonDuelIsRecordedOnce() throws Exception {
        WebTestSupport.skipUnlessStress();
        final QuestData data = QuestFixture.install();
        data.setMatchLength(1);
        data.saveData();
        final Recorder host = inDuel(data);
        computerLoses(host);
        final JsonObject result = host.awaitMatching("campaignResult", r -> true, "no result was sent");
        Assert.assertTrue(result.get("won").getAsBoolean());
        Assert.assertEquals(buttons(result), List.of("leave"));

        // A reload on the result screen shows the same result and records nothing
        final Recorder reloaded = connect("host");
        Assert.assertEquals(reloaded.awaitMatching("campaignResult", r -> true, "a reload lost the result"), result);
        sessions.onMessage(reloaded, JsonCodec.message("leave"));
        final JsonObject reward = untilRewarded(reloaded);
        step(reward, "MESSAGE", Localizer.getInstance().getMessage("lblGameplayResults"));
        final QuestData after = saved(data);
        Assert.assertEquals(after.getAchievements().getWin(), 1);
        Assert.assertEquals(after.getAchievements().getLost(), 0);
        Assert.assertTrue(after.getAssets().getCredits() > 250, "no credits were won: " + after.getAssets().getCredits());

        final Recorder again = connect("host");
        Assert.assertEquals(again.awaitMatching("reward", r -> true, "a reload lost the reward"), reward);
        Assert.assertEquals(saved(data).getAchievements().getWin(), 1);

        send(again, JsonCodec.message("rewardClaim"));
        final Recorder third = connect("host");
        third.awaitMatching("questDuels", s -> true, "the page was not sent");
        Assert.assertFalse(third.got.stream().anyMatch(m -> "reward".equals(m.get("t").getAsString())), "a claimed reward was sent again");
    }

    // Fails if a duel quit before its match is over is not recorded as a loss with the penalty
    @Test(timeOut = 240_000)
    public void quittingADuelEarlyIsALossWithThePenalty() throws Exception {
        WebTestSupport.skipUnlessStress();
        final QuestData data = QuestFixture.install();
        final Recorder host = inDuel(data);
        sessions.onMessage(host, JsonCodec.message("concede"));
        Assert.assertEquals(buttons(host.awaitMatching("campaignResult", r -> true, "no result was sent")), List.of("nextGame", "quit"));
        sessions.onMessage(host, message("nextGame", "decision", "QUIT"));
        send(host, JsonCodec.message("leave"));
        host.awaitMatching("questDuels", s -> true, "leaving did not return to the duels");
        final QuestData after = saved(data);
        Assert.assertEquals(after.getAchievements().getLost(), 1);
        Assert.assertEquals(after.getAchievements().getWin(), 0);
        Assert.assertEquals(after.getAssets().getCredits(), 250 - FModel.getQuestPreferences().getPrefInt(QPref.PENALTY_LOSS));
    }

    // Fails if a won best-of-three is not recorded as a win
    @Test(timeOut = 300_000)
    public void aWonBestOfThreeIsAWin() throws Exception {
        WebTestSupport.skipUnlessStress();
        final QuestData data = QuestFixture.install();
        final Recorder host = inDuel(data);
        computerLoses(host);
        Assert.assertEquals(buttons(host.awaitMatching("campaignResult", r -> true, "no result was sent")), List.of("nextGame", "quit"));
        send(host, message("nextGame", "decision", "CONTINUE"));
        awaitPriority(host);
        computerLoses(host);
        Assert.assertEquals(buttons(host.awaitMatching("campaignResult", r -> true, "no second result was sent")), List.of("leave"));
        // Not forgetting what came before: the booster's question may already be here
        sessions.onMessage(host, JsonCodec.message("leave"));
        untilRewarded(host);
        final QuestData after = saved(data);
        Assert.assertEquals(after.getAchievements().getWin(), 1);
        Assert.assertEquals(after.getAchievements().getLost(), 0);
        Assert.assertTrue(after.getAssets().getCredits() > 250, "the win paid a penalty or nothing: " + after.getAssets().getCredits());
    }

    // Fails if the booster reward's question is not answered by the browser's answer
    @Test(timeOut = 240_000)
    public void theBoosterIsTheFormatTheBrowserChose() throws Exception {
        WebTestSupport.skipUnlessStress();
        final QuestData data = QuestFixture.install();
        data.setMatchLength(1);
        data.saveData();
        final Recorder host = inDuel(data);
        computerLoses(host);
        host.awaitMatching("campaignResult", r -> true, "no result was sent");
        final JsonObject question = host.awaitMatching("hostChoice", q -> true, "the booster's format was not asked");
        final String chosen = question.getAsJsonArray("options").get(1).getAsString();
        answer(host, question, 1);
        send(host, JsonCodec.message("leave"));
        step(untilRewarded(host), "CARDS", chosen);
    }

    // Fails if a browser without the host's seat can open a quest
    @Test(timeOut = 120_000)
    public void aGuestCannotOpenQuest() throws Exception {
        final Recorder guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest"));
        send(guest, message("campaignOpen", "mode", "quest", "resume", false));
        Thread.sleep(500);
        Assert.assertFalse(guest.got.stream().anyMatch(m -> "questSaves".equals(m.get("t").getAsString())));
    }

    // Fails if a name with a path in it reaches a file that is not one of the saves
    @Test
    public void aNameWithAPathInItFindsNothing() throws Exception {
        final QuestData data = QuestFixture.install();
        Assert.assertNotNull(QuestGame.find(data.getName()));
        Assert.assertNull(QuestGame.find("../saves/" + data.getName()));
        Assert.assertNull(QuestGame.find("..\\saves\\" + data.getName()));
        Assert.assertNull(QuestGame.find("./" + data.getName()));
        Assert.assertNull(QuestGame.find(null));
    }
}
