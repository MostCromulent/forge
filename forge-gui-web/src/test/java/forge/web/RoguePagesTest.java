package forge.web;

import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueRunHistoryEntry;
import forge.gamemodes.rogue.RogueStats;
import forge.gamemodes.rogue.effect.ChestEffect;
import forge.gamemodes.rogue.effect.NPCEffect;
import forge.gamemodes.rogue.effect.RogueEffectComposite;
import forge.gamemodes.rogue.effect.WoundEffect;
import forge.gamemodes.rogue.flow.RogueFlow;
import forge.gamemodes.rogue.path.NodePlanebound;
import forge.gamemodes.rogue.path.NodeSanctum;
import forge.util.MyRandom;
import forge.web.campaign.rogue.RoguePages;
import forge.web.protocol.RogueProtocol.CodexItem;
import forge.web.protocol.RogueProtocol.CodexRow;
import forge.web.protocol.RogueProtocol.HistoryRun;
import forge.web.protocol.RogueProtocol.HistoryStep;
import forge.web.protocol.RogueProtocol.MapLink;
import forge.web.protocol.RogueProtocol.MapNode;
import forge.web.protocol.RogueProtocol.RogueAether;
import forge.web.protocol.RogueProtocol.RogueCodexPage;
import forge.web.protocol.RogueProtocol.RogueHome;
import forge.web.protocol.RogueProtocol.RogueMap;
import forge.web.protocol.RogueProtocol.RogueTraits;
import forge.web.protocol.RogueProtocol.Trait;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;

@Test(singleThreaded = true)
public class RoguePagesTest {
    private RogueFlow flow;
    private RogueRun run;

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    /** The fixture's run at the sanctum of row 1, reached from the sanctum of row 0. */
    @BeforeMethod
    public void atTheSanctum() {
        RogueFixture.clean();
        run = RogueFixture.run(NodeSanctum.class);
        flow = new RogueFlow(new ScriptedDecisions());
        flow.setRun(run);
    }

    @AfterMethod(alwaysRun = true)
    public void plainRandom() {
        MyRandom.setRandom(new Random());
    }

    private RogueMap map() {
        return RoguePages.build(flow, RogueMetaProgress.getInstance(), 1, null).pages().stream()
                .filter(RogueMap.class::isInstance).map(RogueMap.class::cast).findFirst().orElseThrow();
    }

    private RogueTraits traits() {
        return RoguePages.build(flow, RogueMetaProgress.getInstance(), 1, null).pages().stream()
                .filter(RogueTraits.class::isInstance).map(RogueTraits.class::cast).findFirst().orElseThrow();
    }

    /** A wound, a chest trait, an NPC's trait, Descension 1 and Endless 1, which getAllEffects lists in another order. */
    private void withTraits() {
        run.addWound(WoundEffect.ARM);
        run.addChestEffect(ChestEffect.BANNER_OF_CHAMPIONS);
        run.addNPCEffect(NPCEffect.HENZIE_PRECIOUS);
        run.setDescensionLevel(1);
        run.setEndlessLevel(1);
    }

    private static MapNode node(final RogueMap map, final int index) {
        return map.rows().stream().flatMap(r -> r.stream()).filter(n -> n.index() == index).findFirst().orElseThrow();
    }

    // Fails if a node is drawn in a state the run does not give it
    @Test
    public void statesFollowTheRun() {
        RogueMap map = map();
        Assert.assertEquals(node(map, 1).state(), "walked");
        Assert.assertEquals(node(map, 0).state(), "past");
        Assert.assertEquals(node(map, 6).state(), "selected");
        Assert.assertEquals(node(map, 5).state(), "dead", "row 1's plane cannot be reached from row 0's sanctum");
        Assert.assertEquals(node(map, 10).state(), "far");
        run.getPath().getNode(1).setCompleted(false);
        map = map();
        Assert.assertEquals(node(map, 5).state(), "open", "with nothing walked in row 0 every node of row 1 can be chosen");
        Assert.assertEquals(map.selected(), 6);
    }

    // Fails if the browser can learn a face-down plane's name, picture or text
    @Test
    public void aFaceDownPlaneSendsNoName() {
        final NodePlanebound hidden = (NodePlanebound) run.getPath().getNode(10);
        hidden.setWrathfulCount(2);
        final MapNode node = node(map(), 10);
        Assert.assertTrue(node.faceDown());
        Assert.assertNull(node.name());
        Assert.assertNull(node.plane());
        Assert.assertNull(node.art());
        Assert.assertNull(node.planeImage());
        Assert.assertNull(node.text());
        Assert.assertFalse(node.enter().contains(hidden.getRoguePlanebound().planeName()), node.enter());
        Assert.assertEquals(node.wrathful(), 2);
        Assert.assertEquals(node.life(), hidden.getPlaneboundLife(run.getPath().countPlaneboundRowsUpTo(2)));
        Assert.assertTrue(node.elite(), "the fixture's third plane is an elite");
        final MapNode shown = node(map(), 0);
        Assert.assertEquals(shown.name(), ((NodePlanebound) run.getPath().getNode(0)).getRoguePlanebound().planeboundName());
        Assert.assertNotNull(shown.planeImage());
    }

    // Fails if a run that jumped within a row loses one of the two walked nodes or the way out of either
    @Test
    public void aJumpedRunDrawsBothWalkedNodes() {
        run.getPath().getNode(2).setCompleted(true);
        final RogueMap map = map();
        Assert.assertEquals(node(map, 1).state(), "walked");
        Assert.assertEquals(node(map, 2).state(), "walked");
        Assert.assertTrue(map.links().contains(new MapLink(1, 6)));
        Assert.assertTrue(map.links().contains(new MapLink(2, 7)));
    }

    // Fails if a wound is listed among the other traits, or Descension and Endless before them
    @Test
    public void woundsComeAfterOtherTraits() {
        withTraits();
        Assert.assertEquals(traits().traits().stream().map(Trait::source).toList(), List.of("CHEST", "NPC", "WOUND", "DESCENSION", "ENDLESS"));
    }

    // Fails if a trait is left out or listed twice
    @Test
    public void everyActiveEffectIsListedOnce() {
        withTraits();
        run.setDescensionLevel(2);
        Assert.assertEquals(traits().traits().size(), RogueEffectComposite.getAllEffects(run).size());
        Assert.assertEquals(traits().traits().stream().map(Trait::name).distinct().count(), 6);
    }

    // Fails if Sparks or an Aether Upgrade are shown before Descension Mode is unlocked
    @Test
    public void sparksAreHiddenBeforeDescension() {
        final RogueMetaProgress progress = RogueMetaProgress.getInstance();
        progress.updateStat(RogueStats.RUNS_COMPLETED, 1, run);
        progress.addSparks(3);
        Assert.assertFalse(progress.isDescensionModeUnlocked());
        final List<Record> pages = RoguePages.build(flow, progress, 1, null).pages();
        final RogueHome home = pages.stream().filter(RogueHome.class::isInstance).map(RogueHome.class::cast).findFirst().orElseThrow();
        Assert.assertTrue(home.bar().balances().stream().noneMatch(b -> "ICO_QUEST_ELIXIR".equals(b.icon())), "the bar shows Sparks");
        final RogueAether aether = pages.stream().filter(RogueAether.class::isInstance).map(RogueAether.class::cast).findFirst().orElseThrow();
        Assert.assertFalse(aether.descension());
        Assert.assertNull(aether.upgrade());
        Assert.assertTrue(aether.locked().isEmpty(), "works an upgrade unlocks are shown");
    }

    // Fails if the browser can learn a card or a planebound the player has not seen from the Codex
    @Test
    public void anUnknownCardSendsNoName() {
        final RogueMetaProgress progress = RogueMetaProgress.getInstance();
        progress.updateStat(RogueStats.RUNS_COMPLETED, 1, run);
        final List<CodexItem> pool = RoguePages.codex(progress, "commanders", null).entry().groups().get(0).items();
        Assert.assertFalse(pool.isEmpty());
        for (final CodexItem card : pool) {
            Assert.assertEquals(card.state(), "unknown", "a new profile has seen no reward card");
            Assert.assertNull(card.name());
            Assert.assertNull(card.image());
        }
        final RogueCodexPage planebounds = RoguePages.codex(progress, "planebounds", null);
        Assert.assertNull(planebounds.entry(), "no planebound has been met");
        for (final CodexRow row : planebounds.rows()) {
            Assert.assertNull(row.id());
            Assert.assertNull(row.art());
            Assert.assertEquals(row.line(), "");
        }
    }

    // Fails if the Codex can be read before a run is completed
    @Test
    public void theCodexIsLockedBeforeTheFirstRun() {
        final RogueMetaProgress progress = RogueMetaProgress.getInstance();
        for (final String tab : List.of("stats", "commanders", "planebounds", "traits", "journal")) {
            Assert.assertNull(RoguePages.codex(progress, tab, null), tab);
        }
        progress.updateStat(RogueStats.RUNS_COMPLETED, 1, run);
        Assert.assertNotNull(RoguePages.codex(progress, "stats", null));
    }

    // Fails if a trait is named by its card, whose name says where the trait came from
    @Test
    public void namesHaveNoCardPrefix() {
        withTraits();
        Assert.assertEquals(traits().traits().get(0).name(), "Banner Of Champions");
    }

    /** The one run in the history, after the first run completed opens History. */
    private HistoryRun onlyRun(final RogueMetaProgress progress, final RogueRunHistoryEntry entry) {
        progress.addRunHistoryEntry(entry);
        progress.updateStat(RogueStats.RUNS_COMPLETED, 1, run);
        final List<HistoryRun> runs = RoguePages.history(progress).runs();
        Assert.assertEquals(runs.size(), 1);
        return runs.get(0);
    }

    // Fails if an Endless run's defeat shows as a defeat, or loses who defeated it
    @Test
    public void anEndlessDefeatShowsAsVictoryWithWhoDefeatedIt() {
        run.setEndlessLevel(2);
        final HistoryRun shown = onlyRun(RogueMetaProgress.getInstance(), RogueRunHistoryEntry.fromRun(run, "DEFEAT", "Trostani, Selesnya's Voice"));
        Assert.assertEquals(shown.outcome(), "VICTORY");
        Assert.assertEquals(shown.line(), "Defeated by: Trostani, Selesnya's Voice · Endless Level 2");
    }

    // Fails if a path label that names no plane is dropped or breaks the page, or a plane's label loses its picture
    @Test
    public void anOldPathStringWithNoPlaneStillShows() throws ReflectiveOperationException {
        final RogueRunHistoryEntry entry = RogueRunHistoryEntry.fromRun(run, "ABANDONED", null);
        final Field path = RogueRunHistoryEntry.class.getDeclaredField("path");
        path.setAccessible(true);
        path.set(entry, List.of("Trostani, Selesnya's Voice", "Sanctum", "Naya (Rafiq of the Many)"));
        final List<HistoryStep> steps = onlyRun(RogueMetaProgress.getInstance(), entry).steps();
        Assert.assertEquals(steps.stream().map(HistoryStep::kind).toList(), List.of("PLANEBOUND", "SANCTUM", "PLANEBOUND"));
        Assert.assertNull(steps.get(0).image());
        Assert.assertEquals(steps.get(0).name(), "Trostani, Selesnya's Voice");
        Assert.assertNotNull(steps.get(2).image(), "Naya has no picture");
        Assert.assertEquals(steps.get(2).name(), "Rafiq of the Many");
    }
}
