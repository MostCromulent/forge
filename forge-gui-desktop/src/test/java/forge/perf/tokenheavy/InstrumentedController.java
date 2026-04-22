package forge.perf.tokenheavy;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.combat.Combat;
import forge.game.perf.OptimizationContext;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.List;

/**
 * PlayerControllerAi subclass that captures AI decisions at four hook points
 * and — in QUERY mode — runs one or more variant contexts against the same
 * pre-decision state to produce apples-to-apples timing.
 *
 * Controller-cache reset (tactical decision): between variant calls we reset
 * the context only. PlayerControllerAi's fields are reached via its public
 * API; if a specific cache is observed to leak between variants during
 * implementation (e.g. divergences appear on identical variants), switch to
 * recreating the controller per decision. Not worth pre-engineering.
 */
public class InstrumentedController extends PlayerControllerAi {

    public enum Mode { QUERY, RUN }

    public static final class VariantSlot {
        public final String name;
        public final OptimizationContext context;
        public final List<DecisionRecord> decisions = new ArrayList<>();
        public long totalNanos;
        public VariantSlot(String name, OptimizationContext context) {
            this.name = name;
            this.context = context;
        }
    }

    private final Mode mode;
    private final List<VariantSlot> slots;     // [0] = BASELINE

    public InstrumentedController(Game game, Player player, LobbyPlayer lp,
                                  Mode mode, List<VariantSlot> slots) {
        super(game, player, lp);
        this.mode = mode;
        this.slots = slots;
    }

    public List<VariantSlot> getSlots() { return slots; }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        return runHooked("chooseSpellAbilityToPlay", () -> super.chooseSpellAbilityToPlay());
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        runHookedVoid("declareAttackers", () -> super.declareAttackers(attacker, combat));
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        runHookedVoid("declareBlockers", () -> super.declareBlockers(defender, combat));
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        return runHookedBool("chooseTargetsFor", () -> super.chooseTargetsFor(currentAbility));
    }

    // ---- hook dispatch ----

    interface DecisionCapture<T> { T run(); }

    private <T> T runHooked(String hookName, DecisionCapture<T> baselineCall) {
        if (mode == Mode.RUN) {
            // Run mode: the single variant for this run is already set globally.
            long t0 = System.nanoTime();
            T result = baselineCall.run();
            long dt = System.nanoTime() - t0;
            slots.get(0).totalNanos += dt;
            slots.get(0).decisions.add(buildRecord(hookName, result));
            return result;
        }

        // Query mode: baseline first, capture its result; then each variant.
        T baselineResult = null;
        for (int i = 0; i < slots.size(); i++) {
            VariantSlot s = slots.get(i);
            OptimizationContext.set(s.context);
            long t0 = System.nanoTime();
            T result = baselineCall.run();
            long dt = System.nanoTime() - t0;
            s.totalNanos += dt;
            s.decisions.add(buildRecord(hookName, result));
            if (i == 0) baselineResult = result;
            OptimizationContext.reset();
        }
        return baselineResult;
    }

    private void runHookedVoid(String hookName, Runnable baselineCall) {
        runHooked(hookName, () -> { baselineCall.run(); return null; });
    }

    private boolean runHookedBool(String hookName, java.util.function.BooleanSupplier baselineCall) {
        return runHooked(hookName, () -> baselineCall.getAsBoolean());
    }

    private DecisionRecord buildRecord(String hookName, Object decision) {
        int turn = getPlayer().getGame().getPhaseHandler().getTurn();
        String phase = String.valueOf(getPlayer().getGame().getPhaseHandler().getPhase());
        String active = getPlayer().getName();
        return new DecisionRecord(hookName, turn, phase, active, describeDecision(decision));
    }

    private List<String> describeDecision(Object decision) {
        if (decision == null) return List.of("<void>");
        if (decision instanceof List) {
            List<?> l = (List<?>) decision;
            List<String> out = new ArrayList<>(l.size());
            for (Object o : l) out.add(describeOne(o));
            return out;
        }
        return List.of(describeOne(decision));
    }

    private String describeOne(Object o) {
        if (o == null) return "null";
        if (o instanceof SpellAbility) {
            SpellAbility sa = (SpellAbility) o;
            return (sa.getHostCard() == null ? "?" : sa.getHostCard().getName())
                + "|" + sa.getDescription()
                + "|" + (sa.getTargets() == null ? "<nt>" : sa.getTargets().toString());
        }
        return String.valueOf(o);
    }
}
