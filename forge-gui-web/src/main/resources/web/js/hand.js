import { reconcile } from './render.js';
import { createCard, updateCard } from './cards.js';
import { zone } from './model.js';

export function renderHand(model, player, select) {
  const root = document.getElementById('hand');
  const cards = zone(model, player, 'Hand');
  reconcile(root, cards, c => c.$key, () => createCard(select), (el, c) => updateCard(el, model, c));
  // A shallow arc: at most 2 degrees per card from the middle, 10 at the ends
  const mid = (cards.length - 1) / 2;
  const perCard = mid > 0 ? Math.min(2, 10 / mid) : 0;
  [...root.children].forEach((el, i) => {
    el.style.setProperty('--tilt', `${(i - mid) * perCard}deg`);
    el.style.setProperty('--drop', `${((i - mid) * perCard) ** 2 * 0.12}px`);
  });
  const first = root.firstElementChild;
  if (!first || cards.length < 2) return;
  const cardWidth = first.offsetWidth;
  const step = Math.min(6, (root.clientWidth - 16 - cards.length * cardWidth) / (cards.length - 1));
  root.style.setProperty('--step', `${step}px`);
}
