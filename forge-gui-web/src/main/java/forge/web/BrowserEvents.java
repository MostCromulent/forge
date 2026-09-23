package forge.web;

import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventShuffle;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneView;
import forge.web.ToBrowser.Attack;
import forge.web.ToBrowser.AttackersDeclared;
import forge.web.ToBrowser.CardDamaged;
import forge.web.ToBrowser.CardMoved;
import forge.web.ToBrowser.Place;
import forge.web.ToBrowser.PlayerDamaged;
import forge.web.ToBrowser.Ref;
import forge.web.ToBrowser.Shuffled;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What happened in the game, as the browser animates it. Forge bundles its events with the state change they caused;
 * the ones a renderer can show travel with that state, and the rest are covered by the state itself.
 */
final class BrowserEvents {
    private BrowserEvents() {
    }

    /** The events a renderer can show, as the browser names them; null for the rest, which the state covers. */
    static Record forwarded(final GameEvent event) {
        if (event instanceof GameEventCardChangeZone e && e.card() != null) {
            return new CardMoved(Ref.card(e.card().getId()), place(e.from()), place(e.to()));
        }
        if (event instanceof GameEventCardDamaged e && e.card() != null) {
            return new CardDamaged(Ref.card(e.card().getId()), e.source() == null ? null : Ref.card(e.source().getId()), e.amount());
        }
        if (event instanceof GameEventPlayerDamaged e && e.target() != null) {
            return new PlayerDamaged(Ref.player(e.target().getId()), e.source() == null ? null : Ref.card(e.source().getId()),
                    e.amount(), e.combat());
        }
        if (event instanceof GameEventAttackersDeclared e && e.player() != null) {
            final List<Attack> attacks = new ArrayList<>();
            for (final Map.Entry<GameEntityView, CardView> attack : e.attackersMap().entries()) {
                final GameEntityView defender = attack.getKey();
                attacks.add(new Attack(Ref.card(attack.getValue().getId()),
                        defender instanceof CardView c ? Ref.card(c.getId())
                                : defender instanceof PlayerView p ? Ref.player(p.getId()) : null));
            }
            return new AttackersDeclared(Ref.player(e.player().getId()), attacks);
        }
        if (event instanceof GameEventShuffle e && e.player() != null) {
            return new Shuffled(Ref.player(e.player().getId()));
        }
        return null;
    }

    static Place place(final ZoneView zone) {
        return zone == null || zone.zoneType() == null ? null
                : new Place(zone.zoneType(), zone.player() == null ? null : Ref.player(zone.player().getId()));
    }
}
