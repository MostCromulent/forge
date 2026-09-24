import { reconcile } from './render';
import { createCard, updateCard, type CardClick } from './cards';
import { stateOf, zone, type Model } from './model';
import { setting } from './settings';
import { byId } from './dom';
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
  const cards = [...handOrder(model, zone(model, player, 'Flashback')), ...handOrder(model, zone(model, player, 'Hand'))];
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
  const step = Math.min(6, (root.clientWidth - 16 - cards.length * cardWidth) / (cards.length - 1));
  root.style.setProperty('--step', `${step}px`);
}
