package forge.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RogueStats;
import forge.gamemodes.rogue.RogueTutorial;
import forge.gamemodes.rogue.effect.AetherEffect;
import forge.gamemodes.rogue.path.NodeSanctum;
import forge.localinstance.properties.ForgePreferences;
import forge.web.campaign.rogue.RogueGame;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.function.Consumer;

/** Tests the pages between runs as a browser uses them: building and switching Aetherworks, and the resets. */
@Test(singleThreaded = true)
public class RogueBetweenRunsTest extends RogueSessionTest {
    /** Opens the runs page over the fixture's saved run, with one run completed so the Aether is open, and the progress set up as given. */
    private TestBrowser hub(final Consumer<RogueMetaProgress> setUp) throws InterruptedException {
        run = RogueFixture.run(NodeSanctum.class);
        final RogueMetaProgress progress = RogueMetaProgress.getInstance();
        progress.updateStat(RogueStats.RUNS_COMPLETED, 1, run);
        setUp.accept(progress);
        progress.save();
        final TestBrowser host = seat("host");
        send(host, message("campaignOpen", "mode", RogueGame.MODE, "resume", false));
        host.awaitMatching("hello", h -> RogueGame.MODE.equals(str(h, "campaign")), "Rogue did not open");
        settle(host);
        return host;
    }

    /** Sends a command made from the latest pages and waits for the pages its job publishes. */
    private void command(final TestBrowser host, final JsonObject command) throws InterruptedException {
        final long before = snapshot(host);
        command.addProperty("snapshot", before);
        sessions.onMessage(host, command);
        host.awaitMatching("rogueHome", h -> h.get("snapshot").getAsLong() > before, "the command published no pages");
        awaitTrue(() -> game().mayLeave(null), "the command's job never finished");
    }

    private static JsonObject work(final TestBrowser host, final AetherEffect work) {
        for (final JsonElement w : host.last("rogueAether").getAsJsonArray("works")) {
            if (work.name().equals(str(w.getAsJsonObject(), "id"))) {
                return w.getAsJsonObject();
            }
        }
        throw new AssertionError(work + " is not on the page");
    }

    private static void build(final RogueMetaProgress progress, final AetherEffect... works) {
        for (final AetherEffect work : works) {
            Assert.assertTrue(progress.upgradeAetherEffect(work));
            progress.activateAetherEffect(work);
        }
    }

    // Fails if a rank the Echoes do not pay for is built, a build does not spend its price, or a first build with Aether Energy to spare is not switched on
    @Test(timeOut = 120_000)
    public void anUpgradeSpendsItsEchoes() throws Exception {
        final TestBrowser host = hub(p -> p.setTotalEchoes(1));
        Assert.assertFalse(work(host, AetherEffect.ZHALFIRIN_SANCTUARY).get("afford").getAsBoolean());
        command(host, message("rogueAetherUpgrade", "work", AetherEffect.ZHALFIRIN_SANCTUARY.name()));
        final RogueMetaProgress progress = RogueMetaProgress.getInstance();
        Assert.assertEquals(progress.getAetherEffectRank(AetherEffect.ZHALFIRIN_SANCTUARY), 0);
        Assert.assertEquals(progress.getTotalEchoes(), 1);

        progress.setTotalEchoes(10);
        command(host, message("rogueAetherUpgrade", "work", AetherEffect.ZHALFIRIN_SANCTUARY.name()));
        Assert.assertEquals(progress.getAetherEffectRank(AetherEffect.ZHALFIRIN_SANCTUARY), 1);
        Assert.assertEquals(progress.getTotalEchoes(), 10 - AetherEffect.ZHALFIRIN_SANCTUARY.getEchoCostForRank(1));
        Assert.assertTrue(work(host, AetherEffect.ZHALFIRIN_SANCTUARY).get("on").getAsBoolean(), "the page does not show the new work switched on");
    }

    // Fails if a work built while Aether Energy is full is switched on, by its build or by a toggle
    @Test(timeOut = 120_000)
    public void aFourthWorkIsBuiltButNotSwitchedOnWhenFull() throws Exception {
        final TestBrowser host = hub(p -> {
            p.setTotalEchoes(100);
            build(p, AetherEffect.ZHALFIRIN_SANCTUARY, AetherEffect.AETHER_EXCHANGE, AetherEffect.RESTORATION_CHAMBER);
        });
        command(host, message("rogueAetherUpgrade", "work", AetherEffect.SPECTRAL_EXPANDER.name()));
        command(host, message("rogueAetherToggle", "work", AetherEffect.SPECTRAL_EXPANDER.name(), "on", true));
        final RogueMetaProgress progress = RogueMetaProgress.getInstance();
        Assert.assertEquals(progress.getAetherEffectRank(AetherEffect.SPECTRAL_EXPANDER), 1);
        Assert.assertFalse(progress.getActiveAetherEffects().contains(AetherEffect.SPECTRAL_EXPANDER));
        Assert.assertEquals(progress.getActiveAetherEffectCount(), 3);
        Assert.assertFalse(work(host, AetherEffect.SPECTRAL_EXPANDER).get("toggle").getAsBoolean(), "the page offers to switch it on");
    }

    // Fails if resetting the Aetherworks keeps a rank, misses Echoes spent on one, takes back Sparks or an Aether Upgrade, or does not say what it refunded
    @Test(timeOut = 120_000)
    public void resetRefundsEchoesOnly() throws Exception {
        final TestBrowser host = hub(p -> {
            p.setTotalEchoes(100);
            p.addSparks(5);
            p.setAetherUpgradeLevel(1);
            build(p, AetherEffect.ZHALFIRIN_SANCTUARY, AetherEffect.AETHER_EXCHANGE);
            Assert.assertTrue(p.upgradeAetherEffect(AetherEffect.ZHALFIRIN_SANCTUARY));
        });
        command(host, message("rogueReset", "kind", "aether"));
        final int refunded = AetherEffect.ZHALFIRIN_SANCTUARY.getEchoCostForRank(1) + AetherEffect.ZHALFIRIN_SANCTUARY.getEchoCostForRank(2)
                + AetherEffect.AETHER_EXCHANGE.getEchoCostForRank(1);
        host.awaitMatching("rogueQuestion", q -> q.has("tell") && q.getAsJsonObject("tell").get("text").toString().contains(" " + refunded + " "),
                "the refund was not told");
        final RogueMetaProgress progress = RogueMetaProgress.getInstance();
        Assert.assertEquals(progress.getTotalEchoes(), 100);
        Assert.assertEquals(progress.getTotalSparks(), 5);
        Assert.assertEquals(progress.getAetherUpgradeLevel(), 1);
        Assert.assertEquals(progress.getAetherEffectRank(AetherEffect.ZHALFIRIN_SANCTUARY), 0);
        Assert.assertEquals(progress.getActiveAetherEffectCount(), 0);
    }

    // Fails if resetting the progress deletes the unfinished run or the tutorials seen, or keeps what it should reset
    @Test(timeOut = 120_000)
    public void resetProgressKeepsTheRunAndTheTutorials() throws Exception {
        final TestBrowser host = hub(p -> {
            p.setTotalEchoes(40);
            p.markTutorialSeen(RogueTutorial.AETHER);
        });
        command(host, message("rogueReset", "kind", "progress"));
        final RogueMetaProgress progress = RogueMetaProgress.getInstance();
        Assert.assertEquals(progress.getTotalEchoes(), 0);
        Assert.assertEquals(progress.getTotalRunsCompleted(), 0);
        Assert.assertTrue(progress.hasSeenTutorial(RogueTutorial.AETHER));
        Assert.assertEquals(RogueIO.loadMostRecentContinuableRun().orElseThrow(AssertionError::new).getCurrentNodeIndex(), run.getCurrentNodeIndex());
        Assert.assertFalse(host.last("rogueHome").get("unlocked").getAsBoolean(), "the Codex is still open");
    }

    // Fails if a dev tool changes the progress while dev mode is off, or does nothing while it is on
    @Test(timeOut = 120_000)
    public void devCommandsAreRefusedOutsideDevMode() throws Exception {
        final TestBrowser host = hub(p -> p.setTotalEchoes(5));
        command(host, message("rogueDev", "tool", "echoes"));
        command(host, message("rogueDev", "tool", "unlockAll"));
        Assert.assertEquals(RogueMetaProgress.getInstance().getTotalEchoes(), 5);
        Assert.assertFalse(RogueMetaProgress.getInstance().isDevUnlockAll());
        ForgePreferences.DEV_MODE = true;
        command(host, message("rogueDev", "tool", "echoes"));
        command(host, message("rogueDev", "tool", "unlockAll"));
        Assert.assertEquals(RogueMetaProgress.getInstance().getTotalEchoes(), 15);
        Assert.assertTrue(host.last("rogueHome").get("devUnlockAll").getAsBoolean(), "the page does not show every commander unlocked");
    }
}
