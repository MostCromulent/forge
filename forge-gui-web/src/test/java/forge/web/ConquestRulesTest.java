package forge.web;

import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.planarconquest.ConquestBattle;
import forge.gamemodes.planarconquest.ConquestChaosBattle;
import forge.gamemodes.planarconquest.ConquestController;
import forge.gamemodes.planarconquest.ConquestData;
import forge.gamemodes.planarconquest.ConquestLocation;
import forge.gamemodes.planarconquest.ConquestPlane;
import forge.gamemodes.planarconquest.ConquestUtil;
import forge.model.FModel;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/** Conquest's rules as the shared package states them, with no screen involved. */
public class ConquestRulesTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @AfterMethod(alwaysRun = true)
    public void cleanUp() {
        ConquestFixture.cleanUp();
    }

    // Fails if a second conquest can take the name of one that exists, which would write into the first one's folder
    @Test
    public void aNameInUseIsRefused() {
        final String name = ConquestFixture.name();
        assertNull(ConquestUtil.nameProblem(name));
        ConquestFixture.create("Zendikar", name);
        assertNotNull(ConquestUtil.nameProblem(name));
        assertNotNull(ConquestUtil.nameProblem("  "));
    }

    // Fails if a created conquest is not on disk as created: missing from the list, or read back with another
    // place, balance or commander deck
    @Test
    public void aCreatedConquestIsListedAndReadsBack() {
        final ConquestData made = ConquestFixture.create("Zendikar");
        assertSame(FModel.getConquest().getModel(), made);
        final ConquestData read = ConquestController.listSaves().stream()
                .filter(d -> d.getName().equals(made.getName())).findFirst().orElse(null);
        assertNotNull(read, "the new conquest is not listed");
        assertEquals(read.getCurrentPlane().getName(), "Zendikar");
        assertEquals(read.getAEtherShards(), made.getAEtherShards());
        assertEquals(read.getUnlockedCardCount(), made.getUnlockedCardCount());
        final int deckSize = made.getSelectedCommander().getDeck().getMain().countAll();
        FModel.getConquest().load(read);
        assertEquals(read.getSelectedCommander().getDeck().getMain().countAll(), deckSize);
        assertTrue(deckSize >= 40);
    }

    // Fails if a Commander event's players do not start at 30 life with their commanders set, or the match is not
    // one game under Conquest's own rules
    @Test
    public void aCommanderEventIsPreparedAsConquestPlaysIt() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestLocation loc = eventWhere(data.getCurrentPlane(), v -> v.contains(GameType.Commander));
        final ConquestBattle battle = loc.getEvent().createBattle(loc, 0);
        final ConquestController.PreparedBattle prepared = FModel.getConquest().prepareBattle(battle, null);
        assertEquals(prepared.players().size(), 2);
        for (final RegisteredPlayer player : prepared.players()) {
            assertEquals(player.getStartingLife(), 30);
            assertFalse(player.getCommanders().isEmpty());
        }
        assertSame(prepared.players().get(0), prepared.human());
        assertEquals(prepared.rules().getGameType(), GameType.PlanarConquest);
        assertEquals(prepared.rules().getGamesPerMatch(), 1);
        assertNull(FModel.getConquest().prepareBattle(battle, null), "a second battle was prepared over the first");
    }

    // Fails if preparing an event with no Commander variant changes the deck the commander keeps
    @Test
    public void preparingABattleLeavesTheStoredDeckAlone() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final int before = data.getSelectedCommander().getDeck().getMain().countAll();
        final ConquestLocation loc = eventWhere(data.getCurrentPlane(), Set::isEmpty);
        final ConquestController.PreparedBattle prepared =
                FModel.getConquest().prepareBattle(loc.getEvent().createBattle(loc, 0), null);
        assertEquals(prepared.human().getDeck().getMain().countAll(), before + 1);
        assertEquals(data.getSelectedCommander().getDeck().getMain().countAll(), before);
    }

    // Fails if a chaos battle left before it ends is not a loss, or leaves the controller unable to start another
    @Test
    public void aChaosBattleLeftEarlyIsALoss() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final int losses = data.getChaosBattleRecord().getLosses();
        final ConquestController conquest = FModel.getConquest();
        final ConquestController.PreparedBattle prepared = conquest.prepareBattle(new ConquestChaosBattle(), null);
        assertEquals(prepared.rules().getGamesPerMatch(), 3);
        conquest.finishBattle();
        assertEquals(data.getChaosBattleRecord().getLosses(), losses + 1);
        assertNull(conquest.getActiveBattle());
        final ConquestLocation loc = new ConquestLocation(data.getCurrentPlane(), 0, 0, 0);
        assertNotNull(conquest.prepareBattle(loc.getEvent().createBattle(loc, 0), null));
    }

    /** The first place on a plane whose event's variants are wanted. */
    static ConquestLocation eventWhere(final ConquestPlane plane, final Predicate<Set<GameType>> wanted) {
        for (int region = 0; region < plane.getRegions().size(); region++) {
            for (int row = 0; row < plane.getRowsPerRegion(); row++) {
                for (int col = 0; col < plane.getCols(); col++) {
                    final ConquestLocation loc = new ConquestLocation(plane, region, row, col);
                    if (wanted.test(loc.getEvent().getVariants())) {
                        return loc;
                    }
                }
            }
        }
        throw new AssertionError("no such event on " + plane.getName());
    }

    // Fails if deleting leaves the save listed
    @Test
    public void aDeletedConquestIsGone() {
        final ConquestData made = ConquestFixture.create("Zendikar");
        ConquestController.delete(made);
        assertFalse(ConquestController.listSaves().stream().anyMatch(d -> d.getName().equals(made.getName())));
    }
}
