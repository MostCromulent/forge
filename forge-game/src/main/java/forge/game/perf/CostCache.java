package forge.game.perf;

import java.util.HashMap;
import java.util.Map;

/**
 * Cache for ComputerUtilCost.canPayCost results within a single
 * chooseSpellAbilityToPlay call. Keyed by a caller-supplied string
 * capturing (sa cost identity, payer mana-pool fingerprint, effect flag).
 *
 * Scope enforced by enterDecision/exitDecision with a depth counter so
 * nested calls share the outermost scope.
 */
public final class CostCache {
    private final Map<String, Boolean> entries = new HashMap<>();
    private int depth;
    private long hits;
    private long misses;

    // Scope binding: cache is only valid for the player whose
    // chooseSpellAbilityToPlay entered the decision. Other payers bypass.
    // Mana fingerprint captured once per decision, reused across all
    // canPayCost calls within the scope so we don't rebuild it per call.
    private forge.game.player.Player scopePlayer;
    private String manaFingerprint = "";

    public Boolean get(String key) {
        Boolean v = entries.get(key);
        if (v == null) misses++; else hits++;
        return v;
    }

    public void put(String key, boolean result) {
        entries.put(key, result);
    }

    public void enterDecision(forge.game.player.Player player) {
        if (depth == 0) {
            entries.clear();
            scopePlayer = player;
            manaFingerprint = computeManaFingerprint(player);
        }
        depth++;
    }

    public void exitDecision() {
        depth--;
        if (depth < 0) depth = 0;
        if (depth == 0) {
            scopePlayer = null;
            manaFingerprint = "";
        }
    }

    public boolean isScoped(forge.game.player.Player payer) {
        return scopePlayer != null && scopePlayer == payer;
    }

    public String getManaFingerprint() { return manaFingerprint; }

    public long hits() { return hits; }
    public long misses() { return misses; }
    public int size() { return entries.size(); }

    private static String computeManaFingerprint(forge.game.player.Player p) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(p.getLife()).append('|');
        sb.append(p.getManaPool().toString()).append('|');
        for (forge.game.card.Card c : p.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
            if (c.getManaAbilities().isEmpty()) continue;
            sb.append(c.getId()).append(':').append(c.isTapped() ? '0' : '1').append(';');
        }
        return sb.toString();
    }
}
