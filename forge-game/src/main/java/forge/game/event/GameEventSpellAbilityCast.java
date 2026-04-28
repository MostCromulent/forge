package forge.game.event;

import forge.game.ability.ApiType;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.StackItemView;
import forge.game.spellability.TargetChoices;

public record GameEventSpellAbilityCast(SpellAbilityView sa, StackItemView si, int stackIndex, String targetDescription, boolean isMassRemoval) implements GameEvent {

    public GameEventSpellAbilityCast(SpellAbility sa, SpellAbilityStackInstance si, int stackIndex) {
        this(SpellAbilityView.get(sa), StackItemView.get(si), stackIndex, computeTargetDescription(sa), classifyMassRemoval(si));
    }

    private static String computeTargetDescription(SpellAbility sa) {
        if (sa.getTargetRestrictions() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (TargetChoices ch : sa.getAllTargetChoices()) {
            if (ch != null) { if (sb.length() > 0) sb.append(" "); sb.append(ch); }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // Recurses sub-instances so modal cards (e.g. Farewell) classify when the destructive mode is a sub-ability.
    private static boolean classifyMassRemoval(SpellAbilityStackInstance si) {
        if (si == null) return false;
        SpellAbility sa = si.getSpellAbility();
        if (sa != null) {
            ApiType api = sa.getApi();
            if (api == ApiType.DestroyAll
                    || api == ApiType.DamageAll
                    || api == ApiType.SacrificeAll
                    || api == ApiType.ChangeZoneAll) {
                return true;
            }
        }
        return classifyMassRemoval(si.getSubInstance());
    }

    /* (non-Javadoc)
     * @see forge.game.event.GameEvent#visit(forge.game.event.IGameEventVisitor)
     */
    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "" + si.getActivatingPlayer() + (sa.isSpell() ? " cast " : si.isTrigger() ? " triggered " : " activated ") + sa;
    }
}
