package forge.perf.tokenheavy;

import org.testng.annotations.Test;
import java.util.Map;
import static forge.perf.tokenheavy.VerdictEvaluator.Verdict.*;
import static org.testng.Assert.*;

public class VerdictEvaluatorTest {
    @Test public void divergenceBeatsPerf() {
        assertEquals(VerdictEvaluator.evaluate(1000, 500, Map.of(), Map.of(), 1),
                     CORRECTNESS_DIVERGENCE);
    }
    @Test public void fasterMeansPass() {
        assertEquals(VerdictEvaluator.evaluate(1000, 900, Map.of(), Map.of(), 0), PASS);
    }
    @Test public void slowerMeansRegression() {
        assertEquals(VerdictEvaluator.evaluate(1000, 1030, Map.of(), Map.of(), 0), PERF_REGRESSION);
    }
    @Test public void counterImprovementWinsWhenWallIsFlat() {
        assertEquals(VerdictEvaluator.evaluate(
            1000, 1000,
            Map.of("canBlock", 1000L), Map.of("canBlock", 100L),
            0), PASS);
    }
    @Test public void noisyFlatIsInconclusive() {
        assertEquals(VerdictEvaluator.evaluate(1000, 1005, Map.of(), Map.of(), 0), INCONCLUSIVE);
    }
}
