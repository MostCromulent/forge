package forge.web;

import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.flow.RogueFlow;
import forge.gamemodes.rogue.path.NodePlanebound;
import forge.gamemodes.rogue.path.NodeSanctum;
import forge.util.MyRandom;
import forge.web.campaign.rogue.RoguePages;
import forge.web.protocol.RogueProtocol.MapLink;
import forge.web.protocol.RogueProtocol.MapNode;
import forge.web.protocol.RogueProtocol.RogueMap;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

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
        return RoguePages.build(flow, RogueMetaProgress.getInstance(), 1).pages().stream()
                .filter(RogueMap.class::isInstance).map(RogueMap.class::cast).findFirst().orElseThrow();
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
}
