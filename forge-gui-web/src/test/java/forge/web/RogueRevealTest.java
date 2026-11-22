package forge.web;

import forge.gamemodes.rogue.RoguePlanebound;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.effect.NPCEffect;
import forge.gamemodes.rogue.effect.WoundEffect;
import forge.gamemodes.rogue.path.NodePlanebound;
import forge.gamemodes.rogue.path.RoguePath;
import forge.gamemodes.rogue.path.RoguePathGenerator;
import forge.gamemodes.rogue.path.RoguePathNode;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

@Test(singleThreaded = true)
public class RogueRevealTest {
    private RogueRun run;
    private RoguePath path;

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    /** Five rows of three planes. Rows of one size link straight up, so a node's only exit is three indexes on. */
    @BeforeMethod
    public void fixedPath() {
        RogueFixture.clean();
        run = RogueFixture.run(NodePlanebound.class);
        final RoguePlanebound planebound = ((NodePlanebound) run.getPath().getNode(0)).getRoguePlanebound();
        final List<RoguePathNode> nodes = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            final NodePlanebound plane = new NodePlanebound(planebound);
            plane.setRowIndex(i / 3);
            plane.setColumnIndex(i % 3);
            nodes.add(plane);
        }
        path = new RoguePath(nodes);
        run.setPath(path);
        run.setCurrentNodeIndex(0);
    }

    @AfterMethod(alwaysRun = true)
    public void plainRandom() {
        MyRandom.setRandom(new Random());
    }

    private void complete(final int node) {
        path.getNode(node).setCompleted(true);
        run.setCurrentNodeIndex(node + 3);
    }

    private List<Integer> reveal() {
        final List<Integer> revealed = new ArrayList<>();
        path.revealVisible(run).forEach(node -> revealed.add(path.getNodes().indexOf(node)));
        return revealed;
    }

    private List<Integer> faceUp() {
        final List<Integer> faceUp = new ArrayList<>();
        for (int i = 0; i < path.getNodeCount(); i++) {
            if (!path.isFaceDown(run, path.getNode(i))) {
                faceUp.add(i);
            }
        }
        return faceUp;
    }

    private void eachGeneratedPath(final Consumer<RoguePath> check) {
        MyRandom.setRandom(new Random(1));
        for (int i = 0; i < 200; i++) {
            RoguePathGenerator.generateRandomPath(run);
            check.accept(run.getPath());
        }
    }

    @Test
    public void noNodeLeadsToMoreThanTwo() {
        eachGeneratedPath(generated -> {
            for (final RoguePathNode node : generated.getNodes()) {
                final int exits = generated.getReachableNodes(node).size();
                Assert.assertTrue(exits <= 2, node + " leads to " + exits);
                Assert.assertTrue(exits > 0 || node.getRowIndex() == generated.getMaxRow(), node + " leads nowhere");
            }
        });
    }

    @Test
    public void linksNeverCross() {
        eachGeneratedPath(generated -> {
            for (int row = 0; row < generated.getMaxRow(); row++) {
                int furthest = 0;
                for (final RoguePathNode node : generated.getNodesInRow(row)) {
                    final int before = furthest;
                    for (final RoguePathNode next : generated.getReachableNodes(node)) {
                        Assert.assertTrue(next.getColumnIndex() >= before, "row " + row + " " + node);
                        furthest = Math.max(furthest, next.getColumnIndex());
                    }
                }
            }
        });
    }

    @Test
    public void theFirstRowIsRevealedAtOnce() {
        Assert.assertEquals(faceUp(), List.of(0, 1, 2));
        Assert.assertEquals(reveal(), List.of(0, 1, 2));
        Assert.assertTrue(((NodePlanebound) path.getNode(2)).isRevealed());
        Assert.assertFalse(((NodePlanebound) path.getNode(3)).isRevealed());
    }

    @Test
    public void anUnreachableNodeOfTheRowStaysFaceDown() {
        reveal();
        complete(1);
        Assert.assertEquals(reveal(), List.of(4));
        Assert.assertEquals(faceUp(), List.of(0, 1, 2, 4));
    }

    @Test
    public void theEyeHidesPlanesButNotTheRevealed() {
        run.addWound(WoundEffect.EYE);
        Assert.assertEquals(reveal(), List.of());
        run.clearWounds();
        Assert.assertEquals(reveal(), List.of(0, 1, 2));
        run.addWound(WoundEffect.EYE);
        complete(1);
        Assert.assertEquals(reveal(), List.of());
        Assert.assertEquals(faceUp(), List.of(0, 1, 2));
    }

    @Test
    public void farsightRevealsReachablePlanesAhead() {
        complete(1);
        run.addNPCEffect(NPCEffect.NARSET_FARSIGHT);
        // Farsight shows three rows past the current one
        Assert.assertEquals(reveal(), List.of(0, 1, 2, 4, 7, 10, 13));
    }

    @Test
    public void aPastNodeThatWasNotReachableStaysFaceDown() {
        complete(1);
        complete(4);
        Assert.assertEquals(reveal(), List.of(0, 1, 2, 4, 7));
        Assert.assertTrue(path.isFaceDown(run, path.getNode(3)));
        Assert.assertTrue(path.isFaceDown(run, path.getNode(5)));
    }

    @Test
    public void aSecondCallRevealsNothing() {
        Assert.assertFalse(reveal().isEmpty());
        Assert.assertEquals(reveal(), List.of());
    }
}
