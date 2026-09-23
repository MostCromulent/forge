import { reconcile } from './render';
import { createCard, updateCard, type CardClick } from './cards';
import { stateOf, zone, type Model } from './model';
import { setting } from './settings';
import { byId } from './dom';
import type { CardView, PlayerView } from './protocol';

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

export function renderHand(model: Model, player: PlayerView | undefined, select: CardClick): void {
  const root = byId('hand');
  const cards = handOrder(model, zone(model, player, 'Hand'));
  reconcile(root, cards, c => c.$key, () => createCard(select), (el, c) => updateCard(el, model, c));
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
