package forge.web;

import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.PreparedMatch;
import forge.player.GamePlayerUtil;
import forge.player.LobbyPlayerHuman;
import forge.web.campaign.Campaign;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.CampaignProtocol.CampaignResult;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests how a campaign mode starts and leaves a match, with a mode that has no pages. */
public class CampaignMatchTest extends WebHubTest {
    private static final String MODE = "stub";
    private static final String SAVE = "run";

    private final class StubMode implements Campaign {
        private Host host;
        private final AtomicInteger gameOvers = new AtomicInteger();
        private final List<String> leaving = new CopyOnWriteArrayList<>();
        private volatile boolean stays;
        private volatile boolean keepsLooks;

        @Override
        public void becameHost(final Host h) {
            host = h;
        }

        @Override
        public boolean handles(final Record command) {
            return false;
        }

        @Override
        public boolean mayLeave(final String save) {
            return !stays;
        }

        @Override
        public List<Record> home() {
            return List.of();
        }

        @Override
        public String current() {
            return null;
        }

        @Override
        public String open(final String save) {
            return save;
        }

        @Override
        public List<Record> page() {
            return List.of();
        }

        @Override
        public boolean oneGame() {
            return true;
        }

        @Override
        public boolean keepsLooks() {
            return keepsLooks;
        }

        @Override
        public CampaignResult gameOver(final GameView hostGame) {
            gameOvers.incrementAndGet();
            return new CampaignResult(false, true, null, null, List.of());
        }

        @Override
        public void leavingMatch() {
            leaving.add("leaving, table " + (sessions.hostLobby() != null));
        }

        @Override
        public void left(final String choice) {
            leaving.add("left for " + choice + ", table " + (sessions.hostLobby() != null));
        }

        @Override
        public void handle(final BrowserChannel channel, final Record command, final String save, final Host h) {
        }
    }

    private final StubMode mode = new StubMode();
    private WebSession session;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger games = new AtomicInteger();

    @BeforeClass
    public void addModes() throws Exception {
        final TestBrowser host = connect("host");
        sessions.onMessage(host, TestBrowser.action("claimHost"));
        sessions.onMessage(host, message("setName", "name", "Host"));
        session = sessions.host;
        session.addCampaign(MODE, mode);
        session.addCampaign("other", new StubMode());
    }

    @AfterMethod(alwaysRun = true)
    public void leaveTheMode() throws InterruptedException {
        mode.stays = false;
        mode.keepsLooks = false;
        final TestBrowser host = connect("host");
        if ("playing".equals(host.hello.get("screen").getAsString())) {
            send(host, TestBrowser.action("leave"));
            host.awaitMatching("hello", h -> !"playing".equals(h.get("screen").getAsString()), "the match was not left");
        }
        while (str(host.hello, "campaign") != null) {
            send(host, TestBrowser.action("campaignLeave"));
            host.awaitMatching("hello", h -> true, "the mode was not left");
        }
        mode.gameOvers.set(0);
        mode.leaving.clear();
        failures.set(0);
        games.set(0);
    }

    private TestBrowser hostIn(final String opened) throws InterruptedException {
        final TestBrowser host = connect("host");
        sessions.onMessage(host, message("campaignOpen", "mode", opened, "resume", false));
        host.awaitMatching("hello", h -> opened.equals(str(h, "campaign")), "the mode did not open");
        send(host, message("campaignLoad", "name", SAVE));
        host.awaitMatching("hello", h -> SAVE.equals(str(h, "campaignSave")), "the save did not open");
        return host;
    }

    private void startMatch() {
        mode.host.startMatch(MODE, SAVE, () -> {
            final LobbyPlayer counted = new LobbyPlayerHuman("Nobody") {
                @Override
                public Player createIngamePlayer(final Game game, final int id) {
                    games.incrementAndGet();
                    return super.createIngamePlayer(game, id);
                }
            };
            counted.setAvatarIndex(7);
            counted.setSleeveIndex(3);
            final RegisteredPlayer human = new RegisteredPlayer(TestDecks.of("Human", "Forest", 60)).setPlayer(counted);
            final RegisteredPlayer computer = new RegisteredPlayer(TestDecks.of("Computer", "Forest", 60)).setPlayer(GamePlayerUtil.createAiPlayer("Computer", -1, -1));
            return new PreparedMatch(new GameRules(GameType.Constructed), Set.of(), List.of(human, computer), human);
        }, failures::incrementAndGet);
    }

    // Fails if a match starts outside the mode that asked for it, such as from the menu or another mode, or the mode that asked is not told
    @Test(timeOut = 120_000)
    public void aMatchDoesNotStartFromAnotherModesStage() throws Exception {
        Assert.assertEquals(session.stage(), new WebSession.Menu());
        startMatch();
        Assert.assertEquals(failures.get(), 1);
        Assert.assertNull(sessions.hostLobby());

        hostIn("other");
        final WebSession.Stage before = session.stage();
        startMatch();
        Assert.assertEquals(failures.get(), 2);
        Assert.assertSame(session.stage(), before);
        Assert.assertNull(sessions.hostLobby());
    }

    // Fails if a match that cannot be built, or whose undoing fails as well, leaves the player in no match, or is undone twice or before the player is back
    @Test(timeOut = 120_000)
    public void aMatchThatCannotBeBuiltGoesBackToItsMode() throws Exception {
        final TestBrowser host = hostIn(MODE);
        final WebSession.Stage before = session.stage();
        final List<WebSession.Stage> undone = new CopyOnWriteArrayList<>();
        Assert.assertThrows(RuntimeException.class, () -> mode.host.startMatch(MODE, SAVE, () -> {
            throw new NoClassDefFoundError("a card");
        }, () -> {
            undone.add(session.stage());
            throw new IllegalStateException("cannot be undone");
        }));
        Assert.assertEquals(undone, List.of(before));
        Assert.assertEquals(session.stage(), before);
        host.awaitMatching("error", e -> str(e, "message").contains("a card"), "the player was not told");
    }

    // Fails if a mode that does not let its save be left loses it to its own home, another save, another mode or the start page
    @Test(timeOut = 120_000)
    public void aSaveThatMayNotBeLeftStaysOpen() throws Exception {
        final TestBrowser host = hostIn(MODE);
        final WebSession.Stage before = session.stage();
        mode.stays = true;
        for (final JsonObject command : List.of(message("campaignOpen", "mode", MODE, "resume", false), message("campaignLoad", "name", "another"),
                message("campaignOpen", "mode", "other", "resume", false), TestBrowser.action("campaignLeave"))) {
            sessions.onMessage(host, command);
            awaitCampaignThread();
            Assert.assertSame(session.stage(), before, command.toString());
        }
    }

    /** Waits for the campaign work queued so far. */
    private void awaitCampaignThread() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        mode.host.run(done::countDown);
        done.await();
    }

    // Fails if the human plays with the player's own avatar and sleeve, or under a name the board cannot find the seat by
    @Test(timeOut = 180_000)
    public void theHumanWearsTheLooksOfTheMatch() throws Exception {
        WebTestSupport.skipUnlessStress();
        hostIn(MODE);
        mode.keepsLooks = true;
        startMatch();
        Assert.assertEquals(failures.get(), 0);
        final Player human = sessions.hostLobby().getHostedMatch().getGame().getPlayers().stream().filter(WebTestSupport::isWebSeat).findFirst().orElseThrow();
        Assert.assertEquals(human.getView().getAvatarIndex(), 7);
        Assert.assertEquals(human.getView().getSleeveIndex(), 3);
        Assert.assertEquals(human.getName(), "Host");
    }

    // Fails if the browser can play on in a match whose one game has ended, which the engine allows
    @Test(timeOut = 180_000)
    public void aSecondGameIsRefusedInAOneGameMatch() throws Exception {
        WebTestSupport.skipUnlessStress();
        final TestBrowser host = hostIn(MODE);
        startMatch();
        awaitPriority(host);
        sessions.onMessage(host, TestBrowser.action("concede"));
        host.awaitMatching("campaignResult", r -> true, "no result was sent");
        sessions.onMessage(host, message("nextGame", "decision", "CONTINUE"));
        sessions.onMessage(host, message("nextGame", "decision", "NEW"));
        // Sent the same way and allowed, so once it has ended the match the other two have been handled
        sessions.onMessage(host, message("nextGame", "decision", "QUIT"));
        awaitTrue(() -> sessions.hostLobby().getHostedMatch() == null, "the match did not end");
        Assert.assertEquals(games.get(), 1);
        Assert.assertEquals(mode.gameOvers.get(), 1);
    }

    private List<String> leaveWith(final String choice) throws InterruptedException {
        final TestBrowser host = hostIn(MODE);
        startMatch();
        awaitPriority(host);
        mode.leaving.clear();
        send(host, message("leave", "choice", choice));
        host.awaitMatching("hello", h -> MODE.equals(str(h, "campaign")), "the match was not left");
        return mode.leaving;
    }

    // Fails if a mode cannot tell a match the player left from the draw that closing its table ends it in, or is not told which of its own buttons the player left with
    @Test(timeOut = 180_000)
    public void leavingMatchComesBeforeTheTableCloses() throws Exception {
        WebTestSupport.skipUnlessStress();
        Assert.assertEquals(leaveWith(null), List.of("leaving, table true", "left for null, table false"));
        Assert.assertEquals(leaveWith("endless").get(1), "left for endless, table false");
    }
}
