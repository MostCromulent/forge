import { reconcile } from './render.js';
import { createCard, updateCard } from './cards.js';
import { zone } from './model.js';

export function renderHand(model, player, select) {
  const root = document.getElementById('hand');
  const cards = zone(model, player, 'Hand');
  reconcile(root, cards, c => c.$key, () => createCard(select), (el, c) => updateCard(el, model, c));
  const mid = (cards.length - 1) / 2;
  [...root.children].forEach((el, i) => {
    el.style.setProperty('--fan', i - mid);
    el.style.setProperty('--lift', `${Math.abs(i - mid) * 5}px`);
  });
}
