package forge.game.perf;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight call-count + total-nanos counters for engine-layer hot paths.
 * Enabled only when the perf testbed harness sets {@link #enabled} true; zero
 * cost when disabled (a single volatile read).
 */
public final class PerfCounters {
    public static volatile boolean enabled = false;

    public static final class Counter {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong nanos = new AtomicLong();
        public long calls() { return calls.get(); }
        public long nanos() { return nanos.get(); }
        public void reset() { calls.set(0); nanos.set(0); }
    }

    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();

    public static Counter counter(String name) {
        return COUNTERS.computeIfAbsent(name, n -> new Counter());
    }

    public static void resetAll() {
        COUNTERS.values().forEach(Counter::reset);
    }

    public static Map<String, Counter> snapshot() {
        return Map.copyOf(COUNTERS);
    }

    public static long time(String name, Runnable body) {
        if (!enabled) { body.run(); return 0L; }
        Counter c = counter(name);
        long t0 = System.nanoTime();
        try { body.run(); return System.nanoTime() - t0; }
        finally {
            long dt = System.nanoTime() - t0;
            c.calls.incrementAndGet();
            c.nanos.addAndGet(dt);
        }
    }

    public static <T> T time(String name, java.util.function.Supplier<T> body) {
        if (!enabled) return body.get();
        Counter c = counter(name);
        long t0 = System.nanoTime();
        try { return body.get(); }
        finally {
            long dt = System.nanoTime() - t0;
            c.calls.incrementAndGet();
            c.nanos.addAndGet(dt);
        }
    }

    private PerfCounters() {}
}
