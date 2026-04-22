package forge.perf.tokenheavy.variants;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.perf.OptimizationContext;
import forge.game.zone.ZoneType;

import java.util.HashSet;
import java.util.Set;

public final class H001_BattlefieldTokenDedup extends OptimizationContext {
    @Override
    public CardCollection filterDuplicateBattlefieldTokens(CardCollection cards) {
        CardCollection out = new CardCollection();
        Set<String> seenTokens = new HashSet<>();
        for (Card c : cards) {
            if (!c.isToken() || c.getZone() == null
                || c.getZone().getZoneType() != ZoneType.Battlefield) {
                out.add(c);
                continue;
            }
            String key = c.getName() + "|"
                + c.getController().getId() + "|"
                + c.getNetPower() + "/" + c.getNetToughness() + "|"
                + c.isTapped() + "|" + c.hasSickness();
            if (seenTokens.add(key)) out.add(c);
        }
        return out;
    }
}
