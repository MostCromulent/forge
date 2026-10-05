package forge.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.gamemodes.quest.data.QuestData;
import forge.gamemodes.quest.data.QuestPreferences.QPref;
import forge.gui.GuiBase;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;

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

    private static String str(final JsonObject o, final String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : null;
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
