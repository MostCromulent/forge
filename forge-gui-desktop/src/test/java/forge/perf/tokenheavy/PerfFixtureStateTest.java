package forge.perf.tokenheavy;

import forge.net.TestUtils;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test(groups = "stress")
public class PerfFixtureStateTest {
    @BeforeClass
    public void setup() { TestUtils.ensureFModelInitialized(); }

    @Test
    public void parsesEmptyBoardFixture() throws Exception {
        PerfFixtureState s = PerfFixtureState.fromResource(
            "perf/fixtures/tokenheavy/empty-board-commander.txt");
        assertNotNull(s);
        // parse() populates internal state; we don't expose it, but if the
        // parse throws or returns null something's wrong.
    }
}
