package forge.web;

import forge.game.Game;
import forge.game.GameType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.planarconquest.ConquestBattle;
import forge.gamemodes.planarconquest.ConquestController.PreparedBattle;
import forge.gamemodes.planarconquest.ConquestData;
import forge.gamemodes.planarconquest.ConquestLocation;
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
}
