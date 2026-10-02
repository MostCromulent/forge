package forge.web;

import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.planarconquest.ConquestAwardPool;
import forge.gamemodes.planarconquest.ConquestBattle;
import forge.gamemodes.planarconquest.ConquestChaosBattle;
import forge.gamemodes.planarconquest.ConquestController;
import forge.gamemodes.planarconquest.ConquestData;
import forge.gamemodes.planarconquest.ConquestEvent.ChaosWheelOutcome;
import forge.gamemodes.planarconquest.ConquestLocation;
import forge.gamemodes.planarconquest.ConquestPlane;
import forge.gamemodes.planarconquest.ConquestPreferences.CQPref;
import forge.gamemodes.planarconquest.ConquestReward;
import forge.gamemodes.planarconquest.ConquestRewardStep;
import forge.gamemodes.planarconquest.ConquestRewardStep.Kind;
import forge.gamemodes.planarconquest.ConquestUtil;
import forge.item.PaperCard;
import forge.model.FModel;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
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

    // Fails if the wheel can be told to rest on a spot that pays something else
    @Test
    public void theWheelRestsOnTheOutcomeItWasGiven() {
        for (final ChaosWheelOutcome outcome : ChaosWheelOutcome.values()) {
            for (int i = 0; i < 20; i++) {
                assertEquals(ChaosWheelOutcome.getWheelOutcome(ChaosWheelOutcome.restingRotation(outcome)), outcome);
            }
        }
    }

    // Fails if a first win does not give its emblem before the wheel, or the shards spot does not pay the wheel's shards
    @Test
    public void aFirstWinGivesAnEmblemThenTheWheel() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestBattle battle = wonBattle(data);
        final int wheelShards = FModel.getConquestPreferences().getPrefInt(CQPref.AETHER_WHEEL_SHARDS);
        final int conquerEmblems = FModel.getConquestPreferences().getPrefInt(CQPref.PLANESWALK_CONQUER_EMBLEMS);
        final int shards = data.getAEtherShards();
        final List<ConquestRewardStep> steps = FModel.getConquest().claimRewards(battle, ChaosWheelOutcome.SHARDS);
        assertEquals(kinds(steps), List.of(Kind.CONQUER_EMBLEMS, Kind.WHEEL, Kind.SHARDS));
        assertEquals(data.getPlaneswalkEmblems(), conquerEmblems);
        assertEquals(data.getAEtherShards(), shards + wheelShards);
        assertEquals(steps.get(2).amount(), wheelShards);

        // A second win at the same event gives no emblem
        data.addWin(battle);
        assertEquals(kinds(FModel.getConquest().claimRewards(battle, ChaosWheelOutcome.DOUBLE_SHARDS)), List.of(Kind.WHEEL, Kind.SHARDS));
        assertEquals(data.getPlaneswalkEmblems(), conquerEmblems);
        assertEquals(data.getAEtherShards(), shards + 3 * wheelShards);
    }

    // Fails if a booster's cards are not unlocked, its duplicates are not paid for, or two boosters are not two steps
    @Test
    public void boostersUnlockTheirCardsAndPayForDuplicates() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestBattle battle = wonBattle(data);
        final int shards = data.getAEtherShards();
        final int cards = data.getUnlockedCardCount();
        final List<ConquestRewardStep> steps = FModel.getConquest().claimRewards(battle, ChaosWheelOutcome.DOUBLE_BOOSTER);
        final List<ConquestRewardStep> boosters = steps.stream().filter(s -> s.kind() == Kind.BOOSTER).toList();
        assertEquals(boosters.size(), 2);
        assertEquals(boosters.get(1).number(), 2);
        assertEquals(boosters.get(1).total(), 2);
        int fresh = 0;
        int paid = 0;
        for (final ConquestRewardStep booster : boosters) {
            assertFalse(booster.cards().isEmpty());
            for (final ConquestReward reward : booster.cards()) {
                assertTrue(data.hasUnlockedCard(reward.getCard()));
                if (reward.isDuplicate()) {
                    paid += reward.getReplacementShards();
                } else {
                    fresh++;
                }
            }
        }
        assertEquals(data.getUnlockedCardCount(), cards + fresh);
        assertEquals(data.getAEtherShards(), shards + paid);
        final int owed = paid;
        assertEquals(steps.stream().anyMatch(s -> s.kind() == Kind.DUPLICATE_SHARDS && s.amount() == owed), paid > 0);
    }

    // Fails if a booster from an empty pool is shown as a pack with no cards
    @Test
    public void anEmptyBoosterIsLeftOut() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestBattle battle = wonBattle(data);
        final ConquestAwardPool pool = data.getCurrentPlane().getAwardPool();
        final List<List<PaperCard>> kept = List.of(new ArrayList<>(pool.commons), new ArrayList<>(pool.uncommons),
                new ArrayList<>(pool.rares), new ArrayList<>(pool.mythics));
        pool.commons.clear();
        pool.uncommons.clear();
        pool.rares.clear();
        pool.mythics.clear();
        try {
            final List<ConquestRewardStep> steps = FModel.getConquest().claimRewards(battle, ChaosWheelOutcome.BOOSTER);
            assertFalse(steps.stream().anyMatch(s -> s.kind() == Kind.BOOSTER));
        } finally {
            // The pool is cached on the plane for the whole process
            pool.commons.addAll(kept.get(0));
            pool.uncommons.addAll(kept.get(1));
            pool.rares.addAll(kept.get(2));
            pool.mythics.addAll(kept.get(3));
        }
    }

    // Fails if a lost battle is rewarded
    @Test
    public void aLostBattleGivesNothing() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestLocation loc = new ConquestLocation(data.getCurrentPlane(), 0, 0, 0);
        final ConquestBattle battle = loc.getEvent().createBattle(loc, 0);
        data.addLoss(battle);
        assertTrue(FModel.getConquest().claimRewards(battle, ChaosWheelOutcome.SHARDS).isEmpty());
        assertEquals(data.getPlaneswalkEmblems(), 0);
    }

    // Fails if moving away from an event that opened a secret plane leaves the plane open, or the new place is not saved
    @Test
    public void movingAwayClosesThePortal() {
        final ConquestData data = ConquestFixture.create("Eldraine");
        final ConquestPlane plane = data.getCurrentPlane();
        final ConquestLocation portal = ConquestFixture.portal(plane);
        assertNotNull(portal, "Eldraine has lost its secret plane; pick another plane");
        final ConquestPlane secret = ConquestUtil.getPlaneByName(portal.getEvent().getTemporaryUnlock());
        data.setCurrentLocation(portal);
        data.addWin(portal.getEvent().createBattle(portal, 0));
        ConquestUtil.setPlaneTemporarilyAccessible(secret.getName(), true);
        assertFalse(secret.isUnreachable());

        // Standing still is not a move
        assertEquals(data.moveTo(portal).size(), 1);
        assertFalse(secret.isUnreachable());

        final ConquestLocation next = portal.getNeighbors().get(0);
        final List<ConquestLocation> path = data.moveTo(next);
        assertEquals(path.size(), 2);
        assertEquals(path.get(0), portal);
        assertTrue(secret.isUnreachable());
        assertEquals(new ConquestData(data.getDirectory()).getCurrentLocation(), next);
    }

    // Fails if a place that touches no conquered event can be walked to
    @Test
    public void anUnreachablePlaceIsNotMovedTo() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestLocation start = data.getCurrentLocation();
        assertNull(data.moveTo(new ConquestLocation(data.getCurrentPlane(), 2, 1, 1)));
        assertEquals(data.getCurrentLocation(), start);
    }

    // Fails if a plane's cost is not the first-unlock price plus the increase for each plane already unlocked past the first
    @Test
    public void eachPlaneCostsMoreEmblems() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final int first = FModel.getConquestPreferences().getPrefInt(CQPref.PLANESWALK_FIRST_UNLOCK);
        final int more = FModel.getConquestPreferences().getPrefInt(CQPref.PLANESWALK_UNLOCK_INCREASE);
        assertEquals(data.getPlaneUnlockCost(), first);
        data.unlockPlane(FModel.getPlanes().get("Alara"));
        assertEquals(data.getPlaneUnlockCost(), first + more);
    }

    private static ConquestBattle wonBattle(final ConquestData data) {
        final ConquestLocation loc = new ConquestLocation(data.getCurrentPlane(), 0, 0, 0);
        final ConquestBattle battle = loc.getEvent().createBattle(loc, 0);
        data.addWin(battle);
        return battle;
    }

    private static List<Kind> kinds(final List<ConquestRewardStep> steps) {
        return steps.stream().map(ConquestRewardStep::kind).toList();
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
