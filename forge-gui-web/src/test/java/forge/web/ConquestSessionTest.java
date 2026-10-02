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
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
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

    // Fails if a Commander event over netplay does not start both players at 30 life with their commanders in the command zone
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

    // Fails if a Planeswalker-only event against a planeswalker does not put both planeswalkers in the command zone
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
        final Recorder host = onTheShelf();
        send(host, message("conquestLoad", "name", data.getName()));
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

    // Fails if the map of a new conquest does not have the player at its first place with only that place open
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
        send(host, message("conquestSelect", "region", next.getRegionIndex(), "row", next.getRow(), "col", next.getCol()));
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
        send(host, JsonCodec.message("conquestMove"));
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
        send(host, JsonCodec.message("conquestBattle"));
        host.awaitMatching("error", e -> true, "the illegal deck was not reported");
        Assert.assertNull(FModel.getConquest().getActiveBattle());
    }

    // Fails if a browser without the host's seat can open a conquest
    @Test(timeOut = 120_000)
    public void aGuestCannotOpenConquest() throws Exception {
        final Recorder guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest"));
        send(guest, message("conquestOpen", "resume", false));
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

    /** Gives every question on the way its default answer, since only at first priority is the game past dealing its zones and safe to end. */
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
        send(host, JsonCodec.message("conquestBattle"));
        host.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean(), "the battle did not start");
        awaitPriority(host);
        return host;
    }

    // Fails if a win is not recorded and rewarded once, or a reconnecting browser is not sent the same reward with the save unchanged
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

        // Until the reward has been shown no other battle starts, since it may end in one that cannot be refused
        sessions.onMessage(again, JsonCodec.message("conquestBattle"));
        onUi(() -> { });
        Assert.assertNull(FModel.getConquest().getActiveBattle(), "a battle started over a reward still to be shown");

        send(again, JsonCodec.message("conquestClaim"));
        final Recorder third = connect("host");
        third.awaitMatching("conquestState", s -> true, "the map was not sent");
        Assert.assertFalse(third.got.stream().anyMatch(m -> "conquestReward".equals(m.get("t").getAsString())),
                "an acknowledged reward was sent again");
    }

    // Fails if a lost Commander event restarted from the result screen does not start again at 30 life, or the loss is not recorded
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
        send(host, message("nextGame", "decision", "NEW"));
        awaitPriority(host);
        final Game game = sessions.hostLobby().getHostedMatch().getGame();
        for (final Player p : game.getPlayers()) {
            Assert.assertEquals(p.getStartingLife(), 30);
        }
    }

    // Fails if a wheel landing on chaos does not lead to a best-of-three once the reward is acknowledged, or leaving it early is not a loss
    @Test(timeOut = 240_000)
    public void aChaosBattleFollowsTheWheelAndLeavingItIsALoss() throws Exception {
        WebTestSupport.skipUnlessStress();
        final ConquestData data = ConquestFixture.create("Zendikar");
        final Recorder host = inBattle(data);
        sessions.onMessage(host, message("devConquestWheel", "outcome", "CHAOS"));
        computerLoses(host);
        host.awaitMatching("conquestResult", r -> true, "no result was sent");
        send(host, JsonCodec.message("leave"));
        host.awaitMatching("conquestReward", r -> true, "the reward was not sent");
        send(host, JsonCodec.message("conquestClaim"));
        host.awaitMatching("hello", h -> h.get("inMatch").getAsBoolean(), "the chaos battle did not start");
        awaitPriority(host);
        Assert.assertEquals(sessions.hostLobby().getHostedMatch().getGame().getRules().getGamesPerMatch(), 3);
        final int losses = FModel.getConquest().getModel().getChaosBattleRecord().getLosses();
        send(host, JsonCodec.message("leave"));
        host.awaitMatching("conquestState", s -> true, "leaving did not return to the map");
        Assert.assertEquals(FModel.getConquest().getModel().getChaosBattleRecord().getLosses(), losses + 1);
        Assert.assertNull(FModel.getConquest().getActiveBattle());
    }

    // Fails if a move with nowhere to go answers with a map, which a browser in the middle of a walk would take as its end
    @Test(timeOut = 120_000)
    public void aMoveWithNothingSelectedSendsNoMap() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final Recorder host = hostInConquest(data);
        send(host, JsonCodec.message("conquestMove"));
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
        send(host, JsonCodec.message("conquestBattle"));
        host.awaitMatching("error", e -> true, "the failed start was not reported");
        host.awaitMatching("hello", h -> !h.get("inMatch").getAsBoolean() && h.get("inConquest").getAsBoolean(), "the page did not return to the map");
        Assert.assertNull(FModel.getConquest().getActiveBattle());
        Assert.assertNull(FModel.getConquest().getModel().getCurrentPlaneData().getEventRecord(loc), "a battle that never started was recorded");

        send(host, JsonCodec.message("conquestBattle"));
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
        send(host, message("conquestEditDeck", "commander", FModel.getConquest().getModel().getSelectedCommander().getName()));
        return host.awaitMatching("editor", e -> e.has("state"), "the editor did not open").getAsJsonObject("state");
    }

    /** What the editor shows once it has answered the message just sent. */
    private JsonObject edited(final Recorder host, final JsonObject message) throws InterruptedException {
        send(host, message);
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

    // Fails if the editor's catalogue lists a card that is not owned or is exiled, or such a card can be added to the deck
    @Test(timeOut = 120_000)
    public void theEditorOffersOnlyOwnedCards() throws Exception {
        final Recorder host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final PaperCard exiled = spares(data).get(0);
        data.exile(List.of(exiled));
        final JsonObject opened = editing(host);
        Assert.assertEquals(str(opened, "collection"), data.getName());
        send(host, message("catalogue", "request", 1, "text", "", "colours", "", "type", "any", "filters", "",
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

    // Fails if a card added in the editor is not in the next battle's deck, or the map is not told the deck's new size on close
    @Test(timeOut = 120_000)
    public void anEditedDeckIsTheDeckFoughtWith() throws Exception {
        final Recorder host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final String spare = spares(data).get(0).getName();
        final int size = data.getSelectedCommander().getDeck().getMain().countAll();
        editing(host);
        Assert.assertTrue(mainNames(edited(host, message("editorEdit", "op", "add", "name", spare, "to", "Main", "count", 1))).contains(spare));
        send(host, JsonCodec.message("editorClose"));
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

    // Fails if anything the editor can be asked changes the deck's Commander section or its name
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
        edited(host, message("editorDeck", "op", "duplicate"));
        edited(host, message("editorDeck", "op", "delete"));
        edited(host, message("editorCheck", "format", "Commander", "unrestricted", false));
        Assert.assertEquals(edited(host, message("editorCheck", "format", "Constructed", "unrestricted", true)).get("format").getAsString(), "PlanarConquest");
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
        Assert.assertEquals(FModel.getConquest().getDecks().size(), 1, "the deck was copied");
        Assert.assertSame(commander.getDeck(), stored, "the commander does not hold the deck that was saved");
    }

    // Fails if an import adds a card that is not owned, leaves out an owned card named in another printing, or refuses the basic lands
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
        Assert.assertNotEquals(DeckImport.read(list, Check.of(GameType.PlanarConquest, null)).deck().getMain().toFlatList().get(0), owned,
                "the list names the printing owned, so it shows nothing");
        editing(host);
        send(host, message("importRead", "request", 7, "text", list, "format", "PlanarConquest", "unrestricted", false));
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
        send(host, message("catalogue", "request", 1, "text", "", "colours", "", "type", "any", "filters", "",
                "sort", "name", "offset", 0, "showAll", true, "source", source));
        final List<String> names = new ArrayList<>();
        final JsonObject page = host.awaitMatching("catalogue", c -> true, "the " + source + " was not listed");
        // The browser holds one catalogue page for the editor and both lists, and tells them apart by this
        Assert.assertEquals(str(page, "source"), source);
        page.getAsJsonArray("rows").forEach(r -> names.add(r.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    // Fails if a card a deck uses can be exiled, or a free card's exile and retrieval do not pay, charge and move it between the lists
    @Test(timeOut = 120_000)
    public void exileAndRetrieveOverTheWire() throws Exception {
        final Recorder host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final PaperCard inDeck = data.getSelectedCommander().getDeck().getMain().toFlatList().stream()
                .filter(c -> !c.getRules().getType().isBasicLand()).findFirst().orElseThrow();
        final int shards = data.getAEtherShards();
        send(host, exile(inDeck, false));
        host.awaitMatching("notice", n -> true, "exiling a card in use was not refused");
        Assert.assertFalse(data.isInExile(inDeck));
        Assert.assertEquals(data.getAEtherShards(), shards);

        final PaperCard spare = spares(data).get(0);
        final int value = data.getExileValue(List.of(spare));
        final int cost = data.getRetrieveCost(List.of(spare));
        send(host, exile(spare, false));
        host.awaitMatching("conquestBar", b -> b.get("shards").getAsInt() == shards + value, "the exile was not paid for");
        Assert.assertEquals(host.awaitMatching("conquestCollection", c -> true, "the lists' sizes were not sent").get("exiled").getAsInt(), 1);
        Assert.assertFalse(cardsOf(host, "collection").contains(spare.getName()), "an exiled card is still in the collection");
        Assert.assertEquals(cardsOf(host, "exile"), List.of(spare.getName()));
        final JsonObject row = host.awaitNewest("catalogue").getAsJsonArray("rows").get(0).getAsJsonObject();
        Assert.assertEquals(row.get("value").getAsInt(), cost);

        // With too few shards the card stays where it is, and mobile's sentence says why
        data.spendAEtherShards(data.getAEtherShards());
        send(host, exile(spare, true));
        host.awaitMatching("notice", n -> true, "a retrieval that cannot be paid for was not refused");
        Assert.assertTrue(data.isInExile(spare));
        data.rewardAEtherShards(shards + value);

        send(host, exile(spare, true));
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
        send(host, message("conquestLead", "commander", second.getName()));
        host.awaitMatching("conquestState", s -> s.getAsJsonObject("commander").get("name").getAsString().equals(wanted.getDisplayName()),
                "the map does not name the commander chosen");
        final JsonObject party = host.awaitMatching("conquestParty", p -> true, "the party was not sent");
        for (final JsonElement e : party.getAsJsonArray("commanders")) {
            final JsonObject c = e.getAsJsonObject();
            Assert.assertEquals(c.get("selected").getAsBoolean(), c.get("name").getAsString().equals(second.getName()));
        }
        Assert.assertEquals(new ConquestData(made.getDirectory()).getSelectedCommander().getName(), second.getName());
    }

    // Fails if a card the rules allow any number of goes into a conquest's deck more times than it is owned, by adding or importing
    @Test(timeOut = 120_000)
    public void aCardIsNotAddedMoreTimesThanItIsOwned() throws Exception {
        final Recorder host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final PaperCard rats = FModel.getMagicDb().getCommonCards().getCard("Relentless Rats");
        data.unlockCard(rats);
        final String deck = data.getSelectedCommander().getName();
        editing(host);
        edited(host, message("editorEdit", "op", "add", "name", rats.getName(), "to", "Main", "count", 1));
        Assert.assertEquals(storedDeck(deck).getMain().countByName(rats.getName()), 1);
        edited(host, message("editorEdit", "op", "add", "name", rats.getName(), "to", "Main", "count", 1));
        Assert.assertEquals(storedDeck(deck).getMain().countByName(rats.getName()), 1, "a second copy was added of a card owned once");
        edited(host, message("importCommit", "text", "20 " + rats.getName() + "\n", "name", "Imported", "format", "PlanarConquest",
                "unrestricted", false, "action", "add"));
        Assert.assertEquals(storedDeck(deck).getMain().countByName(rats.getName()), 1, "an import added copies that are not owned");
    }

    // Fails if a conquest import is read under the format sent, which takes an owned legendary creature for the list's commander
    @Test(timeOut = 120_000)
    public void anImportIsReadAsAConquestDeckWhateverFormatIsAsked() throws Exception {
        final Recorder host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        PaperCard legend = null;
        for (final PaperCard card : data.getCurrentPlane().getCommanders()) {
            if (!data.hasUnlockedCard(card)) {
                legend = card;
            }
        }
        data.unlockCard(legend);
        final String deck = data.getSelectedCommander().getName();
        editing(host);
        edited(host, message("importCommit", "text", "1 " + legend.getName() + "\n", "name", "Imported", "format", "Commander",
                "unrestricted", false, "action", "add"));
        Assert.assertEquals(storedDeck(deck).getMain().countByName(legend.getName()), 1, "the owned legendary creature did not reach the deck");
        Assert.assertEquals(storedDeck(deck).get(DeckSection.Commander).toFlatList(), List.of(data.getSelectedCommander().getCard()));
    }

    /** The host on the list of saved conquests, wherever the last test left the session. */
    private Recorder onTheShelf() throws InterruptedException {
        final Recorder host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        host.awaitMatching("hello", h -> h.get("host").getAsBoolean(), "the host's seat was not given");
        sessions.onMessage(host, message("setName", "name", "Host"));
        // A test that failed in a battle leaves the session in it, and nothing but leaving gets it out
        final JsonObject hello = host.awaitNewest("hello");
        if (hello.get("inMatch").getAsBoolean()) {
            send(host, JsonCodec.message("leave"));
            host.awaitMatching("hello", h -> !h.get("inMatch").getAsBoolean(), "the session never left the last test's battle");
        }
        send(host, message("conquestOpen", "resume", false));
        host.awaitMatching("hello", h -> h.get("inConquest").getAsBoolean() && str(h, "conquest") == null, "the shelf did not open");
        host.awaitMatching("conquestSaves", s -> true, "the conquests were not listed");
        return host;
    }

    private static List<String> names(final JsonObject message, final String list) {
        final List<String> names = new ArrayList<>();
        message.getAsJsonArray(list).forEach(e -> names.add(e.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    private static File saveDir(final String name) {
        return new File(ForgeConstants.CONQUEST_SAVE_DIR, name.replace(' ', '_'));
    }

    // Fails if a conquest can be started under a name another has, or the conquest of that name is replaced
    @Test(timeOut = 120_000)
    public void aNameInUseIsRefused() throws Exception {
        final ConquestData existing = ConquestFixture.create("Zendikar");
        existing.rewardAEtherShards(17);
        existing.saveData();
        final int shards = existing.getAEtherShards();
        final PaperCard commander = existing.getCurrentPlane().getCommanders().get(0);
        final Recorder host = onTheShelf();
        send(host, message("conquestCreate", "name", existing.getName(), "plane", "Zendikar", "commander", commander.getName(),
                "planeswalker", ConquestUtil.getStartingPlaneswalkerOptions(commander).iterator().next().getName()));
        host.awaitMatching("error", e -> true, "a name in use was not refused");
        Assert.assertFalse(host.got.stream().anyMatch(m -> "conquestState".equals(m.get("t").getAsString())), "a conquest was opened");
        Assert.assertEquals(new ConquestData(existing.getDirectory()).getAEtherShards(), shards, "the conquest of that name was replaced");
    }

    // Fails if the form is not told a plane's commanders or a commander's planeswalkers, or a conquest made from them is not saved and shown
    @Test(timeOut = 120_000)
    public void aCreatedConquestOpensOnItsMap() throws Exception {
        final String name = ConquestFixture.expected();
        final Recorder host = onTheShelf();
        send(host, JsonCodec.message("conquestOptions"));
        final JsonObject planes = host.awaitMatching("conquestOptions", o -> true, "the planes were not sent");
        Assert.assertTrue(names(planes, "planes").contains("Zendikar"));
        Assert.assertTrue(planes.get("startShards").getAsInt() > 0);
        send(host, message("conquestOptions", "plane", "Zendikar"));
        final List<String> commanders = names(host.awaitMatching("conquestOptions", o -> o.has("commanders"), "the plane's commanders were not sent"), "commanders");
        // A rebalanced card has no picture anywhere, so it is offered after every card that has one
        final List<String> pictured = commanders.stream().filter(n -> !n.startsWith("A-")).toList();
        Assert.assertTrue(pictured.size() < commanders.size(), "Zendikar has no rebalanced commander to tell the order by");
        Assert.assertEquals(commanders.subList(0, pictured.size()), pictured, "a rebalanced commander is listed before one with a picture");
        final String commander = commanders.get(0);
        send(host, message("conquestOptions", "plane", "Zendikar", "commander", commander));
        final String walker = names(host.awaitMatching("conquestOptions", o -> o.has("planeswalkers"), "the commander's planeswalkers were not sent"), "planeswalkers").get(0);
        send(host, message("conquestCreate", "name", name, "plane", "Zendikar", "commander", commander, "planeswalker", walker));
        host.awaitMatching("hello", h -> name.equals(str(h, "conquest")), "the new conquest did not open");
        final JsonObject state = host.awaitMatching("conquestState", s -> true, "the new conquest's map was not sent");
        Assert.assertEquals(state.get("plane").getAsString(), "Zendikar");
        final ConquestData saved = new ConquestData(saveDir(name));
        Assert.assertEquals(saved.getSelectedCommander().getName(), commander);
        Assert.assertEquals(saved.getPlaneswalker().getName(), walker);
    }

    // Fails if a commander that is not of the plane can start a conquest there: no error is sent, or a save is made
    @Test(timeOut = 120_000)
    public void aConquestThatCannotBeIsNotCreated() throws Exception {
        final String name = ConquestFixture.expected();
        final PaperCard stranger = FModel.getPlanes().get("Alara").getCommanders().get(0);
        Assert.assertFalse(FModel.getPlanes().get("Zendikar").getCommanders().contains(stranger));
        final Recorder host = onTheShelf();
        send(host, message("conquestCreate", "name", name, "plane", "Zendikar", "commander", stranger.getName(),
                "planeswalker", ConquestUtil.getStartingPlaneswalkerOptions(stranger).iterator().next().getName()));
        host.awaitMatching("error", e -> true, "a commander of another plane was not refused");
        Assert.assertFalse(saveDir(name).exists(), "a conquest was made all the same");
    }

    // Fails if renaming the conquest played last leaves the old folder, or the start page's Resume and the shelf's mark still name the old one
    @Test(timeOut = 120_000)
    public void renamingTheCurrentConquestKeepsItCurrent() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final String to = ConquestFixture.expected();
        final Recorder host = onTheShelf();
        send(host, message("conquestRename", "name", data.getName(), "to", to));
        final JsonObject saves = host.awaitMatching("conquestSaves", s -> to.equals(str(s, "current")), "the shelf does not mark the new name as current");
        Assert.assertTrue(names(saves, "saves").contains(to));
        Assert.assertFalse(names(saves, "saves").contains(data.getName()));
        host.awaitMatching("hello", h -> to.equals(str(h, "currentConquest")), "the start page was not told the new name");
        Assert.assertFalse(data.getDirectory().exists(), "the old folder is still there");

        // A name another conquest has is refused, and nothing is renamed
        final ConquestData other = ConquestFixture.create("Zendikar");
        send(host, message("conquestRename", "name", to, "to", other.getName()));
        final JsonObject refused = host.awaitMatching("error", e -> true, "a name in use was not refused");
        Assert.assertTrue(saveDir(to).isDirectory());
        // The browser forgets an error at every hello, so the refusal is the last thing said
        awaitTrue(() -> host.got.size() >= 3, "the shelf was not sent again with the refusal");
        Assert.assertSame(host.got.get(host.got.size() - 1), refused, "the refusal was followed by a hello, which wipes it from the page");
    }

    // Fails if a deleted conquest is still listed, or its folder is still there
    @Test(timeOut = 120_000)
    public void deletingRemovesTheSave() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final Recorder host = onTheShelf();
        Assert.assertTrue(names(host.awaitNewest("conquestSaves"), "saves").contains(data.getName()));
        send(host, message("conquestDelete", "name", data.getName()));
        final JsonObject saves = host.awaitMatching("conquestSaves", s -> true, "the shelf was not sent again");
        Assert.assertFalse(names(saves, "saves").contains(data.getName()));
        Assert.assertFalse(data.getDirectory().exists());
    }

    // Fails if a plane can be unlocked without the emblems or entered while locked, unlocking does not spend once, or going back costs
    @Test(timeOut = 120_000)
    public void unlockingSpendsOnce() throws Exception {
        final Recorder host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final ConquestPlane alara = FModel.getPlanes().get("Alara");
        final int cost = data.getPlaneUnlockCost();
        final int few = data.getPlaneswalkEmblems();
        Assert.assertTrue(few < cost, "the fixture can already afford a plane");
        send(host, message("conquestPlaneswalk", "plane", "Alara", "unlock", true));
        host.awaitMatching("error", e -> true, "an unlock that cannot be paid for was not refused");
        Assert.assertFalse(data.isPlaneUnlocked(alara));
        Assert.assertEquals(data.getPlaneswalkEmblems(), few);
        Assert.assertEquals(data.getCurrentPlane().getName(), "Zendikar");

        data.rewardPlaneswalkEmblems(cost);
        send(host, message("conquestPlaneswalk", "plane", "Alara", "unlock", false));
        host.awaitMatching("error", e -> true, "a locked plane was entered without unlocking it");
        Assert.assertEquals(data.getCurrentPlane().getName(), "Zendikar");
        Assert.assertEquals(data.getPlaneswalkEmblems(), few + cost);

        send(host, message("conquestPlaneswalk", "plane", "Alara", "unlock", true));
        host.awaitMatching("conquestState", s -> "Alara".equals(s.get("plane").getAsString()), "the map is not the new plane's");
        host.awaitMatching("conquestBar", b -> b.get("emblems").getAsInt() == few, "the bar does not show the emblems spent");
        Assert.assertEquals(data.getPlaneswalkEmblems(), few);
        final ConquestData saved = new ConquestData(data.getDirectory());
        Assert.assertEquals(saved.getCurrentPlane().getName(), "Alara");
        Assert.assertEquals(saved.getPlaneswalkEmblems(), few);
        Assert.assertTrue(saved.isPlaneUnlocked(alara));

        // An unlocked plane is gone back to for nothing, whichever way it is asked for
        send(host, message("conquestPlaneswalk", "plane", "Zendikar", "unlock", true));
        host.awaitMatching("conquestState", s -> "Zendikar".equals(s.get("plane").getAsString()), "the player did not go back");
        Assert.assertEquals(data.getPlaneswalkEmblems(), few);
    }

    private static JsonObject aether(final JsonObject shown, final boolean pull) {
        return message("conquestAether", "colors", shown == null ? "" : shown.get("colors").getAsString(), "type", shown == null ? "" : shown.get("type").getAsString(),
                "rarity", shown == null ? "" : shown.get("rarity").getAsString(), "cmc", shown == null ? "" : shown.get("cmc").getAsString(), "pull", pull);
    }

    // Fails if a pull does not cost what the page showed and add the card it names, or a pull with no shards gives a card
    @Test(timeOut = 120_000)
    public void aPullSpendsAndUnlocks() throws Exception {
        final Recorder host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final int shards = data.getAEtherShards();
        final int owned = data.getUnlockedCardCount();
        send(host, aether(null, false));
        final JsonObject shown = host.awaitMatching("conquestAether", a -> true, "the Aether was not sent");
        final int cost = shown.get("cost").getAsInt();
        final int matching = shown.get("matching").getAsInt();
        Assert.assertTrue(cost > 0 && matching > 0, "the fixture's starting filters match nothing");
        Assert.assertEquals(shown.get("type").getAsString(), "CREATURE");
        Assert.assertEquals(shown.getAsJsonArray("rarities").size(), 4);

        send(host, aether(shown, true));
        final JsonObject after = host.awaitMatching("conquestAether", a -> a.has("pulled"), "the pull gave no card");
        final String name = after.getAsJsonObject("pulled").get("name").getAsString();
        Assert.assertEquals(data.getAEtherShards(), shards - cost);
        Assert.assertEquals(data.getUnlockedCardCount(), owned + 1);
        boolean has = false;
        for (final PaperCard card : data.getUnlockedCards()) {
            has |= card.getName().equals(name);
        }
        Assert.assertTrue(has, "the card pulled is not owned");
        Assert.assertEquals(after.get("matching").getAsInt(), matching - 1);
        host.awaitMatching("conquestBar", b -> b.get("shards").getAsInt() == shards - cost, "the bar does not show the shards spent");

        data.spendAEtherShards(data.getAEtherShards());
        send(host, aether(shown, true));
        final JsonObject refused = host.awaitMatching("conquestAether", a -> true, "the Aether did not answer");
        Assert.assertFalse(refused.has("pulled"), "a pull with no shards gave a card");
        Assert.assertTrue(refused.has("problem"));
        Assert.assertEquals(data.getUnlockedCardCount(), owned + 1);
        Assert.assertEquals(data.getAEtherShards(), 0);
    }

    // Fails if a preference that breaks a rule, is negative or is not one of the page's is saved, or a good one is not
    @Test(timeOut = 120_000)
    public void aPreferenceIsCheckedBeforeItIsSaved() throws Exception {
        final forge.gamemodes.planarconquest.ConquestPreferences prefs = FModel.getConquestPreferences();
        final int commons = prefs.getPrefInt(CQPref.BOOSTER_COMMONS);
        final int pull = prefs.getPrefInt(CQPref.AETHER_BASE_PULL_COST);
        // The preferences are the real profile's, and the reset below would lose whatever its owner had set
        final java.util.Map<CQPref, String> before = new java.util.EnumMap<>(CQPref.class);
        for (final CQPref pref : CQPref.values()) {
            before.put(pref, prefs.getPref(pref));
        }
        final Recorder host = hostInConquest(ConquestFixture.create("Zendikar"));
        final String current = prefs.getPref(CQPref.CURRENT_CONQUEST);
        try {
            send(host, message("conquestPref", "key", "BOOSTER_COMMONS", "value",
                    16 - prefs.getPrefInt(CQPref.BOOSTER_UNCOMMONS) - prefs.getPrefInt(CQPref.BOOSTER_RARES)));
            host.awaitMatching("conquestPrefs", p -> p.has("problem"), "a pack of more than 15 cards was not refused");
            Assert.assertEquals(prefs.getPrefInt(CQPref.BOOSTER_COMMONS), commons);
            send(host, message("conquestPref", "key", "AETHER_BASE_PULL_COST", "value", -1));
            host.awaitMatching("conquestPrefs", p -> p.has("problem"), "a negative value was not refused");
            Assert.assertEquals(prefs.getPrefInt(CQPref.AETHER_BASE_PULL_COST), pull);
            send(host, message("conquestPref", "key", "CURRENT_CONQUEST", "value", 1));
            host.awaitMatching("conquestPrefs", p -> true, "the preferences were not sent");
            Assert.assertEquals(prefs.getPref(CQPref.CURRENT_CONQUEST), current, "a preference that is not the page's was written");

            send(host, message("conquestPref", "key", "AETHER_BASE_PULL_COST", "value", pull + 50));
            final JsonObject saved = host.awaitMatching("conquestPrefs", p -> !p.has("problem"), "a good value was refused");
            Assert.assertEquals(saved.getAsJsonArray("rows").size(), 20);
            Assert.assertEquals(new forge.gamemodes.planarconquest.ConquestPreferences().getPrefInt(CQPref.AETHER_BASE_PULL_COST), pull + 50);

            send(host, JsonCodec.message("conquestPrefsReset"));
            host.awaitMatching("conquestPrefs", p -> true, "the reset did not answer");
            Assert.assertEquals(prefs.getPrefInt(CQPref.AETHER_BASE_PULL_COST), Integer.parseInt(CQPref.AETHER_BASE_PULL_COST.getDefault()));
            Assert.assertEquals(prefs.getPref(CQPref.CURRENT_CONQUEST), current, "the reset forgot which conquest is current");
        } finally {
            // Which conquest is current is the fixture's to put back
            before.remove(CQPref.CURRENT_CONQUEST);
            before.forEach(prefs::setPref);
            prefs.save();
        }
    }

    // Fails if a plane's statistics do not list its regions with what is conquered in each, or every plane's figures carry regions
    @Test(timeOut = 120_000)
    public void statisticsNameTheRegions() throws Exception {
        final Recorder host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        send(host, message("conquestStats", "plane", "Zendikar"));
        final JsonObject stats = host.awaitMatching("conquestStats", s -> true, "the statistics were not sent");
        final List<String> regions = names(stats, "regions");
        final List<String> wanted = new ArrayList<>();
        data.getCurrentPlane().getRegions().forEach(r -> wanted.add(r.getName()));
        Assert.assertEquals(regions, wanted);
        Assert.assertEquals(stats.getAsJsonArray("regions").get(0).getAsJsonObject().get("conquered").getAsInt(), 1);
        Assert.assertEquals(stats.getAsJsonArray("regions").get(0).getAsJsonObject().get("wins").getAsInt(), 1);
        Assert.assertEquals(stats.getAsJsonArray("figures").size(), 8);
        Assert.assertTrue(stats.getAsJsonArray("planes").toString().contains("Zendikar"));

        send(host, JsonCodec.message("conquestStats"));
        final JsonObject all = host.awaitMatching("conquestStats", s -> true, "the statistics for every plane were not sent");
        Assert.assertFalse(all.has("plane"));
        Assert.assertEquals(all.getAsJsonArray("regions").size(), 0);
        Assert.assertEquals(all.getAsJsonArray("commanders").size(), 2, "one commander and the chaos battles");
    }

    // Fails if a name with a path in it reaches a folder that is not one of the saves, which a delete would remove
    @Test
    public void aNameWithAPathInItFindsNothing() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final String folder = data.getDirectory().getName();
        Assert.assertNotNull(ConquestGame.find(data.getName()));
        Assert.assertNull(ConquestGame.find("../saves/" + folder));
        Assert.assertNull(ConquestGame.find("..\\saves\\" + folder));
        Assert.assertNull(ConquestGame.find(folder + "/."));
    }

    // Fails if a rebalanced card is not drawn with the art of the card it rebalances, or a card with its own art is given another's
    @Test
    public void aRebalancedCardWearsItsOriginalsArt() {
        final PaperCard rebalanced = FModel.getMagicDb().getCommonCards().getCard("A-Phylath, World Sculptor");
        Assert.assertTrue(rebalanced.isRebalanced());
        final PaperCard original = forge.util.ImageUtil.getPaperCardFromImageKey(WebServer.artKey(rebalanced.getImageKey(false)));
        Assert.assertEquals(original.getName(), "Phylath, World Sculptor");
        Assert.assertEquals(original.getEdition(), rebalanced.getEdition());
        Assert.assertEquals(WebServer.artKey(original.getImageKey(false)), original.getImageKey(false));
    }

    // Fails if a saved conquest's card does not say how much of the plane it stands on is conquered
    @Test
    public void aSaveSaysWhatIsConqueredOnItsPlane() throws Exception {
        final ConquestData data = ConquestFixture.install();
        final ToBrowser.ConquestSave save = ConquestGame.saves().saves().stream().filter(s -> s.name().equals(data.getName())).findFirst().orElseThrow();
        Assert.assertEquals(save.conquered(), 1);
        Assert.assertEquals(save.total(), 54);
    }
}
