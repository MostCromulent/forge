package forge.web;

import com.google.common.collect.Multimap;
import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardAttachment;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardCounters;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventPlayerCounters;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellResolved;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;
import forge.trackable.TrackableTypes;
import forge.trackable.Tracker;
import forge.web.ToBrowser.Attack;
import forge.web.ToBrowser.AttackersDeclared;
import forge.web.ToBrowser.Block;
import forge.web.ToBrowser.BlockersDeclared;
import forge.web.ToBrowser.CardAttached;
import forge.web.ToBrowser.CardCounters;
import forge.web.ToBrowser.CardDamaged;
import forge.web.ToBrowser.CardMoved;
import forge.web.ToBrowser.GameStarted;
import forge.web.ToBrowser.LifeChanged;
import forge.web.ToBrowser.Place;
import forge.web.ToBrowser.PlayerCounters;
import forge.web.ToBrowser.PlayerDamaged;
import forge.web.ToBrowser.Ref;
import forge.web.ToBrowser.Shuffled;
import forge.web.ToBrowser.StackAdded;
import forge.web.ToBrowser.StackResolved;

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
        if (event instanceof GameEventBlockersDeclared e && e.defendingPlayer() != null) {
            final List<Block> blocks = new ArrayList<>();
            for (final Multimap<CardView, CardView> blockersByAttacker : e.blockers().values()) {
                for (final Map.Entry<CardView, CardView> block : blockersByAttacker.entries()) {
                    // The engine lists an unblocked attacker as blocked by itself
                    if (block.getKey().getId() != block.getValue().getId()) {
                        blocks.add(new Block(Ref.card(block.getValue().getId()), Ref.card(block.getKey().getId())));
                    }
                }
            }
            return new BlockersDeclared(Ref.player(e.defendingPlayer().getId()), blocks);
        }
        if (event instanceof GameEventSpellAbilityCast e && e.si() != null) {
            return new StackAdded(player(e.si().getActivatingPlayer()));
        }
        if (event instanceof GameEventSpellResolved e && e.spell() != null) {
            final CardView host = e.spell().getHostCard();
            return new StackResolved(host == null ? null : player(host.getController()),
                    host == null ? null : Ref.card(host.getId()), e.hasFizzled());
        }
        if (event instanceof GameEventPlayerLivesChanged e && e.player() != null) {
            return new LifeChanged(Ref.player(e.player().getId()), e.oldLives(), e.newLives());
        }
        // The engine also fires this with no counter type when it only clears a player's counters
        if (event instanceof GameEventPlayerCounters e && e.receiver() != null && e.type() != null) {
            return new PlayerCounters(Ref.player(e.receiver().getId()));
        }
        if (event instanceof GameEventCardCounters e && e.card() != null) {
            return new CardCounters(Ref.card(e.card().getId()));
        }
        if (event instanceof GameEventCardAttachment e && e.equipment() != null) {
            final GameEntityView to = e.newTarget();
            return new CardAttached(Ref.card(e.equipment().getId()),
                    to instanceof CardView c ? Ref.card(c.getId()) : to instanceof PlayerView p ? Ref.player(p.getId()) : null);
        }
        if (event instanceof GameEventShuffle e && e.player() != null) {
            return new Shuffled(Ref.player(e.player().getId()));
        }
        if (event instanceof GameEventGameStarted e && e.firstTurn() != null) {
            return new GameStarted(Ref.player(e.firstTurn().getId()));
        }
        return null;
    }

    private static Ref player(final PlayerView player) {
        return player == null ? null : Ref.player(player.getId());
    }

    static Place place(final ZoneView zone) {
        return zone == null || zone.zoneType() == null ? null
                : new Place(zone.zoneType(), zone.player() == null ? null : Ref.player(zone.player().getId()));
    }
}
