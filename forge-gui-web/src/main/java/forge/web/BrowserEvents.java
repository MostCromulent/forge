package forge.web;

import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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
        if (event instanceof GameEventGameStarted e && e.firstTurn() != null) {
            return new GameStarted(Ref.player(e.firstTurn().getId()));
        }
        return null;
    }

    /** Zones everyone can see into, where a card arriving or leaving is something to look at. */
    private static final Set<ZoneType> OPEN = EnumSet.of(ZoneType.Battlefield, ZoneType.Stack, ZoneType.Graveyard,
            ZoneType.Exile, ZoneType.Command);

    /**
     * Whether an event is something the player would want to see before the game passes priority for them: another
     * player acting in the open, or anything being dealt damage. What the player did themselves they have seen, and
     * what nobody can see (an opponent's draw) there is nothing to look at.
     */
    static boolean worthSeeing(final GameEvent event, final Predicate<PlayerView> mine) {
        if (event instanceof GameEventSpellAbilityCast e) {
            return e.si() != null && !mine.test(e.si().getActivatingPlayer());
        }
        // Forge declares attackers every combat, even when nobody attacks
        if (event instanceof GameEventAttackersDeclared e) {
            return !mine.test(e.player()) && !e.attackersMap().isEmpty();
        }
        if (event instanceof GameEventBlockersDeclared e) {
            return !mine.test(e.defendingPlayer());
        }
        if (event instanceof GameEventCardChangeZone e) {
            return e.card() != null && !mine.test(mover(e)) && (isOpen(e.from()) || isOpen(e.to()));
        }
        return event instanceof GameEventCardDamaged || event instanceof GameEventPlayerDamaged;
    }

    /**
     * Whose card moved. A card that has just left a zone can arrive as a copy with no controller, so then it is whoever
     * owns the zones it moved between: a discard is from its own player's hand to their own graveyard.
     */
    private static PlayerView mover(final GameEventCardChangeZone e) {
        if (e.card().getController() != null) {
            return e.card().getController();
        }
        if (e.from() != null && e.from().player() != null) {
            return e.from().player();
        }
        return e.to() == null ? null : e.to().player();
    }

    private static boolean isOpen(final ZoneView zone) {
        return zone != null && OPEN.contains(zone.zoneType());
    }

    static Place place(final ZoneView zone) {
        return zone == null || zone.zoneType() == null ? null
                : new Place(zone.zoneType(), zone.player() == null ? null : Ref.player(zone.player().getId()));
    }
}
