package forge.perf.tokenheavy;

import org.testng.annotations.Test;
import java.util.List;
import static org.testng.Assert.*;

public class DecisionRecordTest {
    @Test
    public void equalRecordsAreEqual() {
        DecisionRecord a = new DecisionRecord("chooseSpell", 3, "MAIN1", "p0", List.of("Lightning Bolt", "p1"));
        DecisionRecord b = new DecisionRecord("chooseSpell", 3, "MAIN1", "p0", List.of("Lightning Bolt", "p1"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void differingTargetPartsAreUnequal() {
        DecisionRecord a = new DecisionRecord("chooseSpell", 3, "MAIN1", "p0", List.of("Lightning Bolt", "p1"));
        DecisionRecord b = new DecisionRecord("chooseSpell", 3, "MAIN1", "p0", List.of("Lightning Bolt", "p2"));
        assertNotEquals(a, b);
    }
}
