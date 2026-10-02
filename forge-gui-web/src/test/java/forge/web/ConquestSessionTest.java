package forge.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.GameType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gamemodes.planarconquest.ConquestBattle;
import forge.gamemodes.planarconquest.ConquestController.PreparedBattle;
import forge.gamemodes.planarconquest.ConquestData;
import forge.gamemodes.planarconquest.ConquestLocation;
import forge.gamemodes.planarconquest.ConquestPlane;
import forge.gamemodes.planarconquest.ConquestPreferences.CQPref;
import forge.gamemodes.planarconquest.ConquestUtil;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A conquest as a browser drives it, and its battles as netplay plays them. */
public class ConquestSessionTest extends SessionsTest {
    @Override
    WebGuiBase gui() {
        return (WebGuiBase) GuiBase.getInterface();
    }

    @Override
    void afterDisconnecting() {
        ConquestFixture.cleanUp();
    }

    private static void onUi(final Runnable r) {
        GuiBase.getInterface().invokeInEdtAndWait(r);
    }

    /** The game's thread deals the zones after the match has started, so what it sets up is waited for. */
    private static void awaitTrue(final BooleanSupplier wanted, final String why) {
        awaitTrue(wanted, () -> why);
    }

    private static void awaitTrue(final BooleanSupplier wanted, final Supplier<String> why) {
        for (int i = 0; i < 2000 && !wanted.getAsBoolean(); i++) {
            try {
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assert.assertTrue(wanted.getAsBoolean(), why.get());
    }

    /** Starts the battle at a place over the loopback, with no session, and hands the running match to body. */
    private static void battleAt(final ConquestLocation loc, final Consumer<HostedMatch> body) {
        final LocalGame local = new LocalGame();
        try {
            final WebGuiGame gui = new WebGuiGame();
            gui.attach(new FakeBrowser(gui, false, true));
            final ConquestBattle battle = loc.getEvent().createBattle(loc, 0);
            onUi(() -> {
                final PreparedBattle p = FModel.getConquest().prepareBattle(battle, null);
                local.startPrepared("Web Player", p.rules(), p.variants(), p.players(), p.human(), gui);
            });
            body.accept(local.hostedMatch());
        } finally {
            onUi(local::shutdown);
        }
    }

    // Fails if a Commander event played over netplay does not start both players at 30 life with their commanders
    // in the command zone, or the web seat is not the human's
    @Test(timeOut = 120_000)
    public void aCommanderEventStartsAsConquestPlaysIt() {
        WebTestSupport.skipUnlessStress();
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestLocation loc = ConquestRulesTest.eventWhere(data.getCurrentPlane(), v -> v.equals(Set.of(GameType.Commander)));
        battleAt(loc, match -> {
            final Game game = match.getGame();
            Assert.assertEquals(game.getPlayers().size(), 2);
            for (final Player p : game.getPlayers()) {
                Assert.assertEquals(p.getStartingLife(), 30);
                awaitTrue(() -> p.getCardsIn(ZoneType.Command).stream().filter(c -> c.isCommander()).count() == 1,
                        p.getName() + " has no commander in the command zone");
            }
            Assert.assertEquals(game.getRules().getGameType(), GameType.PlanarConquest);
            // Throws when no human player is played from a web seat
            WebTestSupport.remoteGui(match);
        });
    }

    // Fails if an event that is Planeswalker only, against a planeswalker, does not put both planeswalkers in the
    // command zone
    @Test(timeOut = 120_000)
    public void aPlaneswalkerEventPutsBothInTheCommandZone() {
        WebTestSupport.skipUnlessStress();
        final ConquestData data = ConquestFixture.create("Alara");
        final ConquestLocation loc = ConquestRulesTest.eventWhere(data.getCurrentPlane(), v -> v.equals(Set.of(GameType.Planeswalker)));
        Assert.assertTrue(loc.getEvent().getAvatarCard().getRules().getType().isPlaneswalker());
        battleAt(loc, match -> {
            for (final Player p : match.getGame().getPlayers()) {
                awaitTrue(() -> p.getCardsIn(ZoneType.Command).stream().anyMatch(c -> c.getType().isPlaneswalker()),
                        p.getName() + " has no planeswalker in the command zone");
            }
        });
    }

    private Recorder hostInConquest(final ConquestData data) throws InterruptedException {
        final Recorder host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        host.awaitMatching("hello", h -> h.get("host").getAsBoolean(), "the host's seat was not given");
        sessions.onMessage(host, message("setName", "name", "Host"));
        // A test that failed in a battle leaves the session in it, and nothing but leaving gets it out
        final JsonObject hello = host.awaitNewest("hello");
        if (hello.get("inMatch").getAsBoolean()) {
            host.forget();
            sessions.onMessage(host, JsonCodec.message("leave"));
            host.awaitMatching("hello", h -> !h.get("inMatch").getAsBoolean(), "the session never left the last test's battle");
        }
        sessions.onMessage(host, message("conquestOpen", "resume", false));
        host.awaitMatching("conquestSaves", s -> true, "the conquests were not listed");
        host.forget();
        sessions.onMessage(host, message("conquestLoad", "name", data.getName()));
        host.awaitMatching("hello", h -> data.getName().equals(str(h, "conquest")), "the conquest did not open");
        host.awaitMatching("conquestState", s -> true, "the map was not sent");
        return host;
    }

    private static String str(final JsonObject o, final String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : null;
    }

    private static JsonObject cell(final JsonObject state, final int region, final int row, final int col) {
        for (final JsonElement e : state.getAsJsonArray("cells")) {
            final JsonObject c = e.getAsJsonObject();
            if (c.get("region").getAsInt() == region && c.get("row").getAsInt() == row && c.get("col").getAsInt() == col) {
                return c;
            }
        }
        throw new AssertionError("no such cell");
    }

    // Fails if the list of saves leaves out a conquest, or the map of a new one does not have the player at its
    // first place with only that place open
    @Test(timeOut = 120_000)
    public void aConquestOpensOnItsMap() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final Recorder host = hostInConquest(data);
        final JsonObject state = host.awaitNewest("conquestState");
        Assert.assertEquals(state.get("plane").getAsString(), "Zendikar");
        Assert.assertEquals(state.getAsJsonArray("regions").size(), 6);
        Assert.assertEquals(state.getAsJsonArray("cells").size(), 54);
        Assert.assertEquals(cell(state, 0, 0, 0).get("state").getAsString(), "open");
        Assert.assertEquals(cell(state, 0, 1, 0).get("state").getAsString(), "fog");
        Assert.assertFalse(cell(state, 0, 1, 0).has("name"), "a place not yet found says what is there");
        final JsonObject bar = host.awaitNewest("conquestBar");
        Assert.assertEquals(bar.get("shards").getAsInt(), data.getAEtherShards());
        Assert.assertEquals(bar.get("total").getAsInt(), 54);
    }

    // Fails if selecting a place moves the player, or closes the portal of the place stood on
    @Test(timeOut = 120_000)
    public void selectingDoesNotMove() throws Exception {
        final ConquestData data = ConquestFixture.create("Eldraine");
        final ConquestLocation portal = ConquestFixture.portal(data.getCurrentPlane());
        final ConquestPlane secret = ConquestUtil.getPlaneByName(portal.getEvent().getTemporaryUnlock());
        data.setCurrentLocation(portal);
        data.addWin(portal.getEvent().createBattle(portal, 0));
        data.saveData();
        final Recorder host = hostInConquest(data);
        ConquestUtil.setPlaneTemporarilyAccessible(secret.getName(), true);
        final ConquestLocation next = portal.getNeighbors().get(0);
        host.forget();
        sessions.onMessage(host, message("conquestSelect", "region", next.getRegionIndex(), "row", next.getRow(), "col", next.getCol()));
        final JsonObject state = host.awaitNewest("conquestState", "selecting sent no map");
        Assert.assertEquals(state.get("steps").getAsInt(), 1);
        Assert.assertEquals(state.getAsJsonObject("at").get("row").getAsInt(), portal.getRow());
        Assert.assertEquals(FModel.getConquest().getModel().getCurrentLocation(), portal);
        Assert.assertFalse(secret.isUnreachable(), "selecting closed the portal");
    }

    // Fails if a move does not save the new place, or sends no path for the marker to walk
    @Test(timeOut = 120_000)
    public void movingSavesThePlaceAndSendsThePath() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestLocation start = data.getCurrentLocation();
        data.addWin(start.getEvent().createBattle(start, 0));
        data.saveData();
        final Recorder host = hostInConquest(data);
        final ConquestLocation next = start.getNeighbors().get(0);
        sessions.onMessage(host, message("conquestSelect", "region", next.getRegionIndex(), "row", next.getRow(), "col", next.getCol()));
        host.forget();
        sessions.onMessage(host, JsonCodec.message("conquestMove"));
        final JsonObject state = host.awaitNewest("conquestState", s -> s.getAsJsonArray("path").size() == 2, "the move sent no path");
        Assert.assertEquals(state.get("steps").getAsInt(), 0);
        Assert.assertEquals(new ConquestData(data.getDirectory()).getCurrentLocation(), next);
    }

    // Fails if Battle starts a match with a deck Conquest does not allow
    @Test(timeOut = 120_000)
    public void anIllegalDeckDoesNotBattle() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final Recorder host = hostInConquest(data);
        FModel.getConquest().getModel().getSelectedCommander().getDeck().getMain().clear();
        host.forget();
        sessions.onMessage(host, JsonCodec.message("conquestBattle"));
        host.awaitMatching("error", e -> true, "the illegal deck was not reported");
        Assert.assertNull(FModel.getConquest().getActiveBattle());
    }

    // Fails if a browser without the host's seat can open a conquest
    @Test(timeOut = 120_000)
    public void aGuestCannotOpenConquest() throws Exception {
        final Recorder guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest"));
        guest.forget();
        sessions.onMessage(guest, message("conquestOpen", "resume", false));
        Thread.sleep(500);
        Assert.assertFalse(guest.got.stream().anyMatch(m -> "conquestSaves".equals(m.get("t").getAsString())));
    }

    private boolean devModeBefore;

    @BeforeClass
    public void allowTheWheelToBeSet() {
        WebTestSupport.initModel();
        devModeBefore = FModel.getPreferences().getPrefBoolean(FPref.DEV_MODE_ENABLED);
        FModel.getPreferences().setPref(FPref.DEV_MODE_ENABLED, true);
    }

    @AfterClass(alwaysRun = true)
    public void restoreDevMode() {
        FModel.getPreferences().setPref(FPref.DEV_MODE_ENABLED, devModeBefore);
    }

    /** Wins the game for the web seat with dev mode's cheat, as a player checking a reward by hand would. */
    private void computerLoses(final Recorder host) {
        awaitPriority(host);
        sessions.onMessage(host, message("dev", "action", "winGame"));
    }

    /** By message and not by id: a restarted match numbers its questions from the start again. */
    private final Set<JsonObject> answered = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Plays the web seat up to its first priority: every question the game asks on the way (the mulligan) is given its
     * default answer. Only then is the game past dealing its zones, and safe to end.
     */
    private void awaitPriority(final Recorder host) {
        final String[] seen = { "no game" };
        // A press that lands before the input is ready is lost, so it is made again
        final long[] lastPress = { 0 };
        awaitTrue(() -> {
            final ServerGameLobby lobby = sessions.hostLobby();
            final Game game = lobby == null || lobby.getHostedMatch() == null ? null : lobby.getHostedMatch().getGame();
            if (game == null) {
                return false;
            }
            for (final JsonObject m : host.got) {
                if ("request".equals(m.get("t").getAsString()) && answered.add(m)) {
                    sessions.onMessage(host, FakeBrowser.reply(m.get("id").getAsInt(), m.get("default")));
                }
            }
            for (final Player p : game.getPlayers()) {
                if (p.getController() instanceof PlayerControllerHuman human) {
                    final Object input = human.getInputQueue().getInput();
                    seen[0] = (input == null ? "no input" : input.getClass().getSimpleName()) + ", messages "
                            + host.got.stream().map(m -> m.get("t").getAsString() + (m.has("kind") ? ":" + m.get("kind").getAsString() : "")).distinct().toList()
                            + ", turn " + game.getPhaseHandler().getTurn() + " " + game.getPhaseHandler().getPhase() + ", over " + game.isGameOver();
                    if (input instanceof InputPassPriority) {
                        return true;
                    }
                    // Keeping the opening hand is an input of its own, answered with OK
                    if (input != null && System.currentTimeMillis() - lastPress[0] > 500) {
                        lastPress[0] = System.currentTimeMillis();
                        sessions.onMessage(host, JsonCodec.message("ok"));
                    }
                }
            }
            return false;
        }, () -> "the web seat never got priority: " + seen[0]);
    }

    private Recorder inBattle(final ConquestData data) throws InterruptedException {
        final Recorder host = hostInConquest(data);
        host.forget();
        sessions.onMessage(host, JsonCodec.message("conquestBattle"));
        host.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean(), "the battle did not start");
        awaitPriority(host);
        return host;
    }

    // Fails if a won battle is not recorded, its first-conquest emblem and the wheel's shards are not in the save, or
    // a browser that reconnects before the reward is acknowledged is not sent the same reward with the save unchanged
    @Test(timeOut = 180_000)
    public void aWinIsRecordedAndRewardedOnce() throws Exception {
        WebTestSupport.skipUnlessStress();
        final ConquestData data = ConquestFixture.create("Zendikar");
        final int shards = data.getAEtherShards();
        final int wheelShards = FModel.getConquestPreferences().getPrefInt(CQPref.AETHER_WHEEL_SHARDS);
        final int emblems = FModel.getConquestPreferences().getPrefInt(CQPref.PLANESWALK_CONQUER_EMBLEMS);
        final Recorder host = inBattle(data);
        sessions.onMessage(host, message("devConquestWheel", "outcome", "SHARDS"));
        computerLoses(host);
        final JsonObject result = host.awaitMatching("conquestResult", r -> true, "no result was sent");
        Assert.assertTrue(result.get("won").getAsBoolean());
        Assert.assertTrue(result.get("firstConquest").getAsBoolean());
        final ConquestData model = FModel.getConquest().getModel();
        Assert.assertTrue(model.getCurrentPlaneData().hasConquered(model.getCurrentLocation()));
        Assert.assertEquals(model.getPlaneswalkEmblems(), emblems);
        Assert.assertEquals(model.getAEtherShards(), shards + wheelShards);

        host.forget();
        sessions.onMessage(host, JsonCodec.message("leave"));
        final JsonObject reward = host.awaitMatching("conquestReward", r -> true, "the reward was not sent with the map");
        Assert.assertEquals(reward.getAsJsonArray("steps").size(), 3);

        final Recorder again = connect("host");
        final JsonObject resent = again.awaitMatching("conquestReward", r -> true, "a reload lost the reward");
        Assert.assertEquals(resent, reward);
        Assert.assertEquals(model.getAEtherShards(), shards + wheelShards);
        Assert.assertEquals(model.getPlaneswalkEmblems(), emblems);

        again.forget();
        sessions.onMessage(again, JsonCodec.message("conquestClaim"));
        final Recorder third = connect("host");
        third.awaitMatching("conquestState", s -> true, "the map was not sent");
        Assert.assertFalse(third.got.stream().anyMatch(m -> "conquestReward".equals(m.get("t").getAsString())),
                "an acknowledged reward was sent again");
    }

    // Fails if a lost Commander event restarted from the result screen does not start again at 30 life, or the loss
    // is not in the event's record
    @Test(timeOut = 180_000)
    public void retryKeepsTheBattlesSetUp() throws Exception {
        WebTestSupport.skipUnlessStress();
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestLocation loc = ConquestRulesTest.eventWhere(data.getCurrentPlane(), v -> v.equals(Set.of(GameType.Commander)));
        data.setCurrentLocation(loc);
        data.saveData();
        final Recorder host = inBattle(data);
        sessions.onMessage(host, JsonCodec.message("concede"));
        final JsonObject result = host.awaitMatching("conquestResult", r -> true, "no result was sent");
        Assert.assertFalse(result.get("won").getAsBoolean());
        Assert.assertEquals(FModel.getConquest().getModel().getCurrentPlaneData().getEventRecord(loc).getTotalLosses(), 1);
        host.forget();
        sessions.onMessage(host, message("nextGame", "decision", "NEW"));
        awaitPriority(host);
        final Game game = sessions.hostLobby().getHostedMatch().getGame();
        for (final Player p : game.getPlayers()) {
            Assert.assertEquals(p.getStartingLife(), 30);
        }
    }

    // Fails if a wheel that lands on chaos does not lead to a best-of-three once the reward is acknowledged, or
    // leaving that battle early is not a loss
    @Test(timeOut = 240_000)
    public void aChaosBattleFollowsTheWheelAndLeavingItIsALoss() throws Exception {
        WebTestSupport.skipUnlessStress();
        final ConquestData data = ConquestFixture.create("Zendikar");
        final Recorder host = inBattle(data);
        sessions.onMessage(host, message("devConquestWheel", "outcome", "CHAOS"));
        computerLoses(host);
        host.awaitMatching("conquestResult", r -> true, "no result was sent");
        host.forget();
        sessions.onMessage(host, JsonCodec.message("leave"));
        host.awaitMatching("conquestReward", r -> true, "the reward was not sent");
        host.forget();
        sessions.onMessage(host, JsonCodec.message("conquestClaim"));
        host.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean(), "the chaos battle did not start");
        awaitPriority(host);
        Assert.assertEquals(sessions.hostLobby().getHostedMatch().getGame().getRules().getGamesPerMatch(), 3);
        final int losses = FModel.getConquest().getModel().getChaosBattleRecord().getLosses();
        host.forget();
        sessions.onMessage(host, JsonCodec.message("leave"));
        host.awaitMatching("conquestState", s -> true, "leaving did not return to the map");
        Assert.assertEquals(FModel.getConquest().getModel().getChaosBattleRecord().getLosses(), losses + 1);
        Assert.assertNull(FModel.getConquest().getActiveBattle());
    }
}
