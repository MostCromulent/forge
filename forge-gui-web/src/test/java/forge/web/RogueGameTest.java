package forge.web;

import com.google.gson.JsonObject;
import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RoguePlanebound;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueStats;
import forge.gamemodes.rogue.effect.AetherEffect;
import forge.gamemodes.rogue.effect.ChestEffect;
import forge.gamemodes.rogue.flow.RogueDecisions.Pick;
import forge.gamemodes.rogue.path.NodeChest;
import forge.gamemodes.rogue.path.NodePlanebound;
import forge.gamemodes.rogue.path.RoguePathNode;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.skin.FSkinProp;
import forge.model.FModel;
import forge.web.WebSession.InCampaign;
import forge.web.campaign.Campaign;
import forge.web.campaign.CampaignSession;
import forge.web.campaign.rogue.RogueGame;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Tests how Rogue's game runs the flow on its own thread, one job at a time, and publishes its pages. The flow's questions are answered from a script. */
@Test(singleThreaded = true)
public class RogueGameTest extends WebHubTest {
    private ScriptedDecisions decisions;
    private RogueGame game;
    private WebSession session;
    private final List<CountDownLatch> latches = new ArrayList<>();

    @Override
    void afterDisconnecting() {
        RogueFixture.clean();
    }

    /** Rogue's progress announces through Forge's own GUI, so the sessions run on it. */
    @Override
    WebGuiBase gui() {
        return (WebGuiBase) GuiBase.getInterface();
    }

    @BeforeMethod
    public void freshScript() {
        RogueFixture.clean();
        ForgePreferences.DEV_MODE = false;
        decisions = new ScriptedDecisions();
    }

    /** A job a test left waiting would hold the flow thread of its game for ever. */
    @AfterMethod(alwaysRun = true)
    public void releaseJobs() {
        latches.forEach(CountDownLatch::countDown);
        latches.clear();
    }

    private CountDownLatch latch() {
        final CountDownLatch latch = new CountDownLatch(1);
        latches.add(latch);
        return latch;
    }

    private static void await(final CountDownLatch latch) {
        try {
            Assert.assertTrue(latch.await(60, TimeUnit.SECONDS), "the test never released the job");
        } catch (final InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object read(final Object from, final String field) throws ReflectiveOperationException {
        final Field f = from.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(from);
    }

    /** Connects the host with a new game in its table of modes, in place of the server's own, and opens it. */
    @SuppressWarnings("unchecked")
    private TestBrowser open(final boolean run) throws Exception {
        game = new RogueGame(g -> decisions);
        awaitIdle();
        final TestBrowser host = connect("host");
        sessions.onMessage(host, TestBrowser.action("claimHost"));
        sessions.onMessage(host, message("setName", "name", "Host"));
        session = (WebSession) read(sessions, "host");
        final Campaigns campaigns = (Campaigns) read(session, "campaigns");
        ((Map<String, Campaign>) read(campaigns, "modes")).put(RogueGame.MODE, game);
        campaigns.becameHost();
        send(host, message("campaignOpen", "mode", RogueGame.MODE, "resume", run));
        host.awaitMatching("hello", h -> RogueGame.MODE.equals(str(h, "campaign"))
                && (run ? RogueGame.RUN.equals(str(h, "campaignSave")) : str(h, "campaignSave") == null), "Rogue did not open");
        host.awaitMatching(run ? "rogueMap" : "rogueHome", m -> true, "the pages were not sent");
        // Opening publishes again as its job ends, and commands are checked against that last snapshot
        awaitIdle();
        return host;
    }

    private TestBrowser openRun(final Class<? extends RoguePathNode> at) throws Exception {
        RogueFixture.run(at);
        return open(true);
    }

    private void awaitIdle() {
        awaitTrue(() -> game.mayLeave(null), "the game's job never finished");
    }

    private static long snapshot(final TestBrowser host) {
        return host.last("rogueHome").get("snapshot").getAsLong();
    }

    private static RogueRun saved() {
        return RogueIO.loadMostRecentContinuableRun().orElseThrow(AssertionError::new);
    }

    private long asked(final String question) {
        return decisions.asked.stream().filter(question::equals).count();
    }

    private void campaignThreadDone() throws ReflectiveOperationException {
        final CountDownLatch done = new CountDownLatch(1);
        ((CampaignSession) read(session, "campaigns")).run(done::countDown);
        await(done);
    }

    /** The runs page opens with a first story in a new profile. */
    private void betweenRuns() {
        decisions.then("chooseBoon", new Pick<>(false, null));
    }

    private static Function<Object[], Object> loot(final CountDownLatch release) {
        return args -> {
            await(release);
            return new Pick<>(false, ChestEffect.BANNER_OF_CHAMPIONS);
        };
    }

    // Fails if a double click on Enter runs the node twice, or the second Enter is queued behind the first
    @Test(timeOut = 120_000)
    public void aSecondEnterWhileBusyIsRefused() throws Exception {
        final CountDownLatch release = latch();
        decisions.then("chooseChest", loot(release)).then("chooseChest", loot(release));
        final TestBrowser host = openRun(NodeChest.class);
        final JsonObject enter = message("rogueEnter", "snapshot", snapshot(host), "node", 9);
        sessions.onMessage(host, enter);
        sessions.onMessage(host, enter);
        awaitTrue(() -> asked("chooseChest") == 1, "the chest was not opened");
        Assert.assertEquals(saved().getCurrentNodeIndex(), 9);
        release.countDown();
        awaitIdle();
        Assert.assertEquals(asked("chooseChest"), 1, "the second Enter opened the chest again");
        Assert.assertTrue(saved().getPath().getNode(9).isCompleted());
    }

    // Fails if a command made from a page the player no longer sees runs against the newer state
    @Test(timeOut = 120_000)
    public void aCommandFromAnOldSnapshotIsRefused() throws Exception {
        final RogueRun run = RogueFixture.run(NodePlanebound.class);
        final RogueMetaProgress progress = RogueMetaProgress.getInstance();
        progress.setAetherUpgradeLevel(3);
        progress.setTotalEchoes(100);
        Assert.assertTrue(progress.upgradeAetherEffect(AetherEffect.PLANEBENDER));
        progress.activateAetherEffect(AetherEffect.PLANEBENDER);
        progress.save();
        run.snapshotAetherEffects(progress);
        RogueIO.saveRun(run);
        final RoguePlanebound before = ((NodePlanebound) run.getCurrentNode()).getRoguePlanebound();
        final TestBrowser host = open(true);

        final long old = snapshot(host);
        sessions.onMessage(host, message("rogueSelect", "snapshot", old, "node", 5));
        host.awaitMatching("rogueHome", h -> h.get("snapshot").getAsLong() > old, "the selection published no pages");
        awaitIdle();
        final long now = snapshot(host);
        sessions.onMessage(host, message("rogueReroll", "snapshot", old, "node", 5));
        awaitIdle();
        Assert.assertEquals(((NodePlanebound) saved().getCurrentNode()).getRoguePlanebound(), before, "a reroll from the old page ran");
        Assert.assertEquals(snapshot(host), now);

        sessions.onMessage(host, message("rogueReroll", "snapshot", now, "node", 5));
        awaitTrue(() -> !before.equals(((NodePlanebound) saved().getCurrentNode()).getRoguePlanebound()), "a reroll from the current page was refused");
    }

    // Fails if Back leaves a run while a job is part-way through it
    @Test(timeOut = 120_000)
    public void backIsRefusedWhileAJobRuns() throws Exception {
        betweenRuns();
        final TestBrowser host = openRun(NodeChest.class);
        final CountDownLatch release = latch();
        Assert.assertTrue(game.submit(snapshot(host), -1, () -> await(release)));
        send(host, TestBrowser.action("campaignLeave"));
        campaignThreadDone();
        Assert.assertEquals(session.stage(), new InCampaign(RogueGame.MODE, RogueGame.RUN));

        release.countDown();
        awaitIdle();
        send(host, TestBrowser.action("campaignLeave"));
        host.awaitMatching("hello", h -> str(h, "campaignSave") == null, "Back was refused once the job had ended");
    }

    // Fails if a step that fails keeps its half-done run, or leaves the game refusing every command
    @Test(timeOut = 120_000)
    public void anErrorInAJobLoadsTheLastSave() throws Exception {
        decisions.then("chooseChest", args -> {
            ((RogueRun) args[1]).setCurrentGold(99);
            throw new AssertionError("a scripted failure");
        }).then("chooseChest", args -> new Pick<>(false, ChestEffect.BANNER_OF_CHAMPIONS));
        final TestBrowser host = openRun(NodeChest.class);
        final long before = snapshot(host);
        sessions.onMessage(host, message("rogueEnter", "snapshot", before, "node", 9));
        host.awaitMatching("error", e -> str(e, "message").contains("a scripted failure"), "the failure was not shown");
        final JsonObject home = host.awaitMatching("rogueHome", h -> h.get("snapshot").getAsLong() > before, "no pages followed the failure");
        awaitIdle();
        // The fixture's run has 8 gold
        Assert.assertEquals(home.getAsJsonObject("run").get("gold").getAsInt(), 8);

        sessions.onMessage(host, message("rogueEnter", "snapshot", snapshot(host), "node", 9));
        awaitTrue(() -> asked("chooseChest") == 2, "the next command was refused");
        awaitIdle();
        Assert.assertTrue(saved().getPath().getNode(9).isCompleted());
    }

    // Fails if a reload's pages can reach the browser after the pages of a newer step
    @Test(timeOut = 120_000)
    public void anOlderSnapshotIsNeverSentAfterANewer() throws Exception {
        betweenRuns();
        final TestBrowser host = open(false);
        final Thread steps = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                sessions.onMessage(host, message("rogueSelect", "snapshot", snapshot(host), "node", 0));
            }
        });
        final Thread reloads = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                game.home();
            }
        });
        steps.start();
        reloads.start();
        steps.join();
        reloads.join();
        awaitIdle();
        long last = 0;
        int rises = 0;
        for (final JsonObject home : host.all("rogueHome")) {
            final long number = home.get("snapshot").getAsLong();
            Assert.assertTrue(number >= last, "snapshot " + number + " arrived after " + last);
            rises += number > last ? 1 : 0;
            last = number;
        }
        Assert.assertTrue(rises > 10, "too few steps ran to race the reloads: " + rises);
    }

    // Fails if opening Rogue during a question replaces the progress the question's step is changing, or opening when idle does not read it again
    @Test(timeOut = 120_000)
    public void openingReadsProgressOnlyWhileIdle() throws Exception {
        final CountDownLatch release = latch();
        decisions.then("chooseChest", loot(release));
        final TestBrowser host = openRun(NodeChest.class);
        sessions.onMessage(host, message("rogueEnter", "snapshot", snapshot(host), "node", 9));
        awaitTrue(() -> asked("chooseChest") == 1, "the chest was not opened");
        final RogueMetaProgress inMemory = RogueMetaProgress.getInstance();
        final int echoes = inMemory.getTotalEchoes();
        inMemory.setTotalEchoes(echoes + 77);
        inMemory.save();
        inMemory.setTotalEchoes(echoes);

        final CountDownLatch opened = new CountDownLatch(1);
        game.opening(RogueGame.RUN, opened::countDown);
        await(opened);
        Assert.assertSame(RogueMetaProgress.getInstance(), inMemory);
        Assert.assertEquals(RogueMetaProgress.getInstance().getTotalEchoes(), echoes);

        release.countDown();
        awaitIdle();
        // Completing the chest saved the progress over the change
        RogueMetaProgress.getInstance().setTotalEchoes(echoes + 77);
        RogueMetaProgress.getInstance().save();
        RogueMetaProgress.getInstance().setTotalEchoes(echoes);
        final CountDownLatch reopened = new CountDownLatch(1);
        game.opening(RogueGame.RUN, reopened::countDown);
        await(reopened);
        Assert.assertEquals(RogueMetaProgress.getInstance().getTotalEchoes(), echoes + 77);
    }

    // Fails if the start page's hello reads the saves, which is slow and may fail
    @Test(timeOut = 120_000)
    public void helloReadsNoFiles() throws Exception {
        openRun(NodeChest.class);
        final Path dir = Path.of(RogueIO.ROGUE_SAVE_DIR);
        final Path away = dir.resolveSibling(dir.getFileName() + "-away");
        Files.move(dir, away);
        try {
            Files.writeString(dir, "not a folder");
            Assert.assertEquals(game.current(), RogueGame.RUN);
            final TestBrowser again = connect("host");
            Assert.assertEquals(str(again.hello.getAsJsonObject("campaignResume"), RogueGame.MODE), RogueGame.RUN);
        } finally {
            Files.delete(dir);
            Files.move(away, dir);
        }
    }

    // Fails if the run's time keeps counting with nobody there, or a step that ends after the browser left starts it again
    @Test(timeOut = 120_000)
    public void theTimerStopsWhenTheBrowserGoes() throws Exception {
        final CountDownLatch release = latch();
        final AtomicReference<RogueRun> inMemory = new AtomicReference<>();
        decisions.then("chooseChest", args -> {
            inMemory.set((RogueRun) args[1]);
            return loot(release).apply(args);
        });
        final TestBrowser host = openRun(NodeChest.class);
        sessions.onMessage(host, message("rogueEnter", "snapshot", snapshot(host), "node", 9));
        awaitTrue(() -> inMemory.get() != null, "the chest was not opened");
        Assert.assertTrue(inMemory.get().getRunTimer().isRunning(), "the timer did not run on the run's pages");

        sessions.disconnected(host);
        final long elapsed = inMemory.get().getRunTimer().getElapsedMillis();
        Thread.sleep(50);
        Assert.assertEquals(inMemory.get().getRunTimer().getElapsedMillis(), elapsed);
        release.countDown();
        awaitIdle();
        Thread.sleep(50);
        Assert.assertFalse(inMemory.get().getRunTimer().isRunning(), "the finished step started the timer");
        Assert.assertEquals(inMemory.get().getRunTimer().getElapsedMillis(), elapsed);
    }

    private static List<String> announced(final TestBrowser host, final String field) {
        return host.all("rogueAnnounce").stream().map(a -> str(a, field)).collect(Collectors.toList());
    }

    // Fails if an unlock Forge announces while Rogue's flow runs never reaches the page, or arrives without its card
    @Test(timeOut = 120_000)
    public void anUnlockIsAnnounced() throws Exception {
        final TestBrowser host = openRun(NodeChest.class);
        final RogueRun run = saved();
        Assert.assertTrue(game.submit(snapshot(host), -1, () -> RogueMetaProgress.getInstance().updateStat(RogueStats.NONBASIC_LANDS, 15, run)));
        final String muldrotha = FModel.getMagicDb().getCommonCards().getCard("Muldrotha, the Gravetide").getImageKey(false);
        host.awaitMatching("rogueAnnounce", a -> muldrotha.equals(str(a, "image")), "Muldrotha's unlock was not announced");
        awaitIdle();
        Assert.assertEquals(announced(host, "image").stream().filter(muldrotha::equals).count(), 1);
    }

    // Fails if another mode's trophy, announced while Rogue's flow is idle, is shown on Rogue's pages
    @Test(timeOut = 120_000)
    public void anotherModesTrophyIsNotAnnounced() throws Exception {
        final TestBrowser host = openRun(NodeChest.class);
        final PaperCard card = FModel.getMagicDb().getCommonCards().getCard("Lightning Helix");
        GuiBase.getInterface().showImageDialog(GuiBase.getInterface().createLayeredImage(card, FSkinProp.IMG_SPECIAL_TROPHY, "quest.png", 1f),
                "A Quest trophy", "Achievement Earned");
        Assert.assertTrue(game.submit(snapshot(host), -1, () -> GuiBase.getInterface().showImageDialog(null, "Rogue's own", "Rogue")));
        host.awaitMatching("rogueAnnounce", a -> "Rogue".equals(str(a, "title")), "Rogue's own announcement did not arrive");
        Assert.assertEquals(announced(host, "title"), List.of("Rogue"));
    }
}
