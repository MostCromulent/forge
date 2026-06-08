package forge.game.staticability;

import java.util.List;
import java.util.Set;

import com.google.common.collect.Lists;

import forge.card.mana.ManaCostParser;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class StaticAbilityAlternativeCost {

    public static List<SpellAbility> alternativeCosts(final SpellAbility sa, final Card source, final Player pl) {
        List<SpellAbility> result = Lists.newArrayList();
        // add source first in case it's LKI (alternate host)
        CardCollection list = new CardCollection(source);
        list.addAll(source.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES));
        for (final Card ca : list) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (!stAb.checkConditions(StaticAbilityMode.AlternativeCost)) {
                    continue;
                }

                if (!apply(stAb, sa, source, pl)) {
                    continue;
                }

                final boolean noCost = stAb.hasParam("WithoutManaCost") && !sa.isAbility();
                Cost cost = null;
                if (!noCost) {
                    String costTemplate = stAb.getParam("Cost").replace("ConvertedManaCost", Integer.toString(source.getCMC()));
                    cost = new Cost(costTemplate, sa.isAbility());
                }
                // set the cost to this directly to bypass non mana cost
                final SpellAbility newSA = noCost ? sa.copyWithNoManaCost(pl)
                        : (sa.isAbility() ? sa.copyWithDefinedCost(cost) : sa.copyWithManaCostReplaced(pl, cost));
                newSA.setActivatingPlayer(pl);
                newSA.setBasicSpell(false);
                newSA.setGrantorStatic(stAb);
                if (stAb.hasParam("WithFlash")) {
                    newSA.getRestrictions().setInstantSpeed(true);
                }

                if (stAb.hasParam("XAlternative")) {
                    newSA.putParam("XAlternative", stAb.getParam("XAlternative"));
                }

                if (stAb.hasParam("Announce")) {
                    newSA.putParam("Announce", stAb.getParam("Announce"));
                }

                if (stAb.hasParam("ManaRestriction")) {
                    newSA.putParam("ManaRestriction", stAb.getParam("ManaRestriction"));
                }

                if (!stAb.getHostCard().isImmutable()) {
                    Set<ZoneType> zones = stAb.getActiveZone();
                    if (zones != null && zones.size() == 1) {
                        newSA.getRestrictions().setZone(zones.stream().findFirst().get());
                    }
                }

                if (stAb.hasParam("StackDescription")) {
                    newSA.putParam("StackDescription", stAb.getParam("StackDescription"));
                }

                // makes new SpellDescription
                final StringBuilder sb = new StringBuilder();

                // CostDesc only for ManaCost?
                if (sa.isAbility()) {
                    newSA.putParam("CostDesc", stAb.hasParam("CostDesc") ? ManaCostParser.parse(stAb.getParam("CostDesc")) : cost.toSimpleString());
                    sb.append(newSA.getCostDescription());
                }

                // skip reminder text for now, Keywords might be too complicated
                //sb.append("(").append(newKi.getReminderText()).append(")");
                if (sa.isSpell()) {
                    sb.append(sa.getDescription());
                    if (source.equals(stAb.getHostCard())) {
                        newSA.addOptionalCost(OptionalCost.AltCost);
                        sb.append(" ("+ stAb.getParam("Description") +") ");
                    } else {
                        if (noCost) {
                            sb.append(" (without paying its mana cost");
                        } else {
                            sb.append(" (by paying ").append(cost.toSimpleString().replace("Pay ", "")).append(" instead of paying its mana cost");
                        }
                        if (stAb.hasParam("WithFlash")) {
                            sb.append(" and as though it has flash");
                        }
                        sb.append(")");
                        final Card altHost = stAb.getHostCard();
                        if (altHost.getRenderForUI()) {
                            sb.append(" by ").append(altHost.isImmutable() && altHost.getEffectSource() != null
                                    ? altHost.getEffectSource() : altHost);
                        }
                    }
                }
                newSA.setDescription(sb.toString());

                // Support for custom cards alternative costs targeting
                if (stAb.hasParam("Named")) {
                    newSA.setName(stAb.getParam("Named"));
                }

                result.add(newSA);
            }
        }
        return result;
    }

    private static boolean apply(final StaticAbility stAb, final SpellAbility sa, final Card source, final Player pl) {
        if (!stAb.matchesValidParam("ValidSA", sa)) {
            return false;
        }
        if (!stAb.matchesValidParam("ValidCard", source)) {
            return false;
        }
        if (!stAb.matchesValidParam("ValidPlayer", pl)) {
            return false;
        }
        if (stAb.hasParam("Limit")
                && stAb.getMayPlayTurn() >= Integer.parseInt(stAb.getParam("Limit"))) {
            return false;
        }

        return true;
    }

}
