package forge.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.GameType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.planarconquest.ConquestBattle;
import forge.gamemodes.planarconquest.ConquestController.PreparedBattle;
import forge.gamemodes.planarconquest.ConquestData;
import forge.gamemodes.planarconquest.ConquestLocation;
import forge.gamemodes.planarconquest.ConquestPlane;
import forge.gamemodes.planarconquest.ConquestUtil;
import forge.gui.GuiBase;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
        for (int i = 0; i < 2000 && !wanted.getAsBoolean(); i++) {
            try {
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assert.assertTrue(wanted.getAsBoolean(), why);
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
}
