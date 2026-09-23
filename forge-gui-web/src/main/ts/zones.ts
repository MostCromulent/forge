import { reconcile } from './render';
import { createCard, updateCard, type CardClick } from './cards';
import { zone, type Model } from './model';
import { byId, q } from './dom';
import { changeUi, ui } from './ui';
import type { ZoneType } from './protocol';

// Piles the player opened by clicking, on top of the zones the game asks to show
interface Panel {
  player: number;
  zone: ZoneType;
  /** The game is showing it, so the game, not a Close button, puts it away. */
  forced: boolean;
}

export function togglePile(playerKey: number, zoneName: ZoneType): void {
  const k = `${playerKey}/${zoneName}`;
  changeUi(u => {
    if (!u.openZones.delete(k)) u.openZones.add(k);
  });
}

export function renderZones(model: Model, select: CardClick): void {
  const panels = new Map<string, Panel>();
  for (const z of model.zones) panels.set(`${z.player.ref}/${z.zone}`, { player: z.player.ref, zone: z.zone, forced: true });
  for (const k of ui.openZones) {
    if (panels.has(k)) continue;
    const [player, zoneName] = k.split('/');
    panels.set(k, { player: Number(player), zone: zoneName as ZoneType, forced: false });
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
