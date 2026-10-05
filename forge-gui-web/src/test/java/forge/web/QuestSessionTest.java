package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.game.Game;
import forge.game.GameType;
import forge.game.player.Player;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gamemodes.quest.QuestEventChallenge;
import forge.gamemodes.quest.QuestEventDuel;
import forge.gamemodes.quest.QuestUtil;
import forge.item.PaperCard;
import forge.gamemodes.quest.bazaar.QuestItemType;
import forge.gamemodes.quest.data.QuestData;
import forge.gamemodes.quest.data.QuestPreferences.QPref;
import forge.gamemodes.quest.io.QuestDataIO;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;
import forge.util.ImageUtil;
import forge.util.Localizer;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

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

    private Recorder editor;

    // The session outlives a test, so an open editor or a quest the cleanup removes would be shown to the next one's browser
    @AfterMethod(alwaysRun = true)
    public void leaveTheQuest() throws InterruptedException {
        if (editor != null) {
            sessions.onMessage(editor, JsonCodec.message("editorClose"));
            editor = null;
        }
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

    // Fails if a duel does not start with the quest's deck against the chosen opponent, or the player's seat lacks the face and sleeve chosen
    @Test(timeOut = 180_000)
    public void aDuelStartsAgainstTheChosenOpponent() throws Exception {
        WebTestSupport.skipUnlessStress();
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        // Quest plays the shared human player, which a lobby match before this one may have given looks
        GamePlayerUtil.getQuestPlayer().setAvatarIndex(-1);
        GamePlayerUtil.getQuestPlayer().setSleeveIndex(-1);
        sessions.onMessage(host, message("setName", "name", "Host", "avatar", 5));
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
                Assert.assertEquals(p.getLobbyPlayer().getAvatarIndex(), 5);
                Assert.assertEquals(p.getLobbyPlayer().getSleeveIndex(), LocalGame.storedIndex(FPref.UI_SLEEVES, 0));
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

    /** The deck of a name in the quest as its file holds it, or null. */
    private static Deck savedDeck(final QuestData data, final String name) throws IOException {
        return saved(data).getAssets().getDeckStorage().get(name);
    }

    // Fails if a new deck is not stored in the quest, or a name another deck has is accepted
    @Test(timeOut = 120_000)
    public void aNewDeckIsStoredUnderAFreeName() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        editor = host;
        send(host, message("questDeckNew", "name", "Ice deck"));
        host.awaitMatching("editor", e -> e.has("state"), "the editor did not open on the new deck");
        Assert.assertNotNull(savedDeck(data, "Ice deck"), "the new deck is not in the save");
        sessions.onMessage(host, JsonCodec.message("editorClose"));
        editor = null;
        send(host, message("questDeckNew", "name", "Forest deck"));
        host.awaitMatching("error", e -> true, "a name in use was not refused");
        Assert.assertEquals(savedDeck(data, "Forest deck").getMain().countAll(), 40, "the deck of that name was replaced");
    }

    // Fails if renaming the current deck leaves the quest pointing at the old name
    @Test(timeOut = 120_000)
    public void renamingTheCurrentDeckKeepsItCurrent() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        send(host, message("questDeckRename", "deck", "Forest deck", "to", "Green deck"));
        host.awaitNewest("questDecks", d -> d.toString().contains("Green deck"), "the decks were not sent again");
        Assert.assertNull(savedDeck(data, "Forest deck"));
        Assert.assertEquals(savedDeck(data, "Green deck").getMain().countAll(), 40);
        Assert.assertEquals(saved(data).getAssets().getDeckStorage().get("Green deck").getName(), "Green deck");
        Assert.assertEquals(FModel.getQuest().getCurrentDeck(), "Green deck");
        Assert.assertEquals(QuestUtil.getCurrentDeck().getName(), "Green deck");
    }

    // Fails if a deleted deck stays in the save, or the quest still names it as current
    @Test(timeOut = 120_000)
    public void aDeletedDeckLeavesTheSave() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        send(host, message("questDeckDelete", "deck", "Forest deck"));
        host.awaitNewest("questDecks", d -> d.getAsJsonArray("decks").isEmpty(), "the decks were not sent again");
        Assert.assertNull(savedDeck(data, "Forest deck"));
        Assert.assertNull(QuestUtil.getCurrentDeck());
    }

    // Fails if a deck edited on the web cannot be read back by Quest's own reader with the same cards, or takes a card the quest does not own
    @Test(timeOut = 120_000)
    public void anEditedDeckReadsBackInQuest() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        final PaperCard owned = data.getAssets().getCardPool().toFlatList().stream().filter(c -> !c.getRules().getType().isLand()).findFirst().orElseThrow();
        editor = host;
        send(host, message("questDeckEdit", "deck", "Forest deck"));
        host.awaitMatching("editor", e -> e.has("state"), "the editor did not open");
        send(host, message("editorEdit", "op", "add", "name", owned.getName(), "to", "Sideboard", "count", 1));
        host.awaitMatching("editor", e -> e.has("state"), "the editor did not answer");
        send(host, message("editorEdit", "op", "add", "name", "Black Lotus", "to", "Main", "count", 1));
        host.awaitMatching("notice", e -> true, "a card not owned was not refused");
        final Deck back = savedDeck(data, "Forest deck");
        Assert.assertEquals(back.getMain().countAll(), 40);
        Assert.assertEquals(back.get(DeckSection.Sideboard).count(owned), 1);
        Assert.assertEquals(back.getMain().countByName("Black Lotus"), 0);
    }

    /** A page of one of the quest's lists, as the trade page asks for it. */
    private JsonObject listed(final Recorder host, final String source) throws InterruptedException {
        send(host, message("catalogue", "request", 1, "text", "", "colours", "", "type", "any", "filters", "", "sort", "name",
                "offset", 0, "showAll", true, "source", source));
        return host.awaitMatching("catalogue", c -> source.equals(str(c, "source")), "the " + source + " list was not sent");
    }

    private static JsonObject row(final JsonObject page, final Predicate<JsonObject> wanted) {
        for (final JsonElement e : page.getAsJsonArray("rows")) {
            if (wanted.test(e.getAsJsonObject())) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no such row in " + page);
    }

    private void trade(final Recorder host, final String source, final String key, final int count) {
        final JsonObject pick = new JsonObject();
        pick.addProperty("key", key);
        pick.addProperty("count", count);
        final JsonArray picks = new JsonArray();
        picks.add(pick);
        final JsonObject msg = message("trade", "source", source);
        msg.add("picks", picks);
        send(host, msg);
    }

    private static PaperCard printing(final JsonObject row) {
        return ImageUtil.getPaperCardFromImageKey(row.get("image").getAsString());
    }

    // Fails if a card sells for other than the price the list shows
    @Test(timeOut = 120_000)
    public void aCardSellsForTheListedPrice() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        final JsonObject card = row(listed(host, "inventory"), r -> !"Forest".equals(r.get("name").getAsString()));
        final long credits = FModel.getQuest().getAssets().getCredits();
        trade(host, "inventory", card.get("image").getAsString(), 1);
        host.awaitMatching("trading", t -> true, "the lists were not sent again");
        Assert.assertEquals(FModel.getQuest().getAssets().getCredits(), credits + card.get("value").getAsInt());
        Assert.assertEquals(saved(data).getAssets().getCardPool().count(printing(card)), 0);
    }

    // Fails if a purchase the credits cannot cover changes anything
    @Test(timeOut = 120_000)
    public void aPurchaseTooDearChangesNothing() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        final JsonObject card = row(listed(host, "shop"), r -> r.get("value").getAsInt() > 0);
        FModel.getQuest().getAssets().setCredits(0);
        final PaperCard bought = printing(card);
        final int stock = FModel.getQuest().getCards().getShopList().count(bought);
        trade(host, "shop", card.get("image").getAsString(), 1);
        host.awaitMatching("notice", n -> true, "the purchase was not refused");
        Assert.assertEquals(FModel.getQuest().getAssets().getCredits(), 0);
        Assert.assertEquals(FModel.getQuest().getCards().getShopList().count(bought), stock);
        Assert.assertEquals(FModel.getQuest().getCards().getCardpool().count(bought), data.getAssets().getCardPool().count(bought));
    }

    // Fails if a bought card is not taken from the stock once and added to the pool once
    @Test(timeOut = 120_000)
    public void aBoughtCardMovesOnceFromStockToPool() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        FModel.getQuest().getAssets().setCredits(100_000);
        final JsonObject card = row(listed(host, "shop"), r -> true);
        final PaperCard bought = printing(card);
        final int stock = FModel.getQuest().getCards().getShopList().count(bought);
        final int owned = FModel.getQuest().getCards().getCardpool().count(bought);
        Assert.assertEquals(card.get("count").getAsInt(), stock);
        trade(host, "shop", card.get("image").getAsString(), 1);
        host.awaitMatching("trading", t -> true, "the lists were not sent again");
        Assert.assertEquals(FModel.getQuest().getCards().getShopList().count(bought), stock - 1);
        Assert.assertEquals(saved(data).getAssets().getCardPool().count(bought), owned + 1);
        Assert.assertEquals(saved(data).getAssets().getCredits(), 100_000 - card.get("value").getAsInt());
    }

    // Fails if a card two decks use is sold without leaving both decks
    @Test(timeOut = 120_000)
    public void aCardSoldLeavesEveryDeck() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        final JsonObject card = row(listed(host, "inventory"), r -> !"Forest".equals(r.get("name").getAsString()));
        final PaperCard sold = printing(card);
        FModel.getQuest().getMyDecks().get("Forest deck").getMain().add(sold, 1);
        final Deck second = new Deck("Second deck");
        second.getMain().add(sold, 1);
        FModel.getQuest().getMyDecks().add(second);
        trade(host, "inventory", card.get("image").getAsString(), 1);
        host.awaitMatching("trading", t -> true, "the lists were not sent again");
        Assert.assertEquals(savedDeck(data, "Forest deck").getMain().count(sold), 0);
        Assert.assertEquals(savedDeck(data, "Second deck").getMain().count(sold), 0);
    }

    // Fails if selling one of two copies leaves the lists' sizes as they were, which is what tells the page to read its lists again
    @Test(timeOut = 120_000)
    public void aPartialSaleChangesTheListsSizes() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        final JsonObject card = row(listed(host, "inventory"), r -> !"Forest".equals(r.get("name").getAsString()));
        FModel.getQuest().getCards().getCardpool().add(printing(card), 1);
        send(host, JsonCodec.message("trading"));
        final JsonObject before = host.awaitMatching("trading", t -> true, "the lists were not sent");
        trade(host, "inventory", card.get("key").getAsString(), 1);
        final JsonObject after = host.awaitMatching("trading", t -> true, "the lists were not sent again");
        Assert.assertNotEquals(after.get("lists"), before.get("lists"));
        Assert.assertEquals(FModel.getQuest().getCards().getCardpool().count(printing(card)), 1);
    }

    // Fails if a foil and a plain copy of one printing cannot be told apart, so selling one sells the other or pays the other's price
    @Test(timeOut = 120_000)
    public void aFoilTradesApartFromItsPlainCopy() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        final PaperCard plain = printing(row(listed(host, "inventory"), r -> !"Forest".equals(r.get("name").getAsString())));
        final PaperCard foil = plain.getFoiled();
        FModel.getQuest().getCards().getCardpool().add(foil, 1);
        final JsonObject page = listed(host, "inventory");
        final JsonObject foilRow = row(page, r -> r.get("name").getAsString().equals(plain.getName()) && r.get("key").getAsString().endsWith("foil"));
        Assert.assertNotEquals(foilRow.get("key"), row(page, r -> r.get("name").getAsString().equals(plain.getName())
                && !r.get("key").getAsString().endsWith("foil")).get("key"));
        final long credits = FModel.getQuest().getAssets().getCredits();
        trade(host, "inventory", foilRow.get("key").getAsString(), 1);
        host.awaitMatching("trading", t -> true, "the lists were not sent again");
        Assert.assertEquals(FModel.getQuest().getCards().getCardpool().count(foil), 0);
        Assert.assertEquals(FModel.getQuest().getCards().getCardpool().count(plain), 1);
        Assert.assertEquals(FModel.getQuest().getAssets().getCredits(), credits + foilRow.get("value").getAsInt());
    }

    // Fails if a bought booster's cards are not in the pool and sent as a reward to reveal
    @Test(timeOut = 120_000)
    public void aBoughtBoosterIsRevealed() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        FModel.getQuest().getAssets().setCredits(100_000);
        send(host, JsonCodec.message("trading"));
        final JsonObject trading = host.awaitMatching("trading", t -> t.has("products"), "the products were not sent");
        JsonObject pack = null;
        for (final JsonElement e : trading.getAsJsonArray("products")) {
            if (e.getAsJsonObject().get("kind").getAsString().equals("Booster Pack")) {
                pack = e.getAsJsonObject();
            }
        }
        Assert.assertNotNull(pack, "no booster for sale: " + trading.get("products"));
        final int before = FModel.getQuest().getCards().getCardpool().countAll();
        trade(host, "shop", pack.get("key").getAsString(), 1);
        final JsonObject reward = host.awaitMatching("reward", m -> true, "the booster was not revealed");
        final JsonObject cards = reward.getAsJsonArray("steps").get(0).getAsJsonObject();
        Assert.assertEquals(cards.get("kind").getAsString(), "CARDS");
        final int opened = cards.getAsJsonArray("cards").size();
        Assert.assertTrue(opened > 0);
        Assert.assertEquals(FModel.getQuest().getCards().getCardpool().countAll(), before + opened);
        Assert.assertEquals(FModel.getQuest().getAssets().getCredits(), 100_000 - pack.get("price").getAsInt());
    }

    // Fails if a quest made from the form is not opened and made current, or the form's options are not sent or leave out a world's format or a casual format
    @Test(timeOut = 120_000)
    public void aQuestMadeFromTheFormOpens() throws Exception {
        final Recorder host = onTheShelf();
        send(host, JsonCodec.message("questOptions"));
        final JsonObject options = host.awaitMatching("questOptions", o -> true, "the form's options were not sent");
        Assert.assertTrue(options.getAsJsonArray("worlds").contains(new JsonPrimitive("Main world")));
        Assert.assertEquals(options.getAsJsonArray("difficulties").size(), 4);
        // A world with sets of its own replaces the starting pool, and the casual formats are mobile's whole list, less those of only unselectable sets
        Assert.assertTrue(options.getAsJsonArray("formatWorlds").contains(new JsonPrimitive("Ravnica")));
        Assert.assertFalse(options.getAsJsonArray("formatWorlds").contains(new JsonPrimitive("Main world")));
        Assert.assertTrue(options.getAsJsonArray("casual").contains(new JsonPrimitive("Premodern")));
        Assert.assertFalse(options.getAsJsonArray("casual").contains(new JsonPrimitive("Pre-format (LEB)")));
        final String name = QuestFixture.expected();
        send(host, message("questCreate", "name", name, "difficulty", 1, "fantasy", true, "commander", false, "world", "Main world",
                "pool", "Complete", "poolType", "BALANCED", "colors", "G", "artifacts", false, "completeSet", false, "duplicates", false,
                "boosters", 0, "allowUnlocks", true));
        host.awaitMatching("hello", h -> name.equals(str(h, "campaignSave")), "the new quest was not opened");
        Assert.assertEquals(FModel.getQuestPreferences().getPref(QPref.CURRENT_QUEST), name + ".dat");
        Assert.assertEquals(FModel.getQuest().getName(), name);
        Assert.assertEquals(FModel.getQuest().getAchievements().getDifficulty(), 1);
    }

    // Fails if renaming a quest leaves its old file or the current quest pointing at the old name
    @Test(timeOut = 120_000)
    public void renamingAQuestMovesItsFile() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = onTheShelf();
        FModel.getQuestPreferences().setPref(QPref.CURRENT_QUEST, data.getName() + ".dat");
        final String to = QuestFixture.expected();
        send(host, message("campaignRename", "name", data.getName(), "to", to));
        host.awaitMatching("questSaves", q -> q.toString().contains(to), "the shelf was not sent with the new name");
        Assert.assertNull(QuestGame.find(data.getName()), "the old file is still there");
        Assert.assertEquals(QuestDataIO.loadData(QuestGame.find(to)).getName(), to);
        Assert.assertEquals(FModel.getQuestPreferences().getPref(QPref.CURRENT_QUEST), to + ".dat");
    }

    // Fails if deleting a quest leaves its file or its backup, or the current quest naming it
    @Test(timeOut = 120_000)
    public void deletingAQuestRemovesBothFiles() throws Exception {
        final QuestData data = QuestFixture.install();
        // A second save copies the first to the backup
        data.saveData();
        final File backup = new File(ForgeConstants.QUEST_SAVE_DIR, data.getName() + ".dat.bak");
        Assert.assertTrue(backup.isFile());
        final Recorder host = onTheShelf();
        FModel.getQuestPreferences().setPref(QPref.CURRENT_QUEST, data.getName() + ".dat");
        send(host, message("campaignDelete", "name", data.getName()));
        host.awaitMatching("questSaves", q -> !q.toString().contains(data.getName()), "the shelf was not sent without the quest");
        Assert.assertNull(QuestGame.find(data.getName()));
        Assert.assertFalse(backup.exists());
        Assert.assertNotEquals(FModel.getQuestPreferences().getPref(QPref.CURRENT_QUEST), data.getName() + ".dat");
    }

    /** The fixture with enough wins for challenges to be offered, and one game a match. */
    private static QuestData withChallenges() throws IOException {
        return withChallenges(QuestFixture.install());
    }

    private static QuestData withChallenges(final QuestData data) throws IOException {
        for (int i = 0; i < 40; i++) {
            data.getAchievements().addWin();
        }
        data.setMatchLength(1);
        data.saveData();
        return data;
    }

    // Fails if a challenge's terms are not the challenge file's (life, bounty, starting cards)
    @Test(timeOut = 120_000)
    public void aChallengesTermsAreItsFiles() throws Exception {
        final Recorder host = hostInQuest(withChallenges());
        final JsonObject page = host.awaitNewest("questChallenges", "the challenges were not sent");
        final JsonArray rows = page.getAsJsonArray("challenges");
        Assert.assertTrue(rows.size() > 0, "no challenge is offered after 40 wins");
        Assert.assertEquals(page.get("open").getAsInt(), rows.size());
        for (final JsonElement e : rows) {
            final JsonObject row = e.getAsJsonObject();
            final QuestEventChallenge challenge = FModel.getQuest().getChallenges().get(row.get("id").getAsString());
            Assert.assertEquals(row.get("title").getAsString(), challenge.getTitle());
            Assert.assertEquals(row.get("aiLife").getAsInt(), challenge.getAILife());
            Assert.assertEquals(row.get("credits").getAsInt(), challenge.getCreditsReward());
            final List<String> human = new ArrayList<>();
            row.getAsJsonArray("humanCards").forEach(c -> human.add(c.getAsString()));
            Assert.assertEquals(human, challenge.getHumanExtraCards());
            Assert.assertEquals(row.get("fixedDeck").getAsBoolean(), challenge.getHumanDeck() != null);
        }
    }

    // Fails if a won challenge is not taken off the list and its bounty paid
    @Test(timeOut = 240_000)
    public void aWonChallengeIsPaidAndGone() throws Exception {
        WebTestSupport.skipUnlessStress();
        final QuestData data = withChallenges();
        final Recorder host = hostInQuest(data);
        final JsonObject row = host.awaitNewest("questChallenges", "the challenges were not sent").getAsJsonArray("challenges").get(0).getAsJsonObject();
        final String id = row.get("id").getAsString();
        final long credits = FModel.getQuest().getAssets().getCredits();
        send(host, message("questChallenge", "id", id));
        host.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean(), "the challenge did not start");
        awaitPriority(host);
        computerLoses(host);
        host.awaitMatching("campaignResult", r -> true, "no result was sent");
        sessions.onMessage(host, JsonCodec.message("leave"));
        untilRewarded(host);
        final QuestData after = saved(data);
        Assert.assertFalse(after.getAchievements().getCurrentChallenges().contains(id), "the won challenge is still offered");
        Assert.assertTrue(after.getAssets().getCredits() >= credits + row.get("credits").getAsInt(),
                "the bounty was not paid: " + credits + " then " + after.getAssets().getCredits());
    }

    // Fails if the zeppelin re-rolls the challenges more than once between two matches
    @Test(timeOut = 120_000)
    public void theZeppelinFliesOnceBetweenMatches() throws Exception {
        final QuestData data = withChallenges();
        data.getAssets().setItemLevel(QuestItemType.ZEPPELIN, 1);
        data.saveData();
        final Recorder host = hostInQuest(data);
        final JsonObject before = host.awaitNewest("questChallenges", "the challenges were not sent");
        Assert.assertTrue(before.get("zeppelin").getAsBoolean());
        Assert.assertFalse(before.get("zeppelinUsed").getAsBoolean());
        send(host, JsonCodec.message("questZeppelin"));
        final JsonObject flown = host.awaitMatching("questChallenges", c -> true, "the zeppelin sent no challenges");
        Assert.assertTrue(flown.get("zeppelinUsed").getAsBoolean());
        final List<String> ids = new ArrayList<>(FModel.getQuest().getAchievements().getCurrentChallenges());
        send(host, JsonCodec.message("questZeppelin"));
        host.awaitMatching("questChallenges", c -> true, "a second flight sent no challenges");
        Assert.assertEquals(FModel.getQuest().getAchievements().getCurrentChallenges(), ids, "the zeppelin flew twice");
        Assert.assertEquals(FModel.getQuest().getAssets().getItemLevel(QuestItemType.ZEPPELIN), 2);
    }

    private JsonObject stall(final Recorder host, final String name) throws InterruptedException {
        send(host, message("questStall", "name", name));
        return host.awaitMatching("questBazaar", b -> true, "the bazaar was not sent");
    }

    private static JsonObject item(final JsonObject bazaar, final String name) {
        for (final JsonElement e : bazaar.getAsJsonArray("items")) {
            if (name.equals(e.getAsJsonObject().get("name").getAsString())) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    // Fails if a Classic quest offers no challenges, or offers the zeppelin or the next-challenge line, which desktop hides there
    @Test(timeOut = 120_000)
    public void aClassicQuestOffersChallenges() throws Exception {
        final Recorder host = hostInQuest(withChallenges(QuestFixture.installClassic()));
        final JsonObject page = host.awaitNewest("questChallenges", "the challenges were not sent");
        Assert.assertTrue(page.getAsJsonArray("challenges").size() > 0, "no challenge is offered after 40 wins: " + page);
        Assert.assertFalse(page.get("zeppelin").getAsBoolean());
        Assert.assertFalse(page.has("nextIn") && !page.get("nextIn").isJsonNull(), "the next-challenge line is shown: " + page);
    }

    // Fails if the bazaar is offered in Classic mode
    @Test(timeOut = 120_000)
    public void aClassicQuestHasNoBazaar() throws Exception {
        final Recorder host = hostInQuest(QuestFixture.installClassic());
        Assert.assertEquals(stall(host, null).getAsJsonArray("stalls").size(), 0);
    }

    // Fails if an item costs other than its price, or one bought to its last level stays on its stall
    @Test(timeOut = 120_000)
    public void anItemCostsItsPriceAndLeavesAtItsLastLevel() throws Exception {
        final QuestData data = QuestFixture.install();
        data.getAssets().setCredits(100_000);
        data.saveData();
        final Recorder host = hostInQuest(data);
        final JsonObject bazaar = stall(host, "Bookstore");
        Assert.assertTrue(bazaar.getAsJsonArray("stalls").size() > 0);
        final JsonObject book = item(bazaar, "Sleight of Hand Vol. I");
        Assert.assertNotNull(book, "the book is not for sale: " + bazaar);
        Assert.assertEquals(book.get("maxLevel").getAsInt(), 1);
        send(host, message("questBuy", "stall", "Bookstore", "item", "Sleight of Hand Vol. I"));
        final JsonObject after = host.awaitMatching("questBazaar", b -> true, "the bazaar was not sent again");
        Assert.assertEquals(FModel.getQuest().getAssets().getCredits(), 100_000 - book.get("price").getAsInt());
        Assert.assertTrue(FModel.getQuest().getAssets().hasItem(QuestItemType.SLEIGHT));
        Assert.assertNull(item(after, "Sleight of Hand Vol. I"), "a book bought to its last level is still for sale");
        Assert.assertTrue(saved(data).getAssets().hasItem(QuestItemType.SLEIGHT));
    }

    // Fails if a pet bought is not owned at its first level and offered in the Duels page's pet slot, or one the credits cannot pay for is bought
    @Test(timeOut = 120_000)
    public void aPetBoughtIsOfferedForDuels() throws Exception {
        final QuestData data = QuestFixture.install();
        final Recorder host = hostInQuest(data);
        final JsonObject bird = item(stall(host, "Pet Shop"), "Bird");
        Assert.assertNotNull(bird, "the bird is not for sale");
        FModel.getQuest().getAssets().setCredits(bird.get("price").getAsInt() - 1);
        send(host, message("questBuy", "stall", "Pet Shop", "item", "Bird"));
        host.awaitMatching("notice", n -> true, "a pet too dear was not refused");
        Assert.assertEquals(FModel.getQuest().getAssets().getPetLevel("Bird"), 0);
        FModel.getQuest().getAssets().setCredits(10_000);
        send(host, message("questBuy", "stall", "Pet Shop", "item", "Bird"));
        final JsonObject duels = host.awaitMatching("questDuels", d -> d.toString().contains("Bird"), "the Duels page does not offer the bird");
        Assert.assertEquals(FModel.getQuest().getAssets().getPetLevel("Bird"), 1);
        Assert.assertTrue(duels.getAsJsonArray("pets").toString().contains("Bird"));
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
