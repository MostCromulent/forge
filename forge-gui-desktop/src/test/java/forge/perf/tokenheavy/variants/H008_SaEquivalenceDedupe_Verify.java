package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

import java.util.concurrent.atomic.AtomicLong;

public final class H008_SaEquivalenceDedupe_Verify extends OptimizationContext {
    private static final AtomicLong DIVERGENCES = new AtomicLong();

    @Override
    public boolean verifySaEquivalenceDedupe() { return true; }

    @Override
    public void reportSaEquivalenceDivergence(String fullKey, String dedupedKey) {
        long n = DIVERGENCES.incrementAndGet();
        if (n <= 10) {
            System.err.printf("[H008 verify] chooseSa divergence #%d:%n  full=%s%n  dedup=%s%n",
                n, fullKey, dedupedKey);
        } else if (n == 11) {
            System.err.println("[H008 verify] further divergences suppressed");
        }
    }

    public static long divergenceCount() { return DIVERGENCES.get(); }
}
