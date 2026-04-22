package forge.game.perf;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class PerfCountersTest {
    @Test
    public void disabledByDefault_doesNotRecord() {
        PerfCounters.resetAll();
        PerfCounters.enabled = false;
        PerfCounters.time("t", () -> {});
        assertEquals(PerfCounters.counter("t").calls(), 0L);
    }

    @Test
    public void enabled_recordsCallsAndNanos() {
        PerfCounters.resetAll();
        PerfCounters.enabled = true;
        try {
            PerfCounters.time("t", () -> { try { Thread.sleep(1); } catch (InterruptedException ignored) {} });
            assertEquals(PerfCounters.counter("t").calls(), 1L);
            assertTrue(PerfCounters.counter("t").nanos() > 0L);
        } finally {
            PerfCounters.enabled = false;
        }
    }
}
