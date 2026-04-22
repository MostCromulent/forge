package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * No-op variant. Overrides nothing. Running the harness with -Dhypothesis=H000_Noop
 * must produce: verdict = INCONCLUSIVE (noise band, since query mode runs the same
 * baseline path twice), 0 divergences, 0 counter deltas.
 *
 * This is the end-to-end plumbing gate — if this run fails or produces
 * divergences, the whole harness is broken.
 */
public final class H000_Noop extends OptimizationContext {
}
