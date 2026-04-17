package forge.perf;

import forge.LobbyPlayer;
import forge.ai.AvailableActions;
import forge.ai.AvailableActions.Stats;
import forge.ai.AvailableActions.Variant;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.EnumMap;
import java.util.List;

/**
 * A normal AI player controller that additionally runs AvailableActions.compute
 * each priority pass — once per {@link Variant} — to produce comparative perf
 * measurements.
 */
public class InstrumentedPlayerController extends PlayerControllerAi {

    private static final long TIMEOUT_MS = 60_000L;

    private final boolean evaluate;

    private final EnumMap<Variant, VariantAggregate> perVariant = new EnumMap<>(Variant.class);

    // Board state tracking (variant-independent)
    private long evalCount;
    private long totalHandSize;
    private long totalBattlefieldSize;
    private long totalExternalZonesSize;
    private long totalFlashbackSize;

    public InstrumentedPlayerController(Game game, Player player, LobbyPlayer lobbyPlayer, boolean evaluate) {
        super(game, player, lobbyPlayer);
        this.evaluate = evaluate;
        if (evaluate) {
            for (Variant v : Variant.values()) {
                perVariant.put(v, new VariantAggregate());
            }
        }
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (evaluate) {
            instrumentedEvaluation();
        }
        return super.chooseSpellAbilityToPlay();
    }

    private void instrumentedEvaluation() {
        Player p = getPlayer();

        // Board-state tracking (same across variants — record once)
        totalHandSize += p.getCardsIn(ZoneType.Hand).size();
        totalBattlefieldSize += p.getCardsIn(ZoneType.Battlefield).size();
        int externalSize = 0;
        for (ZoneType zone : new ZoneType[]{ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command}) {
            externalSize += p.getCardsIn(zone).size();
        }
        totalExternalZonesSize += externalSize;
        totalFlashbackSize += p.getCardsIn(ZoneType.Flashback).size();
        evalCount++;

        for (Variant v : Variant.values()) {
            Stats stats = new Stats();
            boolean hasAction = AvailableActions.compute(p, TIMEOUT_MS, stats, v);
            perVariant.get(v).accept(stats, hasAction);
        }
    }

    public long getEvalCount() { return evalCount; }

    public double getAvgHandSize() { return evalCount > 0 ? (double) totalHandSize / evalCount : 0; }
    public double getAvgBattlefieldSize() { return evalCount > 0 ? (double) totalBattlefieldSize / evalCount : 0; }
    public double getAvgExternalZonesSize() { return evalCount > 0 ? (double) totalExternalZonesSize / evalCount : 0; }
    public double getAvgFlashbackSize() { return evalCount > 0 ? (double) totalFlashbackSize / evalCount : 0; }

    public VariantAggregate getAggregate(Variant v) { return perVariant.get(v); }

    public static final class VariantAggregate {
        public long calls;
        public long totalNanos;
        public long maxCallNanos;
        public long handNanos;
        public long battlefieldNanos;
        public long externalZonesNanos;
        public long maxHandNanos;
        public long maxBattlefieldNanos;
        public long maxExternalZonesNanos;
        public long canAffordCalls;
        public long validTargetsCalls;
        public long timeouts;
        public long foundAction;
        public long noActionFound;

        void accept(Stats s, boolean hasAction) {
            calls++;
            totalNanos += s.totalNanos;
            handNanos += s.handNanos;
            battlefieldNanos += s.battlefieldNanos;
            externalZonesNanos += s.externalZonesNanos;
            canAffordCalls += s.canAffordCalls;
            validTargetsCalls += s.validTargetsCalls;
            timeouts += s.timeouts;
            if (s.totalNanos > maxCallNanos) maxCallNanos = s.totalNanos;
            if (s.handNanos > maxHandNanos) maxHandNanos = s.handNanos;
            if (s.battlefieldNanos > maxBattlefieldNanos) maxBattlefieldNanos = s.battlefieldNanos;
            if (s.externalZonesNanos > maxExternalZonesNanos) maxExternalZonesNanos = s.externalZonesNanos;
            if (hasAction) foundAction++; else noActionFound++;
        }

        public double getTotalMs() { return totalNanos / 1_000_000.0; }
        public double getMaxCallMs() { return maxCallNanos / 1_000_000.0; }
        public double getAvgCallMs() { return calls > 0 ? getTotalMs() / calls : 0; }
        public double getHandMs() { return handNanos / 1_000_000.0; }
        public double getBattlefieldMs() { return battlefieldNanos / 1_000_000.0; }
        public double getExternalZonesMs() { return externalZonesNanos / 1_000_000.0; }
        public double getMaxHandMs() { return maxHandNanos / 1_000_000.0; }
        public double getMaxBattlefieldMs() { return maxBattlefieldNanos / 1_000_000.0; }
        public double getMaxExternalZonesMs() { return maxExternalZonesNanos / 1_000_000.0; }
    }
}
