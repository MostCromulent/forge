import { reconcile } from './render.js';
import { createCard, updateCard } from './cards.js';
import { zone } from './model.js';

export function renderHand(model, player, select) {
  const root = document.getElementById('hand');
  const cards = zone(model, player, 'Hand');
  reconcile(root, cards, c => c.$key, () => createCard(select), (el, c) => updateCard(el, model, c));
  const first = root.firstElementChild;
  if (!first || cards.length < 2) return;
  const cardWidth = first.offsetWidth;
  const step = Math.min(6, (root.clientWidth - 16 - cards.length * cardWidth) / (cards.length - 1));
  root.style.setProperty('--step', `${step}px`);
}
