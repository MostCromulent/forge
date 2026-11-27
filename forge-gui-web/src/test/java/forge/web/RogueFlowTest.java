package forge.web;

import forge.LobbyPlayer;
import forge.game.GameType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.rogue.RogueConfig;
import forge.gamemodes.rogue.RogueEvent;
import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueRun.CarryCardType;
import forge.gamemodes.rogue.RogueRunHistoryEntry;
import forge.gamemodes.rogue.RogueRunState;
import forge.gamemodes.rogue.RogueWinLoseController;
import forge.gamemodes.rogue.effect.ChestEffect;
import forge.gamemodes.rogue.effect.CustomRunModification;
import forge.gamemodes.rogue.effect.EventEffect;
import forge.gamemodes.rogue.effect.WrathfulEffect;
import forge.gamemodes.rogue.flow.RogueDecisions.Pick;
import forge.gamemodes.rogue.flow.RogueDecisions.RogueMatch;
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
import forge.gui.interfaces.IButton;
import forge.gui.interfaces.IWinLoseView;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;

@Test(singleThreaded = true)
public class RogueFlowTest {
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
    public void aMatchLeftWithNoResultGivesThePlayerBackTheirLooks() {
        final LobbyPlayer human = GamePlayerUtil.getGuiPlayer();
        final String name = human.getName();
        final int avatar = human.getAvatarIndex();
        final int sleeve = human.getSleeveIndex();
        start(NodePlanebound.class);
        startedMatch();
        Assert.assertEquals(human.getName(), run.getCurrentCommanderName());
        flow.setRun(null);
        flow.load();
        Assert.assertEquals(human.getName(), name);
        Assert.assertEquals(human.getAvatarIndex(), avatar);
        Assert.assertEquals(human.getSleeveIndex(), sleeve);
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

    /** A result screen that runs the rewards at once and shows nothing. */
    private static IWinLoseView<?> resultScreen() {
        final ClassLoader loader = IWinLoseView.class.getClassLoader();
        final Object button = Proxy.newProxyInstance(loader, new Class<?>[] {IButton.class}, (proxy, method, args) -> null);
        return (IWinLoseView<?>) Proxy.newProxyInstance(loader, new Class<?>[] {IWinLoseView.class}, (proxy, method, args) -> {
            if (method.getName().equals("showRewards")) {
                ((Runnable) args[0]).run();
            }
            return method.getReturnType() == IButton.class ? button : null;
        });
    }

    private RogueWinLoseController fight(final boolean win) {
        final RogueMatch match = startedMatch();
        run.setHostedMatch(new HostedMatch());
        return flow.matchOver(RogueFixture.finished(match, win), resultScreen());
    }

    private void recordMapChanges() {
        decisions.then("mapChanged", args -> {
            Assert.assertSame(args[0], run);
            decisions.asked.add("mapChanged");
            return null;
        });
    }

    private void startBoss() {
        start(NodePlanebound.class);
        run.setCurrentNodeIndex(15);
    }

    private static RogueRunHistoryEntry onlyHistoryEntry() {
        final List<RogueRunHistoryEntry> history = RogueMetaProgress.getInstance().getRunHistory();
        Assert.assertEquals(history.size(), 1);
        return history.get(0);
    }

    @Test
    public void aWonBattleGivesGoldEchoesAndCards() {
        start(NodePlanebound.class);
        run.setCurrentNodeIndex(10);
        run.addCarryCard(FModel.getMagicDb().getCommonCards().getCard("Sol Ring"), CarryCardType.ITEM, "test");
        final List<PaperCard> chosen = new ArrayList<>();
        for (final int cards : new int[] {7, 3}) {
            decisions.then("show", args -> {
                final List<?> offered = (List<?>) args[1];
                Assert.assertEquals(offered.size(), cards);
                chosen.add((PaperCard) offered.get(0));
                return List.of(offered.get(0));
            });
        }
        final RogueWinLoseController result = fight(true);
        Assert.assertEquals(decisions.asked, List.of("startMatch", "show", "show"));
        Assert.assertEquals(flow.resultTitle(result), "Battle Won!");
        Assert.assertFalse(flow.offersEnding(result));
        Assert.assertEquals(RogueMetaProgress.getInstance().getTotalEchoes(), 4);

        final RogueRun saved = saved();
        Assert.assertEquals(saved.getCurrentGold(), 12);
        Assert.assertEquals(saved.getCurrentLife(), 14);
        Assert.assertEquals(saved.getCarryCards().size(), 1);
        chosen.forEach(card -> Assert.assertEquals(copies(saved, card), 1, card.getName()));
        Assert.assertTrue(saved.getPath().getNode(10).isCompleted());
        Assert.assertEquals(saved.getCurrentNodeIndex(), 15);

        recordMapChanges();
        flow.resultLeft(result, false);
        Assert.assertEquals(decisions.asked, List.of("startMatch", "show", "show", "mapChanged"));
        Assert.assertNull(run.getHostedMatch());
    }

    @Test
    public void aLostBattleEndsTheRun() {
        final NodePlanebound plane = (NodePlanebound) start(NodePlanebound.class);
        final RogueWinLoseController result = fight(false);
        Assert.assertEquals(flow.resultTitle(result), "Run Lost!");
        Assert.assertEquals(run.getRunState(), RogueRunState.LOST);
        Assert.assertEquals(onlyHistoryEntry().getOutcome(), "DEFEAT");
        Assert.assertEquals(onlyHistoryEntry().getBossOrDefeatedBy(), plane.getRoguePlanebound().planeboundName());
        flow.resultLeft(result, false);
        Assert.assertEquals(decisions.asked, List.of("startMatch", "runEnded"));
        Assert.assertNull(flow.run());
        Assert.assertTrue(flow.load().isEmpty());
    }

    @Test
    public void theBossOffersAnEnding() {
        startBoss();
        RogueWinLoseController result = fight(true);
        Assert.assertEquals(flow.resultTitle(result), "Run Completed!");
        Assert.assertTrue(flow.offersEnding(result));
        Assert.assertEquals(run.getRunState(), RogueRunState.STARTED, "the player has not chosen yet");
        flow.resultLeft(result, false);
        Assert.assertEquals(decisions.asked, List.of("startMatch", "runEnded"));
        Assert.assertEquals(run.getRunState(), RogueRunState.WON);
        Assert.assertEquals(onlyHistoryEntry().getOutcome(), "VICTORY");
        Assert.assertNull(flow.run());

        startBoss();
        RogueMetaProgress.getInstance().unlockEndlessMode();
        result = fight(true);
        recordMapChanges();
        flow.resultLeft(result, true);
        Assert.assertEquals(decisions.asked, List.of("startMatch", "runEnded", "startMatch", "mapChanged"));
        Assert.assertEquals(saved().getEndlessLevel(), 1);
        Assert.assertEquals(saved().getCurrentNodeIndex(), 0);
        onlyHistoryEntry();
    }
}
