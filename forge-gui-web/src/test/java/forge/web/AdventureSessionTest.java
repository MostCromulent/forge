package forge.web;

import com.google.gson.JsonObject;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.world.WorldSave;
import forge.gui.GuiBase;
import forge.web.adventure.AdventureFixture;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicReference;

/** Tests adventure driven from a browser. */
public class AdventureSessionTest extends SessionsTest {
    @Override
    WebGuiBase gui() {
        return (WebGuiBase) GuiBase.getInterface();
    }

    @BeforeClass
    public void boot() {
        AdventureFixture.boot();
    }

    /** Connects the host and opens the list of adventure saves. */
    TestBrowser onTheShelf() throws InterruptedException {
        final TestBrowser host = connect("host");
        sessions.onMessage(host, TestBrowser.action("claimHost"));
        host.awaitMatching("hello", h -> h.get("host").getAsBoolean(), "the host's seat was not given");
        send(host, message("campaignOpen", "mode", "adventure", "resume", false));
        host.awaitMatching("hello", h -> "adventure".equals(str(h, "campaign")) && str(h, "campaignSave") == null, "the shelf did not open");
        host.awaitMatching("adventureSaves", s -> true, "the saves were not listed");
        return host;
    }

    TestBrowser inNewGame() throws InterruptedException {
        final TestBrowser host = onTheShelf();
        send(host, message("adventureCreate", "name", "Tester", "female", false, "race", 0, "avatar", 0, "colors", "W", "difficulty", 1,
                "mode", "Commander", "customDeck", 0, "seed", 42));
        host.awaitMatching("hello", h -> str(h, "campaignSave") != null, "the new game did not open");
        return host;
    }

    @AfterMethod(alwaysRun = true)
    public void leave() {
        for (final TestBrowser browser : browsers) {
            if (browser.hello != null && browser.hello.get("host").getAsBoolean()) {
                send(browser, message("campaignLeave"));
            }
        }
    }

    // Fails if a new game does not build the world, name the player and give the plane's main quest as NewGameScene does
    @Test(timeOut = 120_000)
    public void newGameCreatesWorldAndAddsQuest28() throws InterruptedException {
        inNewGame();
        final AtomicReference<String> name = new AtomicReference<>();
        final AtomicReference<Boolean> quest = new AtomicReference<>();
        onUi(() -> {
            name.set(AdventurePlayer.current().getName());
            quest.set(AdventurePlayer.current().getQuests().stream().anyMatch(q -> q.getID() == 28));
        });
        Assert.assertEquals(name.get(), "Tester");
        Assert.assertTrue(quest.get());
    }

    // Fails if a save made in the browser cannot be opened again with the same player
    @Test(timeOut = 120_000)
    public void saveAndReopen() throws InterruptedException {
        final TestBrowser host = inNewGame();
        final AtomicReference<float[]> before = new AtomicReference<>();
        onUi(() -> before.set(new float[] {AdventurePlayer.current().getWorldPosX(), AdventurePlayer.current().getGold()}));
        send(host, message("adventureSaveTo", "slot", 3));
        host.awaitMatching("notice", n -> true, "saving was not confirmed");
        send(host, message("campaignLeave"));
        host.awaitMatching("adventureSaves", s -> s.getAsJsonArray("saves").toString().contains(WorldSave.filename(3)), "slot 3 was not listed");
        onUi(() -> AdventurePlayer.current().setWorldPosX(0));
        send(host, message("campaignLoad", "name", WorldSave.filename(3)));
        final JsonObject hello = host.awaitMatching("hello", h -> WorldSave.filename(3).equals(str(h, "campaignSave")), "slot 3 did not open");
        Assert.assertNotNull(hello);
        final AtomicReference<float[]> after = new AtomicReference<>();
        onUi(() -> after.set(new float[] {AdventurePlayer.current().getWorldPosX(), AdventurePlayer.current().getGold()}));
        Assert.assertEquals(after.get(), before.get());
    }
}
