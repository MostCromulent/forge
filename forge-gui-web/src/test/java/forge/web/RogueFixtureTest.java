package forge.web;

import forge.gamemodes.rogue.RogueEvent;
import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueRun.CarryCard;
import forge.gamemodes.rogue.RogueRun.CarryCardType;
import forge.gamemodes.rogue.effect.ChestEffect;
import forge.gamemodes.rogue.effect.WoundEffect;
import forge.gamemodes.rogue.path.NodeChest;
import forge.gamemodes.rogue.path.NodeEvent;
import forge.gamemodes.rogue.path.NodePlanebound;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;

@Test(singleThreaded = true)
public class RogueFixtureTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @BeforeMethod
    public void clean() {
        RogueFixture.clean();
    }

    @Test
    public void theFixtureRunLoads() {
        RogueFixture.install("/rogue/run-master.xml", "Aragorn_1.dat");
        final RogueRun run = RogueIO.loadMostRecentContinuableRun().orElseThrow(AssertionError::new);
        Assert.assertEquals(run.getEndlessLevel(), 1);
        Assert.assertEquals(run.getDescensionLevel(), 1);
        Assert.assertEquals(run.getRemovalCredits(), 3);
        Assert.assertEquals(run.getCarryCards().stream().map(CarryCard::type).toList(),
                List.of(CarryCardType.ITEM, CarryCardType.FELLOW, CarryCardType.SCROLL));
        Assert.assertEquals(run.getCarryCards().stream().map(CarryCard::cardName).toList(),
                List.of("Sol Ring", "Llanowar Elves", "Lightning Bolt"));
        Assert.assertEquals(run.getActiveWoundEffects(), List.of(WoundEffect.LEFT_LEG));
        Assert.assertEquals(run.getCurrentGold(), 8);
        Assert.assertEquals(run.getCurrentCommanderName(), "Aragorn, the Uniter");
        Assert.assertEquals(run.getCurrentDeck().getMain().countAll(), 32);
        Assert.assertEquals(run.getSelectedRogueDeck().getRewardPoolCards().size(), 157);

        Assert.assertEquals(run.getCurrentNodeIndex(), 11);
        Assert.assertEquals(((NodeChest) run.getCurrentNode()).getChestEffects(),
                List.of(ChestEffect.RELIC_OF_INSIGHT, ChestEffect.BANNER_OF_WAR));
        Assert.assertEquals(((NodeEvent) run.getPath().getNodes().get(10)).getEvent(), RogueEvent.MERCHANT_CARAVAN);
        final NodePlanebound first = (NodePlanebound) run.getPath().getNodes().get(0);
        Assert.assertTrue(first.isCompleted());
        Assert.assertTrue(first.isRevealed());
        Assert.assertEquals(first.getWrathfulCount(), 1);
        Assert.assertEquals(first.getStartingLifeModification(), 5);
        Assert.assertEquals(first.getRoguePlanebound().planeName(), "The Great Forest");
    }
}
