package forge.web;

import forge.gamemodes.rogue.CodexHelper;
import forge.gamemodes.rogue.CodexHelper.CodexState;
import forge.gamemodes.rogue.CodexHelper.JournalEntry;
import forge.gamemodes.rogue.KeywordHint;
import forge.gamemodes.rogue.PreviewReferenceType;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RoguePlanebound;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueTutorial;
import forge.gamemodes.rogue.TextHelper;
import forge.gamemodes.rogue.TextHelper.Part;
import forge.gamemodes.rogue.effect.AetherEffect;
import forge.gamemodes.rogue.effect.ChestEffect;
import forge.gamemodes.rogue.effect.EffectResultContext;
import forge.gamemodes.rogue.effect.EventEffect;
import forge.gamemodes.rogue.effect.NPCEffect;
import forge.gamemodes.rogue.effect.RogueEffect;
import forge.gamemodes.rogue.effect.WoundEffect;
import forge.gamemodes.rogue.npc.NPC;
import forge.gamemodes.rogue.npc.TeferiOptionalEncounter;
import forge.gamemodes.rogue.path.NodePlanebound;
import forge.item.PaperCard;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Test(singleThreaded = true)
public class RogueTextTest {
    private RogueMetaProgress progress;

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @BeforeMethod
    public void clean() {
        RogueFixture.clean();
        progress = RogueMetaProgress.getInstance();
    }

    @AfterMethod(alwaysRun = true)
    public void plainRandom() {
        MyRandom.setRandom(new Random());
    }

    private static Part text(final String text) {
        return new Part(null, text, null);
    }

    @Test
    public void aCardReferenceIsItsName() {
        Assert.assertEquals(TextHelper.parts("Gain [[The One Ring|LTR]]."), List.of(text("Gain "),
                new Part(PreviewReferenceType.CARD, "The One Ring", "The One Ring|LTR"), text(".")));
        Assert.assertEquals(TextHelper.resolveCard("The One Ring|LTR").getEdition(), "LTR");
        Assert.assertNull(TextHelper.resolveCard("No Such Card"));
    }

    @Test
    public void aHiddenReferenceHasNoText() {
        final Part ring = new Part(PreviewReferenceType.CARD, "", "Sol Ring");
        Assert.assertEquals(TextHelper.parts("Gain a card. ![[Sol Ring]]"), List.of(ring, text("Gain a card.")));
        Assert.assertEquals(TextHelper.parts("Gain ![[Sol Ring]] a card"), List.of(ring, text("Gain a card")));
        Assert.assertEquals(TextHelper.parts("{{Gold}} ![[Sol Ring]], then"), List.of(
                new Part(PreviewReferenceType.KEYWORD, "Gold", "Gold"), ring, text(", then")));
    }

    @Test
    public void aPluralKeywordResolves() {
        final Part traits = new Part(PreviewReferenceType.KEYWORD, "Traits", "Traits");
        Assert.assertEquals(TextHelper.parts("Gain 2 {{Traits}}."), List.of(text("Gain 2 "), traits, text(".")));
        Assert.assertEquals(KeywordHint.fromToken("Traits"), KeywordHint.TRAIT);
        Assert.assertEquals(KeywordHint.fromToken("Echoes"), KeywordHint.ECHOES);
        Assert.assertNull(KeywordHint.fromToken("Bananas"));
    }

    @Test
    public void everyDescriptionSplits() {
        Stream.<RogueEffect[]>of(NPCEffect.values(), ChestEffect.values(), EventEffect.values(), AetherEffect.values())
                .flatMap(Stream::of).forEach(effect -> {
                    final List<Part> parts = TextHelper.parts(effect.getRawDescription());
                    Assert.assertEquals(parts.stream().map(Part::text).collect(Collectors.joining()), effect.getDescription().replace("**", ""));
                    for (final Part part : parts) {
                        if (part.type() == PreviewReferenceType.KEYWORD) {
                            Assert.assertNotNull(KeywordHint.fromToken(part.token()), effect + " " + part);
                        } else if (part.type() == PreviewReferenceType.CARD) {
                            Assert.assertNotNull(TextHelper.resolveCard(part.token()), effect + " " + part);
                        }
                    }
                });
    }

    @Test
    public void aWoundIsNeverOnlySeen() {
        final RogueRun run = RogueFixture.run(NodePlanebound.class);
        final EffectResultContext gained = new EffectResultContext();
        WoundEffect.EYE.gainWound(run, gained);
        for (final WoundEffect wound : WoundEffect.values()) {
            Assert.assertEquals(CodexHelper.state(progress, wound),
                    wound == gained.gainedWoundEffect ? CodexState.ACQUIRED : CodexState.UNKNOWN);
        }

        CodexHelper.recordTraitChoice(ChestEffect.BANNER_OF_WAR);
        Assert.assertEquals(CodexHelper.state(progress, ChestEffect.BANNER_OF_WAR), CodexState.SEEN);
        CodexHelper.recordTraitAcquired(ChestEffect.BANNER_OF_WAR);
        Assert.assertEquals(CodexHelper.state(progress, ChestEffect.BANNER_OF_WAR), CodexState.ACQUIRED);
    }

    @Test
    public void onlyARewardCardCanBeOnlySeen() {
        final RogueRun run = RogueFixture.run(NodePlanebound.class);
        final PaperCard reward = run.getSelectedRogueDeck().getRewardPoolCards().get(0);
        Assert.assertEquals(CodexHelper.state(progress, run.getSelectedRogueDeck(), reward), CodexState.UNKNOWN);
        CodexHelper.recordCardRewardOptions(run, List.of(reward));
        Assert.assertEquals(CodexHelper.state(progress, run.getSelectedRogueDeck(), reward), CodexState.SEEN);
        CodexHelper.recordAcquiredCards(run, List.of(reward));
        Assert.assertEquals(CodexHelper.state(progress, run.getSelectedRogueDeck(), reward), CodexState.ACQUIRED);

        final RoguePlanebound planebound = ((NodePlanebound) run.getPath().getNode(0)).getRoguePlanebound();
        Assert.assertEquals(CodexHelper.state(progress, planebound, reward), CodexState.UNKNOWN);
        CodexHelper.recordPlaneboundCommanderCards(planebound, List.of(reward));
        Assert.assertEquals(CodexHelper.state(progress, planebound, reward), CodexState.ACQUIRED);
    }

    @Test
    public void theJournalListsTutorialsFirst() {
        Assert.assertTrue(CodexHelper.journal(progress).isEmpty());
        progress.setNPCLevel(NPC.TEFERI.id, 2);
        progress.markNPCEncounterSeen(TeferiOptionalEncounter.TEMPORAL_ANCHOR.getId());
        progress.markTutorialSeen(RogueTutorial.CHEST);
        final List<JournalEntry> journal = CodexHelper.journal(progress);
        Assert.assertEquals(journal.stream().map(JournalEntry::id).collect(Collectors.toList()),
                List.of(RogueTutorial.CHEST.getId(), "teferi:MEETING_TEFERI", "teferi:FIRST_EVIDENCE",
                        TeferiOptionalEncounter.TEMPORAL_ANCHOR.getId()));
        Assert.assertEquals(journal.stream().map(JournalEntry::story).collect(Collectors.toList()),
                List.of(false, true, true, true));
        journal.forEach(entry -> Assert.assertFalse(entry.text().isEmpty(), entry.title()));
    }
}
