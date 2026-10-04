package forge.web;

import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventShuffle;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;
import forge.trackable.TrackableTypes;
import forge.trackable.Tracker;
import forge.web.ToBrowser.Attack;
import forge.web.ToBrowser.AttackersDeclared;
import forge.web.ToBrowser.CardDamaged;
import forge.web.ToBrowser.CardMoved;
import forge.web.ToBrowser.GameStarted;
import forge.web.ToBrowser.Place;
import forge.web.ToBrowser.PlayerDamaged;
import forge.web.ToBrowser.Ref;
import forge.web.ToBrowser.Shuffled;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The game events the browser can animate, which travel with the state change they caused. */
final class BrowserEvents {
    private BrowserEvents() {
    }

    /** Returns null for an event the browser has nothing to show for, which the state covers. */
    static Record forwarded(final GameEvent event, final Tracker tracker) {
        if (event instanceof GameEventCardChangeZone e && e.card() != null) {
            PlayerView caster = null;
            if (e.to() != null && e.to().zoneType() == ZoneType.Stack) {
                caster = e.card().getController();
                // A card the game copied as it moved arrives without its controller, so the tracker's copy gives it
                final CardView tracked = caster == null && tracker != null ? tracker.getObj(TrackableTypes.CardViewType, e.card().getId()) : null;
                caster = tracked == null ? caster : tracked.getController();
            }
            return new CardMoved(Ref.card(e.card().getId()), place(e.from()), place(e.to()),
                    caster == null ? null : Ref.player(caster.getId()));
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
        if (event instanceof GameEventGameStarted e && e.firstTurn() != null) {
            return new GameStarted(Ref.player(e.firstTurn().getId()));
        }
        return null;
    }

    static Place place(final ZoneView zone) {
        return zone == null || zone.zoneType() == null ? null
                : new Place(zone.zoneType(), zone.player() == null ? null : Ref.player(zone.player().getId()));
    }
}
