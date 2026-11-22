package forge.web;

import forge.game.GameType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.rogue.RogueConfig;
import forge.gamemodes.rogue.RogueEvent;
import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RoguePlanebound;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueRunHistoryEntry;
import forge.gamemodes.rogue.RogueRunState;
import forge.gamemodes.rogue.effect.AetherEffect;
import forge.gamemodes.rogue.effect.BazaarItem;
import forge.gamemodes.rogue.effect.ChestEffect;
import forge.gamemodes.rogue.effect.CustomRunModification;
import forge.gamemodes.rogue.effect.EventEffect;
import forge.gamemodes.rogue.effect.NPCEffect;
import forge.gamemodes.rogue.effect.WoundEffect;
import forge.gamemodes.rogue.effect.WrathfulEffect;
import forge.gamemodes.rogue.flow.RogueDecisions.Pick;
import forge.gamemodes.rogue.flow.RogueDecisions.ResultKind;
import forge.gamemodes.rogue.flow.RogueDecisions.RogueMatch;
import forge.gamemodes.rogue.flow.RogueDecisions.SanctumAction;
import forge.gamemodes.rogue.flow.RogueDecisions.SanctumAnswer;
import forge.gamemodes.rogue.flow.RogueDecisions.SanctumOffer;
import forge.gamemodes.rogue.flow.RogueDecisions.ShopAnswer;
import forge.gamemodes.rogue.flow.RogueDecisions.ShopOffer;
import forge.gamemodes.rogue.flow.RogueFlow;
import forge.gamemodes.rogue.npc.NPC;
import forge.gamemodes.rogue.path.NodeBazaar;
import forge.gamemodes.rogue.path.NodeChest;
import forge.gamemodes.rogue.path.NodeEvent;
import forge.gamemodes.rogue.path.NodePlanebound;
import forge.gamemodes.rogue.path.NodeSanctum;
import forge.gamemodes.rogue.path.RoguePathNode;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;

@Test(singleThreaded = true)
public class RogueFlowTest {
    private static final SanctumAnswer HEAL = new SanctumAnswer(SanctumAction.HEAL, null);
    private static final ShopAnswer LEAVE = new ShopAnswer(false, Set.of(), 0);

    private ScriptedDecisions decisions;
    private RogueFlow flow;
    private RogueRun run;

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @BeforeMethod
    public void clean() {
        RogueFixture.clean();
        ForgePreferences.DEV_MODE = false;
        decisions = new ScriptedDecisions();
        flow = new RogueFlow(decisions);
    }

    @AfterMethod(alwaysRun = true)
    public void plainRandom() {
        MyRandom.setRandom(new Random());
    }

    private RoguePathNode start(final Class<? extends RoguePathNode> at) {
        run = RogueFixture.run(at);
        flow.setRun(run);
        return run.getCurrentNode();
    }

    private void aetherwork(final AetherEffect effect) {
        final RogueMetaProgress progress = RogueMetaProgress.getInstance();
        progress.setAetherUpgradeLevel(3);
        progress.setTotalEchoes(100);
        Assert.assertTrue(progress.upgradeAetherEffect(effect));
        progress.activateAetherEffect(effect);
        run.snapshotAetherEffects(progress);
    }

    private static Pick<ChestEffect> loot(final ChestEffect effect) {
        return new Pick<>(false, effect);
    }

    private static Pick<RogueEvent.EventChoice> choice(final RogueEvent event, final EventEffect effect) {
        return new Pick<>(false, event.getChoices().stream().filter(c -> c.effect() == effect).findFirst().orElseThrow());
    }

    private static RogueRun saved() {
        return RogueIO.loadMostRecentContinuableRun().orElseThrow(AssertionError::new);
    }

    private static int copies(final RogueRun run, final PaperCard card) {
        return run.getCurrentDeck().getMain().count(card);
    }

    @Test
    public void aChestTraitIsAddedAndTheNodeCompletes() {
        final RoguePathNode chest = start(NodeChest.class);
        decisions.then("chooseChest", loot(ChestEffect.BANNER_OF_CHAMPIONS));
        flow.enter();
        Assert.assertEquals(run.getActiveChestEffects(), List.of(ChestEffect.BANNER_OF_CHAMPIONS));
        Assert.assertTrue(chest.isCompleted());
        Assert.assertEquals(run.getCurrentNode().getRowIndex(), 2);
        Assert.assertEquals(decisions.asked, List.of("chooseChest"));

        final RogueRun saved = saved();
        Assert.assertEquals(saved.getActiveChestEffects(), List.of(ChestEffect.BANNER_OF_CHAMPIONS));
        Assert.assertTrue(saved.getPath().getNode(9).isCompleted());
        Assert.assertEquals(saved.getCurrentNodeIndex(), run.getCurrentNodeIndex());
    }

    @Test
    public void skippingAChestCompletesTheNodeAndAddsNothing() {
        final RoguePathNode chest = start(NodeChest.class);
        decisions.then("chooseChest", loot(null));
        flow.enter();
        Assert.assertTrue(chest.isCompleted());
        Assert.assertTrue(run.getActiveChestEffects().isEmpty());
        Assert.assertTrue(saved().getPath().getNode(9).isCompleted());
    }

    @Test
    public void rerollingAChestSpendsTheChargeAndOffersOthers() {
        start(NodeChest.class);
        aetherwork(AetherEffect.FATEBENDER);
        Assert.assertEquals(run.getRunEffectCharges(AetherEffect.FATEBENDER.getId()), 1);
        decisions.then("chooseChest", new Pick<>(true, null))
                .then("chooseChest", args -> {
                    final List<?> loot = (List<?>) args[0];
                    Assert.assertEquals(loot.size(), 2);
                    Assert.assertFalse(loot.contains(ChestEffect.BANNER_OF_CHAMPIONS));
                    Assert.assertFalse(loot.contains(ChestEffect.CARD_CACHE_GAMECHANGER));
                    return loot(null);
                });
        flow.enter();
        Assert.assertEquals(run.getRunEffectCharges(AetherEffect.FATEBENDER.getId()), 0);
        Assert.assertEquals(decisions.asked, List.of("chooseChest", "chooseChest"));
    }

    @Test
    public void aChestThatRewardsCardsAsksForThem() {
        final RoguePathNode chest = start(NodeChest.class);
        final PaperCard[] chosen = new PaperCard[1];
        decisions.then("chooseChest", loot(ChestEffect.CARD_CACHE_GAMECHANGER))
                .then("show", args -> {
                    chosen[0] = (PaperCard) ((List<?>) args[1]).get(0);
                    return List.of(chosen[0]);
                });
        flow.enter();
        Assert.assertEquals(decisions.asked, List.of("chooseChest", "show"));
        Assert.assertEquals(copies(run, chosen[0]), 1);
        Assert.assertTrue(chest.isCompleted());
        Assert.assertEquals(copies(saved(), chosen[0]), 1);
    }

    @Test
    public void anEventChoiceAppliesAndItsResultIsShown() {
        final RoguePathNode event = start(NodeEvent.class);
        decisions.then("chooseEvent", choice(RogueEvent.WANDERING_HEALER, EventEffect.HEALER_POTION))
                .then("showResult", args -> {
                    Assert.assertEquals(args[0], ResultKind.EVENT);
                    Assert.assertFalse(event.isCompleted(), "the node completed before its result was read");
                    return null;
                });
        flow.enter();
        Assert.assertEquals(run.getCurrentGold(), 3);
        Assert.assertEquals(run.getCurrentLife(), 22);
        Assert.assertEquals(decisions.asked, List.of("chooseEvent", "showResult"));
        Assert.assertTrue(event.isCompleted());
    }

    /** Keep to your own path costs 5 life. */
    private NodeEvent startCrookedCounselAt3Life() {
        final NodeEvent event = (NodeEvent) start(NodeEvent.class);
        event.setEvent(RogueEvent.CROOKED_COUNSEL);
        run.setCurrentLife(3);
        decisions.then("chooseEvent", choice(RogueEvent.CROOKED_COUNSEL, EventEffect.CROOKED_COUNSEL_RING));
        return event;
    }

    @Test
    public void anEventBelowZeroLifeEndsTheRun() {
        final NodeEvent event = startCrookedCounselAt3Life();
        flow.enter();
        Assert.assertEquals(decisions.asked, List.of("chooseEvent", "showResult", "runEnded"));
        Assert.assertEquals(run.getRunState(), RogueRunState.LOST);
        Assert.assertFalse(event.isCompleted());
        Assert.assertNull(flow.run());
        final List<RogueRunHistoryEntry> history = RogueMetaProgress.getInstance().getRunHistory();
        Assert.assertEquals(history.size(), 1);
        Assert.assertEquals(history.get(0).getOutcome(), "DEFEAT");
        Assert.assertEquals(history.get(0).getBossOrDefeatedBy(), RogueEvent.CROOKED_COUNSEL.getDisplayName());
        Assert.assertTrue(RogueIO.loadMostRecentContinuableRun().isEmpty());
    }

    @Test
    public void aReviveAtASideNodeCompletesTheNode() {
        final NodeEvent event = startCrookedCounselAt3Life();
        aetherwork(AetherEffect.CONTINUUM_ENGINE);
        decisions.then("showResult", args -> {
            Assert.assertEquals(args[0], ResultKind.REVIVE);
            return null;
        }).then("showResult", args -> {
            Assert.assertEquals(args[0], ResultKind.EVENT);
            return null;
        });
        flow.enter();
        Assert.assertEquals(decisions.asked, List.of("chooseEvent", "showResult", "showResult"));
        Assert.assertEquals(run.getCurrentLife(), 5);
        Assert.assertEquals(run.getRunEffectCharges(AetherEffect.CONTINUUM_ENGINE.getId()), 0);
        Assert.assertTrue(event.isCompleted());
        Assert.assertSame(flow.run(), run);
    }

    @Test
    public void restingHealsAndCuresWounds() {
        final RoguePathNode sanctum = start(NodeSanctum.class);
        run.addWound(WoundEffect.HEAD);
        // From level 3 Tyvar tells no story at a second sanctum
        RogueMetaProgress.getInstance().setNPCLevel(NPC.TYVAR.id, 3);
        decisions.then("chooseSanctum", HEAL);
        flow.enter();
        Assert.assertEquals(run.getCurrentLife(), 19);
        Assert.assertTrue(run.getActiveWoundEffects().isEmpty());
        Assert.assertTrue(sanctum.isCompleted());

        // The next node is the sanctum below, with 4 life missing
        decisions.then("chooseSanctum", HEAL);
        flow.enter();
        Assert.assertEquals(run.getCurrentLife(), 23);
        Assert.assertEquals(saved().getCurrentLife(), 23);
    }

    @Test
    public void cookingWithNoFoodSaysSoAndCompletes() {
        final RoguePathNode sanctum = start(NodeSanctum.class);
        // A Food already in the deck is not cooked again
        run.addCardsToDeck(run.getAllCardsForActiveCommander(c -> c.getRules().getType().hasSubtype("Food")), false);
        decisions.then("chooseSanctum", new SanctumAnswer(SanctumAction.COOK, null));
        flow.enter();
        Assert.assertEquals(decisions.asked, List.of("chooseSanctum", "showMessage"));
        Assert.assertTrue(run.getCarryCards().isEmpty());
        Assert.assertTrue(sanctum.isCompleted());
    }

    @Test
    public void aPurchaseSpendsItsPriceAndAddsTheCard() {
        final RoguePathNode bazaar = start(NodeBazaar.class);
        final BazaarItem[] bought = new BazaarItem[1];
        decisions.then("shop", args -> {
            final ShopOffer offer = (ShopOffer) args[0];
            Assert.assertEquals(offer.gold(), 8);
            Assert.assertEquals(offer.items().size(), 10);
            bought[0] = offer.items().get(0);
            return new ShopAnswer(false, Set.of(bought[0]), 0);
        });
        flow.enter();
        Assert.assertTrue(bought[0].getPrice() > 0);
        Assert.assertEquals(run.getCurrentGold(), 8 - bought[0].getPrice());
        Assert.assertEquals(copies(run, bought[0].card()), 1);
        Assert.assertEquals(run.getRemovalCredits(), 1);
        Assert.assertTrue(bazaar.isCompleted());
    }

    @Test
    public void aBazaarRerollCostsTwoThenFour() {
        start(NodeBazaar.class);
        run.setCurrentGold(10);
        for (final int cost : new int[] {2, 4}) {
            decisions.then("shop", args -> {
                final ShopOffer offer = (ShopOffer) args[0];
                Assert.assertEquals(offer.rerollCost(), cost);
                Assert.assertTrue(offer.rerollEnabled());
                return new ShopAnswer(true, Set.of(), 1);
            });
        }
        decisions.then("shop", args -> {
            final ShopOffer offer = (ShopOffer) args[0];
            Assert.assertEquals(offer.gold(), 4);
            Assert.assertEquals(offer.rerollCost(), 6);
            Assert.assertFalse(offer.rerollEnabled());
            Assert.assertEquals(offer.tab(), 1, "a reroll keeps the tab");
            return LEAVE;
        });
        flow.enter();
        Assert.assertEquals(decisions.asked, List.of("shop", "shop", "shop"));
        Assert.assertEquals(run.getCurrentGold(), 4);
    }

    /** Seeds the random so that the draw comes out true when the flow makes it next. */
    private static void seedFor(final BooleanSupplier draw) {
        long seed = 0;
        do {
            MyRandom.setRandom(new Random(++seed));
        } while (!draw.getAsBoolean());
        MyRandom.setRandom(new Random(seed));
    }

    private void boardTakesTo(final int index) {
        seedFor(() -> MyRandom.getRandom().nextInt(run.getPath().getNodeCount()) == index);
    }

    @Test
    public void movingByEventEntersTheTargetNode() {
        start(NodeEvent.class);
        final List<RoguePathNode> nodes = run.getPath().getNodes();
        final RoguePathNode event = nodes.get(3);
        ((NodeEvent) event).setEvent(RogueEvent.ON_THE_EDGE);
        event.setCompleted(false);
        run.setCurrentNodeIndex(3);
        final RoguePathNode sanctum = nodes.get(11);
        boardTakesTo(11);
        decisions.then("chooseEvent", choice(RogueEvent.ON_THE_EDGE, EventEffect.ON_THE_EDGE_BOARD))
                .then("chooseSanctum", args -> {
                    Assert.assertSame(((SanctumOffer) args[0]).run(), run);
                    Assert.assertSame(run.getCurrentNode(), sanctum);
                    Assert.assertTrue(run.getPath().getNodesInRow(1).stream().noneMatch(RoguePathNode::isCompleted));
                    return HEAL;
                });
        flow.enter();
        Assert.assertEquals(decisions.asked, List.of("chooseEvent", "chooseSanctum"));
        Assert.assertEquals(run.getCurrentLife(), 19);
        Assert.assertTrue(sanctum.isCompleted());
        Assert.assertFalse(event.isCompleted());
    }

    private RogueMatch startedMatch() {
        final RogueMatch[] started = new RogueMatch[1];
        decisions.then("startMatch", args -> {
            started[0] = (RogueMatch) args[0];
            return null;
        });
        flow.enter();
        Assert.assertNotNull(started[0], "no match in " + decisions.asked);
        return started[0];
    }

    @Test
    public void enteringAPlaneAnnouncesThenStarts() {
        final NodePlanebound plane = (NodePlanebound) start(NodePlanebound.class);
        plane.setWrathfulCount(2);
        plane.setCursedCount(1);
        decisions.then("showPlaneboundEffects", args -> {
            Assert.assertSame(args[0], plane);
            Assert.assertEquals(run.getActiveWrathfulEffects(), args[1]);
            Assert.assertEquals(run.getActiveCursedEffects(), args[2]);
            Assert.assertEquals(run.getActiveWrathfulEffects().size(), 2);
            Assert.assertEquals(run.getActiveCursedEffects().size(), 1);
            return null;
        });
        Assert.assertSame(startedMatch().node(), plane);
        Assert.assertEquals(decisions.asked, List.of("showPlaneboundEffects", "startMatch"));
    }

    @Test
    public void theMatchHasBothDecksAndTheRunsLife() {
        final NodePlanebound plane = (NodePlanebound) start(NodePlanebound.class);
        final RogueMatch match = startedMatch();
        Assert.assertEquals(match.human().getStartingLife(), 14);
        Assert.assertEquals(match.human().getDeck().getCommanders(), run.getCurrentDeck().getCommanders());
        Assert.assertEquals(match.human().getDeck().getMain(), run.getCurrentDeck().getMain());
        Assert.assertEquals(match.variants(), Set.of(GameType.Commander, GameType.Planechase));
        Assert.assertSame(match.players().get(0), match.human());
        // 5 for each of the two rows with planes
        Assert.assertEquals(match.players().get(1).getStartingLife(), 10);
        Assert.assertEquals(match.players().get(1).getDeck().getCommanders(),
                RogueConfig.loadPlaneboundDeck(plane.getRoguePlanebound()).getCommanders());

        run.addCustomRunModification(CustomRunModification.VOID);
        Assert.assertEquals(startedMatch().variants(), Set.of(GameType.Commander));
    }

    /** At level 665 the next choice brings Henzie's story. The contract he offers instead of the event is rerolled to the given event. */
    private void startAnEventWithAStory(final RogueEvent event) {
        start(NodeEvent.class);
        RogueMetaProgress.getInstance().setNPCLevel(NPC.HENZIE.id, 665);
        decisions.then("chooseEvent", args -> {
            seedFor(() -> {
                final List<RogueEvent> others = new ArrayList<>(List.of(RogueEvent.values()));
                others.removeIf(e -> !e.isAvailable() || e == args[0]);
                Collections.shuffle(others, MyRandom.getRandom());
                return others.get(0) == event;
            });
            return new Pick<>(true, null);
        }).then("chooseBoon", new Pick<>(false, null));
    }

    @Test
    public void anEventBattleTellsItsStoryFirst() {
        startAnEventWithAStory(RogueEvent.AMBUSH);
        decisions.then("chooseEvent", args -> {
            Assert.assertEquals(args[0], RogueEvent.AMBUSH);
            return choice(RogueEvent.AMBUSH, EventEffect.AMBUSH_FIGHT);
        });
        Assert.assertSame(startedMatch().run(), run);
        Assert.assertEquals(decisions.asked, List.of("chooseEvent", "chooseEvent", "chooseBoon", "startMatch"));
    }

    @Test
    public void movingOntoABattleStartsItOnce() {
        startAnEventWithAStory(RogueEvent.ON_THE_EDGE);
        final NodePlanebound elite = (NodePlanebound) run.getPath().getNode(10);
        elite.setCursedCount(1);
        decisions.then("chooseEvent", args -> {
            Assert.assertEquals(args[0], RogueEvent.ON_THE_EDGE);
            boardTakesTo(10);
            return choice(RogueEvent.ON_THE_EDGE, EventEffect.ON_THE_EDGE_BOARD);
        });
        Assert.assertSame(startedMatch().node(), elite);
        Assert.assertEquals(decisions.asked,
                List.of("chooseEvent", "chooseEvent", "chooseBoon", "showPlaneboundEffects", "startMatch"));
    }

    @Test
    public void aMatchThatCannotStartReloadsTheSave() {
        final NodePlanebound plane = (NodePlanebound) start(NodePlanebound.class);
        run.addWrathful(WrathfulEffect.AGGRESSION);
        RogueIO.saveRun(run);
        plane.setCursedCount(1);
        decisions.then("startMatch", args -> {
            Assert.assertTrue(run.getActiveWrathfulEffects().isEmpty(), "the match spends the effect");
            run.setHostedMatch(new HostedMatch());
            throw new IllegalStateException("no match today");
        }).then("mapChanged", args -> {
            Assert.assertSame(args[0], flow.run());
            decisions.asked.add("mapChanged");
            return null;
        });
        flow.enter();
        Assert.assertEquals(decisions.asked, List.of("showPlaneboundEffects", "startMatch", "showMessage", "mapChanged"));
        Assert.assertNotSame(flow.run(), run);
        Assert.assertNull(run.getHostedMatch());
        Assert.assertEquals(flow.run().getName(), run.getName());
        Assert.assertEquals(flow.run().getActiveWrathfulEffects(), List.of(WrathfulEffect.AGGRESSION));
        Assert.assertTrue(flow.run().getActiveCursedEffects().isEmpty());
        Assert.assertEquals(((NodePlanebound) flow.run().getCurrentNode()).getCursedCount(), 0);

        Assert.assertSame(startedMatch().run(), flow.run());
    }

    @Test
    public void onlyReachableNodesCanBeSelected() {
        start(NodeSanctum.class);
        Assert.assertFalse(flow.select(5));
        Assert.assertEquals(run.getCurrentNodeIndex(), 6);

        run.addChestEffect(ChestEffect.RELIC_OF_PATHFINDING);
        Assert.assertTrue(flow.select(5));
        Assert.assertEquals(run.getCurrentNodeIndex(), 5);
        Assert.assertFalse(flow.select(10), "a node in the next row");
        Assert.assertFalse(flow.select(-1));
        Assert.assertEquals(run.getCurrentNodeIndex(), 5);
    }

    @Test
    public void rerollingChangesThePlaneAndSaves() {
        final NodePlanebound plane = (NodePlanebound) start(NodePlanebound.class);
        final RoguePlanebound before = plane.getRoguePlanebound();
        Assert.assertEquals(flow.rerollsLeft(), 0);
        Assert.assertFalse(flow.rerollPlane());
        Assert.assertSame(plane.getRoguePlanebound(), before);

        aetherwork(AetherEffect.PLANEBENDER);
        Assert.assertEquals(flow.rerollsLeft(), 1);
        final List<RoguePlanebound> candidates = flow.rerollCandidates();
        Assert.assertTrue(flow.rerollPlane());
        final RoguePlanebound after = plane.getRoguePlanebound();
        Assert.assertNotEquals(after, before);
        Assert.assertEquals(after.type(), before.type());
        Assert.assertTrue(candidates.contains(after));
        Assert.assertEquals(flow.rerollsLeft(), 0);

        final RogueRun saved = saved();
        Assert.assertEquals(((NodePlanebound) saved.getCurrentNode()).getRoguePlanebound(), after);
        Assert.assertEquals(saved.getRunEffectCharges(AetherEffect.PLANEBENDER.getId()), 0);
    }

    @Test
    public void abandoningRecordsAndDeletes() {
        start(NodeSanctum.class);
        flow.abandon();
        final List<RogueRunHistoryEntry> history = RogueMetaProgress.getInstance().getRunHistory();
        Assert.assertEquals(history.size(), 1);
        Assert.assertEquals(history.get(0).getOutcome(), "ABANDONED");
        Assert.assertFalse(RogueIO.getRogueFile(run).exists());
        Assert.assertEquals(decisions.asked, List.of("runEnded"));
        Assert.assertTrue(flow.load().isEmpty());
    }

    @Test
    public void goingEndlessRaisesTheLevelAndAsksForABoon() {
        start(NodeSanctum.class);
        run.setCurrentNodeIndex(15);
        run.getCurrentNode().setCompleted(true);
        final RogueMetaProgress progress = RogueMetaProgress.getInstance();
        progress.setNPCLevel(NPC.TYVAR.id, 3);
        Assert.assertFalse(flow.goEndless());
        Assert.assertEquals(run.getEndlessLevel(), 0);
        Assert.assertEquals(run.getCurrentNodeIndex(), 15);

        progress.unlockEndlessMode();
        decisions.then("chooseBoon", new Pick<>(false, NPCEffect.TYVAR_BRAWLER));
        Assert.assertTrue(flow.goEndless());
        Assert.assertEquals(decisions.asked, List.of("chooseBoon"));
        final RogueRun saved = saved();
        Assert.assertEquals(saved.getEndlessLevel(), 1);
        Assert.assertEquals(saved.getCurrentNodeIndex(), 0);
        Assert.assertEquals(saved.getPath().getCompletedCount(), 0);
        Assert.assertEquals(saved.getActiveNPCEffects(), List.of(NPCEffect.TYVAR_BRAWLER));
    }
}
