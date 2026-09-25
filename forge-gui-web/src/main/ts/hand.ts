// The player's hand along the bottom of the board, fanned and sorted as the options say.

import { commandKind } from './command';
import { reconcile } from './render';
import { createCard, updateCard, type CardClick } from './cards';
import { deref, isLocal, players, stateOf, zone, type Model } from './model';
import { logTints } from './log';
import { setting } from './settings';
import { byId, q } from './dom';
import type { CardView, PlayerView, ZoneType } from './protocol';

// {2}{W} counts as three; a hybrid shard counts as one and X as nothing
function manaValue(cost: string | undefined): number {
  return [...String(cost ?? '').matchAll(/\{([^}]+)\}/g)].reduce((sum, [, shard]) => {
    const digits = shard.split('/').find(part => /^\d+$/.test(part));
    return sum + (digits !== undefined ? Number(digits) : /^[xyz]$/i.test(shard) ? 0 : 1);
  }, 0);
}

function handOrder(model: Model, cards: CardView[]): CardView[] {
  if (setting('handSort') !== 'mana') {
    return cards;
  }
  return [...cards].sort((a, b) => manaValue(stateOf(model, a).ManaCost) - manaValue(stateOf(model, b).ManaCost)
    || String(stateOf(model, a).Name ?? '').localeCompare(stateOf(model, b).Name ?? ''));
}

/**
 * Cards you may play from somewhere that is not your hand: flashback, escape, adventure, foretell, a land out of
 * the graveyard. The engine gathers them into its Flashback pseudo-zone and the view keeps it current, so they
 * only have to be laid out. They come before the hand and are ordered by the same rule, and they stay there
 * whether or not you can afford them — the playable outline is what says which are castable right now.
 *
 * Each one still reports the zone it is really in, which is what the desktop client labels them by.
 */
function fromElsewhere(model: Model, player: PlayerView | undefined): Map<number, ZoneType> {
  const found = new Map<number, ZoneType>();
  for (const card of zone(model, player, 'Flashback')) {
    if (card.Zone) {
      found.set(card.$key, card.Zone);
    }
  }
  return found;
}

export function renderHand(model: Model, player: PlayerView | undefined, select: CardClick): void {
  const root = byId('hand');
  const elsewhere = fromElsewhere(model, player);
  const tints = logTints(players(model).map(p => ({ name: p.Name ?? '', local: isLocal(model, p) })));
  // The planar die has a button of its own by the plane, so it is not laid out with the cards
  const others = zone(model, player, 'Flashback').filter(c => commandKind(c, stateOf(model, c)) !== 'dice');
  const cards = [...handOrder(model, others), ...handOrder(model, zone(model, player, 'Hand'))];
  reconcile(root, cards, c => c.$key, () => createCard(select), (el, c) => {
    updateCard(el, model, c);
    const source = elsewhere.get(c.$key);
    el.classList.toggle('elsewhere', source !== undefined);
    // The zone picks the glow's colour in CSS, and names itself in the detail panel when the card is hovered
    if (source) {
      el.dataset.from = source;
    } else {
      delete el.dataset.from;
    }
    // Another player's card you may play says whose it is, so it is not taken for one of your own
    const owner = deref(model, c.Owner);
    const foreign = !!owner && !!player && owner.$key !== player.$key;
    el.classList.toggle('foreign', foreign);
    q(el, '.owned-by').textContent = foreign ? `${owner?.Name ?? ''}'s` : '';
    if (foreign) el.style.setProperty('--owner-tint', tints.find(t => t.name === owner?.Name)?.colour ?? 'var(--muted)');
    // A card another player may look at has been revealed to them
    el.classList.toggle('revealed', (c.PlayerMayLook ?? []).some(r => !!r && !model.localPlayers.includes(r.ref)));
  });
  // A shallow arc: at most 2 degrees per card from the middle, 10 at the ends
  const mid = (cards.length - 1) / 2;
  const perCard = mid > 0 ? Math.min(2, 10 / mid) : 0;
  [...root.children].forEach((child, i) => {
    const el = child as HTMLElement;
    el.style.setProperty('--tilt', `${(i - mid) * perCard}deg`);
    el.style.setProperty('--drop', `${((i - mid) * perCard) ** 2 * 0.12}px`);
  });
  const first = root.firstElementChild as HTMLElement | null;
  if (!first || cards.length < 2) return;
  const cardWidth = first.offsetWidth;
  // The room the hand keeps clear for the prompt beside it is not room for cards
  const style = getComputedStyle(root);
  const width = root.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
  const step = Math.min(6, (width - 16 - cards.length * cardWidth) / (cards.length - 1));
  root.style.setProperty('--step', `${step}px`);
}
