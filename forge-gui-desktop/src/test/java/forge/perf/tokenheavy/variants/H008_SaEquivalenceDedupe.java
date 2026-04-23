package forge.perf.tokenheavy.variants;

import forge.game.perf.OptimizationContext;

/**
 * H008 production variant: dedupe the spell-ability list inside
 * AiController.chooseSpellAbilityToPlayFromList by equivalence class
 * (host identity + ability identity + cost) before sort + iteration.
 *
 * For N identical tokens with K abilities each, score + canPlay-check
 * 1×K SAs instead of N×K. An arbitrary representative stands in for the
 * whole class — engine plays that specific host, and any group member
 * was interchangeable by construction.
 */
public final class H008_SaEquivalenceDedupe extends OptimizationContext {
    @Override
    public boolean useSaEquivalenceDedupe() { return true; }
}
