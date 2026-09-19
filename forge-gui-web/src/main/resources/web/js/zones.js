import { reconcile } from './render.js';
import { createCard, updateCard } from './cards.js';
import { zone } from './model.js';

// Piles the player opened by clicking, on top of the zones the game asks to show
const opened = new Set();
let last = null;

export function togglePile(playerKey, zoneName) {
  const k = `${playerKey}/${zoneName}`;
  if (opened.has(k)) opened.delete(k);
  else opened.add(k);
  if (last) renderZones(...last);
}

export function renderZones(model, select) {
  last = [model, select];
  const panels = new Map();
  for (const z of model.zones) panels.set(`${z.player.ref}/${z.zone}`, { player: z.player.ref, zone: z.zone, forced: true });
  for (const k of opened) {
    if (panels.has(k)) continue;
    const [player, zoneName] = k.split('/');
    panels.set(k, { player: Number(player), zone: zoneName, forced: false });
  }
  reconcile(document.getElementById('zones'), [...panels.entries()], ([k]) => k,
    () => {
      const el = document.createElement('div');
      el.className = 'zone-panel';
      el.innerHTML = '<header><b></b><button class="close">Close</button></header><div class="cards"></div>';
      return el;
    },
    (el, [, p]) => {
      const player = model.objects.get(p.player);
      el.querySelector('b').textContent = `${player?.Name ?? ''} · ${p.zone}`;
      const close = el.querySelector('.close');
      close.hidden = p.forced;
      close.onclick = () => togglePile(p.player, p.zone);
      reconcile(el.querySelector('.cards'), zone(model, player, p.zone), c => c.$key,
        () => createCard(select), (c, card) => updateCard(c, model, card));
    });
}
