package forge.perf.tokenheavy;

import java.util.List;
import java.util.Objects;

/**
 * Comparable key for an AI decision, used by the oracle to detect divergence
 * between baseline and variant. Stringly-typed on purpose — SpellAbility
 * objects differ between game copies and between baseline/variant calls, so
 * identity comparison is unusable; string-key comparison is.
 */
public final class DecisionRecord {
    public final String hook;              // "chooseSpellAbilityToPlay" / "declareAttackers" / ...
    public final int turn;
    public final String phase;
    public final String activePlayer;
    public final List<String> keyParts;    // ordered, hook-specific

    public DecisionRecord(String hook, int turn, String phase, String activePlayer, List<String> keyParts) {
        this.hook = hook;
        this.turn = turn;
        this.phase = phase;
        this.activePlayer = activePlayer;
        this.keyParts = List.copyOf(keyParts);
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof DecisionRecord)) return false;
        DecisionRecord d = (DecisionRecord) o;
        return turn == d.turn && hook.equals(d.hook) && phase.equals(d.phase)
            && activePlayer.equals(d.activePlayer) && keyParts.equals(d.keyParts);
    }
    @Override public int hashCode() {
        return Objects.hash(hook, turn, phase, activePlayer, keyParts);
    }
    @Override public String toString() {
        return hook + "@T" + turn + "/" + phase + "/" + activePlayer + keyParts;
    }
}
