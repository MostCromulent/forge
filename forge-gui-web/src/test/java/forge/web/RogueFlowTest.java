package forge.web;

import forge.gamemodes.rogue.RogueEvent;
import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueRunHistoryEntry;
import forge.gamemodes.rogue.RogueRunState;
import forge.gamemodes.rogue.effect.AetherEffect;
import forge.gamemodes.rogue.effect.BazaarItem;
import forge.gamemodes.rogue.effect.ChestEffect;
import forge.gamemodes.rogue.effect.EventEffect;
import forge.gamemodes.rogue.effect.WoundEffect;
import forge.gamemodes.rogue.flow.RogueDecisions.Pick;
import forge.gamemodes.rogue.flow.RogueDecisions.ResultKind;
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

import java.util.List;
import java.util.Random;
import java.util.Set;

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

    @Test
    public void movingByEventEntersTheTargetNode() {
        start(NodeEvent.class);
        final List<RoguePathNode> nodes = run.getPath().getNodes();
        final RoguePathNode event = nodes.get(3);
        ((NodeEvent) event).setEvent(RogueEvent.ON_THE_EDGE);
        event.setCompleted(false);
        run.setCurrentNodeIndex(3);
        final RoguePathNode sanctum = nodes.get(11);
        // Board rolls an index of the 16 nodes
        MyRandom.setRandom(new Random(1) {
            @Override
            public int nextInt(final int bound) {
                return bound == 16 ? 11 : super.nextInt(bound);
            }
        });
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
}
