import { reconcile } from './render';
import { createCard, updateCard, type CardClick } from './cards';
import { zone, type Model } from './model';
import { byId, q } from './dom';
import type { ZoneName } from './protocol';

// Piles the player opened by clicking, on top of the zones the game asks to show
const opened = new Set<string>();
let schedule: () => void = () => {};

interface Panel {
  player: number;
  zone: ZoneName;
  forced: boolean;
}

export function initZones(scheduleFn: () => void): void {
  schedule = scheduleFn;
}

export function resetZones(): void {
  opened.clear();
}

export function togglePile(playerKey: number, zoneName: ZoneName): void {
  const k = `${playerKey}/${zoneName}`;
  if (opened.has(k)) opened.delete(k);
  else opened.add(k);
  schedule();
}

export function renderZones(model: Model, select: CardClick): void {
  const panels = new Map<string, Panel>();
  for (const z of model.zones) panels.set(`${z.player.ref}/${z.zone}`, { player: z.player.ref, zone: z.zone, forced: true });
  for (const k of opened) {
    if (panels.has(k)) continue;
    const [player, zoneName] = k.split('/');
    panels.set(k, { player: Number(player), zone: zoneName as ZoneName, forced: false });
  }
  reconcile(byId('zones'), [...panels.entries()], ([k]) => k,
    () => {
      const el = document.createElement('div');
      el.className = 'zone-panel';
      el.innerHTML = '<header><b></b><button class="close">Close</button></header><div class="cards"></div>';
      return el;
    },
    (el, [, p]) => {
      const player = model.objects.get(p.player);
      q(el, 'b').textContent = `${player?.Name ?? ''} · ${p.zone}`;
      const close = q(el, '.close');
      close.hidden = p.forced;
      close.onclick = () => togglePile(p.player, p.zone);
      reconcile(q(el, '.cards'), zone(model, player, p.zone), c => c.$key,
        () => createCard(select), (c, card) => updateCard(c, model, card));
    });
}
