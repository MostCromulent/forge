package forge.ai;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Heuristic: does the player have any playable action this priority window?
// Bounded by timeoutMs; returns true on expiry (false-positive — player is prompted).
public final class AvailableActions {

    public enum Variant {
        BASELINE,
        FLASHBACK,
        SORTED,
        FLASHBACK_SORTED
    }

    public static final class Stats {
        public long handNanos;
        public long battlefieldNanos;
        public long externalZonesNanos;
        public long totalNanos;
        public long canAffordCalls;
        public long validTargetsCalls;
        public long timeouts;
    }

    private AvailableActions() {}

    public static boolean compute(Player player, long timeoutMs) {
        return compute(player, timeoutMs, null, Variant.BASELINE);
    }

    public static boolean compute(Player player, long timeoutMs, Stats stats, Variant variant) {
        long callStart = stats != null ? System.nanoTime() : 0L;
        long deadlineNanos = callStart + timeoutMs * 1_000_000L;
        if (stats == null) deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;

        boolean sorted = variant == Variant.SORTED || variant == Variant.FLASHBACK_SORTED;
        boolean flashback = variant == Variant.FLASHBACK || variant == Variant.FLASHBACK_SORTED;

        try {
            long phaseStart = stats != null ? System.nanoTime() : 0L;
            if (checkHand(player, deadlineNanos, timeoutMs, stats, sorted)) return true;
            if (stats != null) stats.handNanos += System.nanoTime() - phaseStart;

            phaseStart = stats != null ? System.nanoTime() : 0L;
            if (checkBattlefield(player, deadlineNanos, timeoutMs, stats, sorted)) return true;
            if (stats != null) stats.battlefieldNanos += System.nanoTime() - phaseStart;

            phaseStart = stats != null ? System.nanoTime() : 0L;
            if (flashback) {
                if (checkFlashbackZone(player, deadlineNanos, timeoutMs, stats, sorted)) return true;
            } else {
                if (checkExternalZones(player, deadlineNanos, timeoutMs, stats, sorted)) return true;
            }
            if (stats != null) stats.externalZonesNanos += System.nanoTime() - phaseStart;

            return false;
        } finally {
            if (stats != null) stats.totalNanos += System.nanoTime() - callStart;
        }
    }

    private static boolean checkHand(Player player, long deadlineNanos, long timeoutMs,
            Stats stats, boolean sorted) {
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            for (SpellAbility sa : abilitiesOf(card, player, sorted)) {
                if (checkTimeout(deadlineNanos, timeoutMs, stats)) return true;
                if (sa.isSpell()) {
                    if (canAfford(sa, player, stats) && hasValidTargets(sa, stats)) {
                        return true;
                    }
                } else if (sa.isLandAbility()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean checkBattlefield(Player player, long deadlineNanos, long timeoutMs,
            Stats stats, boolean sorted) {
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            for (SpellAbility sa : abilitiesOf(card, player, sorted)) {
                if (checkTimeout(deadlineNanos, timeoutMs, stats)) return true;
                if (!sa.isManaAbility() && canAfford(sa, player, stats) && hasValidTargets(sa, stats)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean checkExternalZones(Player player, long deadlineNanos, long timeoutMs,
            Stats stats, boolean sorted) {
        for (ZoneType zone : new ZoneType[]{ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command}) {
            for (Card card : player.getCardsIn(zone)) {
                for (SpellAbility sa : abilitiesOf(card, player, sorted)) {
                    if (checkTimeout(deadlineNanos, timeoutMs, stats)) return true;
                    if (!sa.isManaAbility() && canAfford(sa, player, stats) && hasValidTargets(sa, stats)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean checkFlashbackZone(Player player, long deadlineNanos, long timeoutMs,
            Stats stats, boolean sorted) {
        for (Card card : player.getCardsIn(ZoneType.Flashback)) {
            for (SpellAbility sa : abilitiesOf(card, player, sorted)) {
                if (checkTimeout(deadlineNanos, timeoutMs, stats)) return true;
                if (!sa.isManaAbility() && canAfford(sa, player, stats) && hasValidTargets(sa, stats)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Iterable<SpellAbility> abilitiesOf(Card card, Player player, boolean sorted) {
        List<SpellAbility> abilities = card.getAllPossibleAbilities(player, true);
        if (!sorted || abilities.size() < 2) return abilities;
        List<SpellAbility> copy = new ArrayList<>(abilities);
        copy.sort(Comparator.comparingInt(AvailableActions::cmcOf));
        return copy;
    }

    private static int cmcOf(SpellAbility sa) {
        if (sa.isLandAbility()) return 0;
        if (sa.getPayCosts() == null || !sa.getPayCosts().hasManaCost()) return 0;
        return sa.getPayCosts().getTotalMana().getCMC();
    }

    private static boolean canAfford(SpellAbility sa, Player player, Stats stats) {
        if (stats != null) stats.canAffordCalls++;
        if (sa.getPayCosts() == null || !sa.getPayCosts().hasManaCost()) {
            return true;
        }
        return ComputerUtilMana.canPayManaCost(sa, player, 0, false);
    }

    private static boolean hasValidTargets(SpellAbility sa, Stats stats) {
        if (stats != null) stats.validTargetsCalls++;
        if (!sa.usesTargeting()) {
            return true;
        }
        return sa.getTargetRestrictions().hasCandidates(sa);
    }

    private static boolean checkTimeout(long deadlineNanos, long timeoutMs, Stats stats) {
        if (System.nanoTime() < deadlineNanos) {
            return false;
        }
        if (stats != null) {
            stats.timeouts++;
        } else {
            Logger.warn("AvailableActions: heuristic timed out after {}ms; returning true.", timeoutMs);
        }
        return true;
    }
}
