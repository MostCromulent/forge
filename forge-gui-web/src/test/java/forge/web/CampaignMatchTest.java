package forge.web;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.PreparedMatch;
import forge.gamemodes.match.PreparedMatch.Looks;
import forge.player.GamePlayerUtil;
import forge.player.LobbyPlayerHuman;
import forge.web.campaign.Campaign;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.CampaignProtocol.CampaignResult;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests how a campaign mode starts and leaves a match, with a mode that has no pages. */
public class CampaignMatchTest extends WebHubTest {
    private static final String MODE = "stub";
    private static final String SAVE = "run";

    private static final class StubMode implements Campaign {
        private Host host;
        private final AtomicInteger gameOvers = new AtomicInteger();

        @Override
        public void becameHost(final Host h) {
            host = h;
        }

        @Override
        public boolean handles(final Record command) {
            return false;
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
        public CampaignResult gameOver(final GameView hostGame) {
            gameOvers.incrementAndGet();
            return new CampaignResult(false, true, null, List.of());
        }

        @Override
        public void left() {
        }

        @Override
        public void handle(final BrowserChannel channel, final Record command, final String save, final Host h) {
        }
    }

    private final StubMode mode = new StubMode();
    private WebSession session;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger games = new AtomicInteger();

    private static Object read(final Object from, final String field) throws ReflectiveOperationException {
        final Field f = from.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(from);
    }

    // The session's table of modes is fixed, so the test's own are put into it
    @BeforeClass
    @SuppressWarnings("unchecked")
    public void addModes() throws Exception {
        final TestBrowser host = connect("host");
        sessions.onMessage(host, TestBrowser.action("claimHost"));
        sessions.onMessage(host, message("setName", "name", "Host"));
        session = (WebSession) read(sessions, "host");
        final Campaigns campaigns = (Campaigns) read(session, "campaigns");
        final Map<String, Campaign> modes = (Map<String, Campaign>) read(campaigns, "modes");
        modes.put(MODE, mode);
        modes.put("other", new StubMode());
        campaigns.becameHost();
    }

    @AfterMethod(alwaysRun = true)
    public void leaveTheMode() throws InterruptedException {
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

    private void startMatch(final Looks looks) {
        mode.host.startMatch(MODE, SAVE, () -> {
            final LobbyPlayer counted = new LobbyPlayerHuman("Nobody") {
                @Override
                public Player createIngamePlayer(final Game game, final int id) {
                    games.incrementAndGet();
                    return super.createIngamePlayer(game, id);
                }
            };
            final RegisteredPlayer human = new RegisteredPlayer(TestDecks.of("Human", "Forest", 60)).setPlayer(counted);
            final RegisteredPlayer computer = new RegisteredPlayer(TestDecks.of("Computer", "Forest", 60)).setPlayer(GamePlayerUtil.createAiPlayer("Computer", -1, -1));
            return new PreparedMatch(new GameRules(GameType.Constructed), Set.of(), List.of(human, computer), human, looks);
        }, failures::incrementAndGet);
    }

    // Fails if a match starts in a mode other than the one that asked for it, or the mode that asked is not told
    @Test(timeOut = 120_000)
    public void aMatchDoesNotStartFromAnotherModesStage() throws Exception {
        hostIn("other");
        final WebSession.Stage before = session.stage();
        startMatch(null);
        Assert.assertEquals(failures.get(), 1);
        Assert.assertSame(session.stage(), before);
        Assert.assertNull(sessions.hostLobby());
    }

    // Fails if a mode whose player has gone to the start page waits forever for a match
    @Test(timeOut = 120_000)
    public void aMatchDoesNotStartAfterTheBrowserLeft() {
        Assert.assertEquals(session.stage(), new WebSession.Menu());
        startMatch(null);
        Assert.assertEquals(failures.get(), 1);
        Assert.assertNull(sessions.hostLobby());
    }

    // Fails if the human plays with the player's own avatar and sleeve, or under a name the board cannot find the seat by
    @Test(timeOut = 180_000)
    public void theHumanWearsTheLooksOfTheMatch() throws Exception {
        WebTestSupport.skipUnlessStress();
        hostIn(MODE);
        startMatch(new Looks(7, 3));
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
        startMatch(null);
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
}
