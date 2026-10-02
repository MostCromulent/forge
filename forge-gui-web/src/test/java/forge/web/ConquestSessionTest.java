package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gamemodes.planarconquest.ConquestBattle;
import forge.gamemodes.planarconquest.ConquestCommander;
import forge.gamemodes.planarconquest.ConquestController.PreparedBattle;
import forge.gamemodes.planarconquest.ConquestData;
import forge.gamemodes.planarconquest.ConquestLocation;
import forge.gamemodes.planarconquest.ConquestPlane;
import forge.gamemodes.planarconquest.ConquestPreferences.CQPref;
import forge.gamemodes.planarconquest.ConquestUtil;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
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

        // A reload on the result screen shows the same result and records nothing again
        final Recorder reloaded = connect("host");
        Assert.assertEquals(reloaded.awaitMatching("conquestResult", r -> true, "a reload lost the result"), result);
        Assert.assertEquals(model.getCurrentPlaneData().getEventRecord(model.getCurrentLocation()).getTotalWins(), 1);
        Assert.assertEquals(model.getAEtherShards(), shards + wheelShards);

        host.forget();
        sessions.onMessage(reloaded, JsonCodec.message("leave"));
        final JsonObject reward = reloaded.awaitMatching("conquestReward", r -> true, "the reward was not sent with the map");
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

    // Fails if a move with nowhere to go answers with a map, which a browser in the middle of a walk would take as its end
    @Test(timeOut = 120_000)
    public void aMoveWithNothingSelectedSendsNoMap() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final Recorder host = hostInConquest(data);
        host.forget();
        sessions.onMessage(host, JsonCodec.message("conquestMove"));
        sessions.onMessage(host, message("conquestSelect", "region", 0, "row", 0, "col", 0));
        host.awaitMatching("conquestState", s -> true, "selecting sent no map");
        Assert.assertEquals(host.got.stream().filter(m -> "conquestState".equals(m.get("t").getAsString())).count(), 1);
    }

    // Fails if a battle that could not start leaves the conquest unable to start another, or records anything
    @Test(timeOut = 180_000)
    public void aBattleThatFailsToStartLeavesTheConquestUsable() throws Exception {
        WebTestSupport.skipUnlessStress();
        final ConquestData data = ConquestFixture.create("Zendikar");
        final Recorder host = hostInConquest(data);
        // A battle already marked active makes the next one refuse to be prepared
        final ConquestLocation loc = FModel.getConquest().getModel().getCurrentLocation();
        FModel.getConquest().prepareBattle(loc.getEvent().createBattle(loc, 0), null);
        host.forget();
        sessions.onMessage(host, JsonCodec.message("conquestBattle"));
        host.awaitMatching("error", e -> true, "the failed start was not reported");
        host.awaitMatching("hello", h -> !h.get("inMatch").getAsBoolean() && h.get("inConquest").getAsBoolean(), "the page did not return to the map");
        Assert.assertNull(FModel.getConquest().getActiveBattle());
        Assert.assertNull(FModel.getConquest().getModel().getCurrentPlaneData().getEventRecord(loc), "a battle that never started was recorded");

        host.forget();
        sessions.onMessage(host, JsonCodec.message("conquestBattle"));
        host.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean(), "a second battle did not start");
        awaitPriority(host);
    }

    // Fails if one save that cannot be read keeps every save from being listed
    @Test
    public void anUnreadableSaveDoesNotHideTheOthers() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final String broken = ConquestFixture.broken();
        final List<String> names = ConquestGame.saves().saves().stream().map(s -> s.name()).toList();
        Assert.assertTrue(names.contains(data.getName()), "the readable save is not listed");
        Assert.assertFalse(names.contains(broken), "a save with no planeswalker or place is listed");
    }

    private Recorder editor;

    // The session outlives a test, and an editor left open would be shown to the next one's browser
    @AfterMethod(alwaysRun = true)
    public void closeTheEditor() {
        if (editor != null) {
            sessions.onMessage(editor, JsonCodec.message("editorClose"));
            editor = null;
        }
    }

    /** Opens the selected commander's deck in the editor, and answers what the editor shows. */
    private JsonObject editing(final Recorder host) throws InterruptedException {
        editor = host;
        host.forget();
        sessions.onMessage(host, message("conquestEditDeck", "commander", FModel.getConquest().getModel().getSelectedCommander().getName()));
        return host.awaitMatching("editor", e -> e.has("state"), "the editor did not open").getAsJsonObject("state");
    }

    /** What the editor shows once it has answered the message just sent. */
    private JsonObject edited(final Recorder host, final JsonObject message) throws InterruptedException {
        host.forget();
        sessions.onMessage(host, message);
        return host.awaitMatching("editor", e -> e.has("state"), "the editor did not answer").getAsJsonObject("state");
    }

    private static List<String> mainNames(final JsonObject editorState) {
        final List<String> names = new ArrayList<>();
        editorState.getAsJsonArray("main").forEach(g -> g.getAsJsonObject().getAsJsonArray("cards")
                .forEach(c -> names.add(c.getAsJsonObject().get("name").getAsString())));
        return names;
    }

    /** Owned cards that are in no deck and are neither a commander nor the planeswalker, in name order. */
    private static List<PaperCard> spares(final ConquestData data) {
        final List<PaperCard> out = new ArrayList<>();
        for (final PaperCard card : data.getUnlockedCards()) {
            boolean used = card.equals(data.getPlaneswalker()) || data.isInExile(card) || card.getRules().getType().isBasicLand();
            for (final ConquestCommander c : data.getCommanders()) {
                used |= c.getCard().equals(card) || c.getDeck().getMain().countByName(card.getName()) > 0;
            }
            if (!used) {
                out.add(card);
            }
        }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    /** A card the conquest does not own under any printing. */
    private static PaperCard stranger(final ConquestData data) {
        final Set<String> owned = new HashSet<>();
        data.getUnlockedCards().forEach(c -> owned.add(c.getName()));
        for (final PaperCard card : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            if (!owned.contains(card.getName()) && card.getRules().getType().isCreature() && !card.getRules().getType().isLegendary()) {
                return card;
            }
        }
        throw new AssertionError("every card is owned");
    }

    private static Deck storedDeck(final String name) {
        return FModel.getConquest().getDecks().get(name);
    }

    // Fails if the editor's catalogue lists a card outside the conquest's available cards, lists an exiled card, or
    // a card that is not owned can be added to the deck
    @Test(timeOut = 120_000)
    public void theEditorOffersOnlyOwnedCards() throws Exception {
        final Recorder host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final PaperCard exiled = spares(data).get(0);
        data.exile(List.of(exiled));
        final JsonObject opened = editing(host);
        Assert.assertEquals(str(opened, "collection"), data.getName());
        host.forget();
        sessions.onMessage(host, message("catalogue", "request", 1, "text", "", "colours", "", "type", "any", "filters", "",
                "sort", "name", "offset", 0, "showAll", true));
        final JsonObject page = host.awaitMatching("catalogue", c -> true, "the catalogue did not answer");
        final Set<String> available = new HashSet<>();
        ConquestUtil.getAvailablePool().forEach(e -> available.add(e.getKey().getName()));
        final List<String> listed = new ArrayList<>();
        page.getAsJsonArray("rows").forEach(r -> listed.add(r.getAsJsonObject().get("name").getAsString()));
        Assert.assertFalse(listed.isEmpty(), "the catalogue is empty");
        Assert.assertTrue(available.containsAll(listed), "the catalogue lists cards that are not owned: " + listed);
        Assert.assertFalse(listed.contains(exiled.getName()), "an exiled card is offered");
        Assert.assertEquals(page.get("total").getAsInt(), available.size());

        final String stranger = stranger(data).getName();
        final JsonObject after = edited(host, message("editorEdit", "op", "add", "name", stranger, "to", "Main", "count", 1));
        Assert.assertFalse(mainNames(after).contains(stranger), "a card that is not owned was added");
        Assert.assertTrue(host.got.stream().anyMatch(m -> "notice".equals(m.get("t").getAsString())), "the refusal was not said");
        final JsonObject again = edited(host, message("editorEdit", "op", "add", "name", exiled.getName(), "to", "Main", "count", 1));
        Assert.assertFalse(mainNames(again).contains(exiled.getName()), "an exiled card was added");
    }

    // Fails if a card added in the editor is not in the deck the next battle is prepared with, or the map is not
    // told the deck's new size when the editor closes
    @Test(timeOut = 120_000)
    public void anEditedDeckIsTheDeckFoughtWith() throws Exception {
        final Recorder host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final String spare = spares(data).get(0).getName();
        final int size = data.getSelectedCommander().getDeck().getMain().countAll();
        editing(host);
        Assert.assertTrue(mainNames(edited(host, message("editorEdit", "op", "add", "name", spare, "to", "Main", "count", 1))).contains(spare));
        host.forget();
        sessions.onMessage(host, JsonCodec.message("editorClose"));
        host.awaitMatching("conquestState", s -> s.getAsJsonObject("commander").get("deckSize").getAsInt() == size + 1,
                "the map was not told the deck's new size");
        onUi(() -> {
            final ConquestLocation loc = data.getCurrentLocation();
            final PreparedBattle prepared = FModel.getConquest().prepareBattle(loc.getEvent().createBattle(loc, 0), null);
            try {
                Assert.assertEquals(prepared.human().getDeck().getMain().countByName(spare), 1, "the battle's deck lacks the card added");
            } finally {
                FModel.getConquest().cancelBattle();
            }
        });
    }

    // Fails if anything the editor can be asked changes the deck's Commander section or its name: an edit and its
    // undo, a removal or a move from the Commander section, a rename, or an import that replaces the deck
    @Test(timeOut = 120_000)
    public void theCommanderStays() throws Exception {
        final Recorder host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final ConquestCommander commander = data.getSelectedCommander();
        final List<PaperCard> spares = spares(data);
        editing(host);
        edited(host, message("editorEdit", "op", "add", "name", spares.get(0).getName(), "to", "Main", "count", 1));
        edited(host, JsonCodec.message("editorUndo"));
        edited(host, message("editorEdit", "op", "remove", "name", commander.getName(), "from", "Commander", "count", 1));
        edited(host, message("editorEdit", "op", "move", "name", commander.getName(), "from", "Commander", "to", "Main", "count", 1));
        edited(host, message("editorEdit", "op", "commander", "name", spares.get(0).getName(), "count", 1));
        edited(host, message("editorRename", "name", "Another name"));
        final JsonObject replaced = edited(host, message("importCommit", "text",
                "Commander\n1 " + commander.getName() + "\n\nDeck\n1 " + spares.get(0).getName() + "\n", "name", "Imported",
                "format", "PlanarConquest", "unrestricted", false, "action", "replace"));
        Assert.assertEquals(mainNames(replaced), List.of(spares.get(0).getName()), "the import did not replace the main deck");
        Assert.assertEquals(replaced.get("name").getAsString(), commander.getName());
        final Deck stored = storedDeck(commander.getName());
        Assert.assertNotNull(stored, "the deck is no longer saved under the commander's name");
        Assert.assertEquals(stored.get(DeckSection.Commander).toFlatList(), List.of(commander.getCard()));
        Assert.assertNull(storedDeck("Another name"));
        Assert.assertNull(storedDeck("Imported"));
        Assert.assertSame(commander.getDeck(), stored, "the commander does not hold the deck that was saved");
    }

    // Fails if reading a list for a conquest's deck does not mark a card that is not owned as a problem, or adding
    // the list puts that card in the deck, leaves out an owned card named in a printing that is not owned, puts it
    // in as the printing named instead of the one owned, or refuses the basic lands
    @Test(timeOut = 120_000)
    public void importTakesOnlyOwnedCards() throws Exception {
        final Recorder host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        PaperCard owned = null;
        PaperCard otherPrinting = null;
        for (final PaperCard spare : spares(data)) {
            for (final PaperCard printing : FModel.getMagicDb().getCommonCards().getAllCards(spare.getName())) {
                if (!printing.getEdition().equals(spare.getEdition())) {
                    owned = spare;
                    otherPrinting = printing;
                }
            }
        }
        Assert.assertNotNull(owned, "the fixture owns no spare card with a second printing");
        final String stranger = stranger(data).getName();
        final int forests = data.getSelectedCommander().getDeck().getMain().countByName("Forest");
        final String list = "1 " + owned.getName() + " (" + otherPrinting.getEdition() + ")\n1 " + stranger + "\n5 Forest\n";
        editing(host);
        host.forget();
        sessions.onMessage(host, message("importRead", "request", 7, "text", list, "format", "PlanarConquest", "unrestricted", false));
        final JsonObject read = host.awaitMatching("importResult", r -> true, "the list was not read");
        Assert.assertEquals(read.getAsJsonArray("lines").get(0).getAsJsonObject().get("kind").getAsString(), "read");
        Assert.assertEquals(read.getAsJsonArray("lines").get(1).getAsJsonObject().get("kind").getAsString(), "problem",
                "a card that is not owned was not marked");
        Assert.assertEquals(read.getAsJsonArray("lines").get(2).getAsJsonObject().get("kind").getAsString(), "read");
        Assert.assertEquals(read.getAsJsonObject("summary").get("notImported").getAsInt(), 1);

        final JsonObject added = edited(host, message("importCommit", "text", list, "name", "Imported", "format", "PlanarConquest",
                "unrestricted", false, "action", "add"));
        Assert.assertTrue(mainNames(added).contains(owned.getName()), "the owned card was left out");
        Assert.assertFalse(mainNames(added).contains(stranger), "a card that is not owned was imported");
        final Deck stored = storedDeck(data.getSelectedCommander().getName());
        Assert.assertEquals(stored.getMain().count(owned), 1, "the card went in as a printing that is not owned");
        Assert.assertEquals(stored.getMain().countByName("Forest"), forests + 5);
    }

    private static JsonObject exile(final PaperCard card, final boolean retrieve) {
        final JsonObject m = message("conquestExile", "retrieve", retrieve);
        final JsonArray cards = new JsonArray();
        cards.add(card.getImageKey(false));
        m.add("cards", cards);
        return m;
    }

    private List<String> cardsOf(final Recorder host, final String source) throws InterruptedException {
        host.forget();
        sessions.onMessage(host, message("catalogue", "request", 1, "text", "", "colours", "", "type", "any", "filters", "",
                "sort", "name", "offset", 0, "showAll", true, "source", source));
        final List<String> names = new ArrayList<>();
        host.awaitMatching("catalogue", c -> true, "the " + source + " was not listed").getAsJsonArray("rows")
                .forEach(r -> names.add(r.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    // Fails if a card a deck uses can be exiled over the wire; if exiling a free card does not pay its exile value,
    // leaves it in the collection's list or out of the exile's; or if retrieving it does not cost its price and
    // bring it back
    @Test(timeOut = 120_000)
    public void exileAndRetrieveOverTheWire() throws Exception {
        final Recorder host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final PaperCard inDeck = data.getSelectedCommander().getDeck().getMain().toFlatList().stream()
                .filter(c -> !c.getRules().getType().isBasicLand()).findFirst().orElseThrow();
        final int shards = data.getAEtherShards();
        host.forget();
        sessions.onMessage(host, exile(inDeck, false));
        host.awaitMatching("notice", n -> true, "exiling a card in use was not refused");
        Assert.assertFalse(data.isInExile(inDeck));
        Assert.assertEquals(data.getAEtherShards(), shards);

        final PaperCard spare = spares(data).get(0);
        final int value = data.getExileValue(List.of(spare));
        final int cost = data.getRetrieveCost(List.of(spare));
        host.forget();
        sessions.onMessage(host, exile(spare, false));
        host.awaitMatching("conquestBar", b -> b.get("shards").getAsInt() == shards + value, "the exile was not paid for");
        Assert.assertEquals(host.awaitMatching("conquestCollection", c -> true, "the lists' sizes were not sent").get("exiled").getAsInt(), 1);
        Assert.assertFalse(cardsOf(host, "collection").contains(spare.getName()), "an exiled card is still in the collection");
        Assert.assertEquals(cardsOf(host, "exile"), List.of(spare.getName()));
        final JsonObject row = host.awaitNewest("catalogue").getAsJsonArray("rows").get(0).getAsJsonObject();
        Assert.assertEquals(row.get("value").getAsInt(), cost);

        host.forget();
        sessions.onMessage(host, exile(spare, true));
        host.awaitMatching("conquestBar", b -> b.get("shards").getAsInt() == shards + value - cost, "the retrieval was not charged");
        Assert.assertFalse(data.isInExile(spare));
        Assert.assertTrue(cardsOf(host, "collection").contains(spare.getName()));
    }

    // Fails if choosing a commander does not make it the one the map names and the party marks, or is not saved
    @Test(timeOut = 120_000)
    public void selectingACommanderChangesTheLead() throws Exception {
        final ConquestData made = ConquestFixture.create("Zendikar");
        PaperCard second = null;
        for (final PaperCard card : made.getCurrentPlane().getCommanders()) {
            if (!made.hasUnlockedCard(card)) {
                second = card;
            }
        }
        made.unlockCard(second);
        made.saveData();
        final Recorder host = hostInConquest(made);
        final ConquestCommander wanted = ConquestGame.commander(second.getName());
        host.forget();
        sessions.onMessage(host, message("conquestLead", "commander", second.getName()));
        host.awaitMatching("conquestState", s -> s.getAsJsonObject("commander").get("name").getAsString().equals(wanted.getDisplayName()),
                "the map does not name the commander chosen");
        final JsonObject party = host.awaitMatching("conquestParty", p -> true, "the party was not sent");
        for (final JsonElement e : party.getAsJsonArray("commanders")) {
            final JsonObject c = e.getAsJsonObject();
            Assert.assertEquals(c.get("selected").getAsBoolean(), c.get("name").getAsString().equals(second.getName()));
        }
        Assert.assertEquals(new ConquestData(made.getDirectory()).getSelectedCommander().getName(), second.getName());
    }
}
