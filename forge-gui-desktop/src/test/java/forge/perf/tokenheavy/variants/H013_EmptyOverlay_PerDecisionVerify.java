package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-decision empirical verify for H013. Runs the full slow path on
 * every getChangedCardTraitsList call and checks that, when the fast-path
 * condition (both overlay tables empty) holds, the slow path produces
 * exactly the singleton that the fast path would have returned.
 *
 * Only the H013 verify flag is enabled — all other optimisations off —
 * so the slow-path reference is pure baseline.
 */
public final class H013_EmptyOverlay_PerDecisionVerify extends OptimizationContext {
    private static final AtomicLong DIVERGENCES = new AtomicLong();

    @Override public boolean verifyEmptyOverlayFastPath() { return true; }

    @Override
    public void reportEmptyOverlayDivergence(forge.game.card.Card card, String reason) {
        long n = DIVERGENCES.incrementAndGet();
        if (n <= 10) {
            System.err.printf("[H013 verify] divergence #%d: card=%s reason=%s%n",
                n, card == null ? "null" : card.getName(), reason);
        } else if (n == 11) {
            System.err.println("[H013 verify] further divergences suppressed");
        }
    }

    public static long divergenceCount() { return DIVERGENCES.get(); }
}
