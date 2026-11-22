package forge.web;

import forge.gamemodes.rogue.RogueCustomDeckGenerator;
import forge.gamemodes.rogue.RogueDeck;
import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueRunHistoryEntry;
import forge.gamemodes.rogue.RogueStats;
import forge.gamemodes.rogue.effect.CustomRunModification;
import forge.gamemodes.rogue.effect.DescensionLevel;
import forge.gamemodes.rogue.effect.NPCEffect;
import forge.gamemodes.rogue.flow.RogueDecisions.CardChoice;
import forge.gamemodes.rogue.flow.RogueDecisions.CardChoiceKind;
import forge.gamemodes.rogue.flow.RogueDecisions.Pick;
import forge.gamemodes.rogue.flow.RogueFlow;
import forge.gamemodes.rogue.flow.RogueStart;
import forge.gamemodes.rogue.npc.NPC;
import forge.gamemodes.rogue.npc.NPCContext;
import forge.gamemodes.rogue.path.NodeSanctum;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

@Test(singleThreaded = true)
public class RogueStartTest {
    private ScriptedDecisions decisions;
    private RogueFlow flow;
    private RogueMetaProgress progress;
    private RogueDeck unlocked;

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @BeforeMethod
    public void clean() {
        RogueFixture.clean();
        ForgePreferences.DEV_MODE = false;
        MyRandom.setRandom(new Random(1));
        decisions = new ScriptedDecisions();
        flow = new RogueFlow(decisions);
        progress = RogueMetaProgress.getInstance();
        unlocked = RogueStart.roster(false).get(0);
    }

    @AfterMethod(alwaysRun = true)
    public void plainRandom() {
        MyRandom.setRandom(new Random());
    }

    private static RogueStart normal(final RogueDeck deck, final int descension) {
        return new RogueStart(deck, false, Set.of(), descension);
    }

    private RogueStart custom(final CustomRunModification... modifications) {
        return new RogueStart(unlocked, true, Set.of(modifications), 0);
    }

    private void unlockCustomRuns() {
        progress.updateStat(RogueStats.RUNS_WON, 2, new RogueRun(unlocked));
    }

    private RogueRun unfinishedRun() {
        final RogueRun run = RogueFixture.run(NodeSanctum.class);
        flow.setRun(run);
        return run;
    }

    /** From level 3 Tyvar offers a boon at the start of a run. */
    private void tyvarOffersABoon() {
        progress.setNPCLevel(NPC.TYVAR.id, 3);
    }

    private void assertUntouched(final RogueRun old) {
        Assert.assertTrue(RogueIO.getRogueFile(old).exists());
        Assert.assertEquals(RogueIO.getRogueFiles().length, 1);
        Assert.assertTrue(progress.getRunHistory().isEmpty());
        Assert.assertSame(flow.run(), old);
    }

    @Test
    public void conflictingModificationsCannotBegin() {
        Assert.assertFalse(custom(CustomRunModification.RICH).canBegin(progress), "custom runs are locked");
        unlockCustomRuns();
        Assert.assertTrue(custom(CustomRunModification.RICH).canBegin(progress));
        Assert.assertTrue(custom(CustomRunModification.PATH_OF_THE_BANNED).canBegin(progress));
        Assert.assertFalse(custom(CustomRunModification.RICH, CustomRunModification.PATH_OF_THE_BANNED).canBegin(progress));
        Assert.assertFalse(custom().canBegin(progress));
    }

    @Test
    public void generatedCommandersBringTheirOwnModifications() {
        unlockCustomRuns();
        Assert.assertTrue(flow.begin(custom(CustomRunModification.MANIA)));
        final RogueRun mania = flow.run();

        MyRandom.setRandom(new Random(1));
        decisions.then("confirmReplaceRun", true);
        Assert.assertTrue(flow.begin(custom(CustomRunModification.MANIA, CustomRunModification.RANDOM_START)));
        Assert.assertNotSame(flow.run(), mania);
        Assert.assertEquals(flow.run().getActiveCustomRunModifications(), List.of(CustomRunModification.MANIA));
        Assert.assertEquals(flow.run().getSelectedRogueDeck().getStartDeck().getAllCardsInASinglePool(),
                mania.getSelectedRogueDeck().getStartDeck().getAllCardsInASinglePool());
    }

    @Test
    public void onlyTheNextDescensionLevelCanBeStarted() {
        Assert.assertTrue(normal(unlocked, 0).canBegin(progress));
        final List<RogueRun> won = new ArrayList<>();
        for (final RogueDeck deck : RogueStart.roster(false).subList(0, 3)) {
            won.add(new RogueRun(deck));
        }
        won.get(0).setDescensionLevel(1);
        progress.recordDescensionWin(won.get(0));
        progress.mergeRunsWonPerCommander(won.get(0));
        progress.mergeRunsWonPerCommander(won.get(1));
        Assert.assertFalse(normal(unlocked, 1).canBegin(progress), "Descension needs three Commanders");
        progress.mergeRunsWonPerCommander(won.get(2));
        Assert.assertEquals(progress.getMaxDescensionWon(unlocked.getCommanderCardName()), 1);

        Assert.assertTrue(normal(unlocked, 2).canBegin(progress));
        Assert.assertFalse(normal(unlocked, 3).canBegin(progress));
        unlockCustomRuns();
        final Set<CustomRunModification> rich = Set.of(CustomRunModification.RICH);
        Assert.assertTrue(new RogueStart(unlocked, true, rich, 7).canBegin(progress));
        Assert.assertFalse(new RogueStart(unlocked, true, rich, DescensionLevel.getMaxLevel() + 1).canBegin(progress));
    }

    @Test
    public void aLockedCommanderCannotStart() {
        final List<RogueDeck> roster = RogueStart.roster(false);
        final RogueDeck locked = roster.get(roster.size() - 1);
        Assert.assertTrue(unlocked.isUnlocked());
        Assert.assertFalse(locked.isUnlocked());
        Assert.assertFalse(flow.begin(normal(locked, 0)));
        Assert.assertNull(flow.run());
        Assert.assertEquals(RogueIO.getRogueFiles().length, 0);

        unlockCustomRuns();
        Assert.assertFalse(new RogueStart(locked, true, Set.of(CustomRunModification.RICH), 0).canBegin(progress));
        Assert.assertTrue(new RogueStart(locked, true, Set.of(CustomRunModification.MANIA), 0).canBegin(progress),
                "a generated Commander takes its place");
    }

    @Test
    public void startingAsksInOrder() {
        unlockCustomRuns();
        tyvarOffersABoon();
        final RogueRun old = unfinishedRun();
        final PaperCard commander = RogueCustomDeckGenerator.getEligibleCommanders().get(0);
        final List<PaperCard> picks = new ArrayList<>();
        decisions.then("chooseCommanders", List.of(commander)).then("chooseCards", args -> {
            final CardChoice choice = (CardChoice) args[0];
            Assert.assertEquals(choice.kind(), CardChoiceKind.STARTING_DECK);
            Assert.assertEquals(choice.min(), 25);
            Assert.assertEquals(choice.max(), 25);
            picks.addAll(choice.candidates().subList(0, 25));
            Assert.assertEquals(choice.preview().apply(picks).getMain().countAll(), 25);
            return picks;
        }).then("choosePortrait", 42).then("confirmReplaceRun", true).then("chooseBoon", new Pick<>(false, null));
        Assert.assertTrue(flow.begin(custom(CustomRunModification.CHOSEN_ONE, CustomRunModification.SEALED)));
        Assert.assertEquals(decisions.asked,
                List.of("chooseCommanders", "chooseCards", "choosePortrait", "confirmReplaceRun", "chooseBoon"));

        final RogueRun run = flow.run();
        Assert.assertEquals(run.getSelectedRogueDeck().getAvatarIndex(), 42);
        Assert.assertEquals(run.getCurrentDeck().getCommanders().get(0).getName(), commander.getName());
        for (final PaperCard pick : picks) {
            Assert.assertTrue(run.getCurrentDeck().getMain().contains(pick), pick.getName());
        }
        Assert.assertFalse(RogueIO.getRogueFile(old).exists());
        Assert.assertTrue(RogueIO.getRogueFile(run).exists());
        final List<RogueRunHistoryEntry> history = progress.getRunHistory();
        Assert.assertEquals(history.size(), 1);
        Assert.assertEquals(history.get(0).getOutcome(), "ABANDONED");
        Assert.assertEquals(history.get(0).getCommanderName(), old.getCurrentCommanderName());
    }

    @Test
    public void theOldRunStaysUntilTheNewOneIsSaved() {
        tyvarOffersABoon();
        final RogueRun old = unfinishedRun();
        decisions.then("confirmReplaceRun", true).then("chooseBoon", args -> {
            throw new IllegalStateException("the player left");
        });
        Assert.assertThrows(IllegalStateException.class, () -> flow.begin(normal(unlocked, 0)));
        assertUntouched(old);
    }

    @Test
    public void refusingToReplaceChangesNothing() {
        final RogueRun old = unfinishedRun();
        decisions.then("confirmReplaceRun", false);
        Assert.assertFalse(flow.begin(normal(unlocked, 0)));
        assertUntouched(old);
    }

    @Test
    public void aDeckThatCannotBeGeneratedChangesNothing() {
        unlockCustomRuns();
        final RogueRun old = unfinishedRun();
        decisions.then("chooseCommanders", List.of(FModel.getMagicDb().getCommonCards().getCard("Forest")));
        Assert.assertFalse(flow.begin(custom(CustomRunModification.CHOSEN_ONE)));
        Assert.assertEquals(decisions.asked, List.of("chooseCommanders", "showMessage"));
        assertUntouched(old);
    }

    @Test
    public void aBoonIsAppliedAndTheRunIsSaved() {
        tyvarOffersABoon();
        decisions.then("chooseBoon", new Pick<>(false, NPCEffect.TYVAR_BRAWLER)).then("runStarted", args -> {
            Assert.assertSame(args[0], flow.run());
            decisions.asked.add("runStarted");
            return null;
        });
        Assert.assertTrue(flow.begin(normal(unlocked, 0)));
        Assert.assertEquals(decisions.asked, List.of("chooseBoon", "runStarted"));
        final RogueRun saved = RogueIO.loadMostRecentContinuableRun().orElseThrow(AssertionError::new);
        Assert.assertEquals(saved.getName(), flow.run().getName());
        Assert.assertEquals(saved.getActiveNPCEffects(), List.of(NPCEffect.TYVAR_BRAWLER));
        Assert.assertEquals(saved.getCurrentCommanderName(), unlocked.getCommanderCardName());
    }

    @Test
    public void aStoryBetweenRunsIsToldOnce() {
        decisions.then("chooseBoon", args -> {
            final NPCContext story = (NPCContext) args[0];
            Assert.assertEquals(story.npc(), NPC.TEFERI);
            Assert.assertTrue(story.choices().isEmpty());
            return new Pick<>(false, null);
        });
        flow.betweenRuns();
        Assert.assertEquals(decisions.asked, List.of("chooseBoon"));
        flow.betweenRuns();
        Assert.assertEquals(decisions.asked, List.of("chooseBoon"));
    }
}
