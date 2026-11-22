package forge.web;

import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueRun;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

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
        Assert.assertEquals(run.getCarryCards().size(), 3);
    }
}
