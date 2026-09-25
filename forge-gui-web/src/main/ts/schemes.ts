// Archenemy on the board. A scheme set in motion is shown to the whole table for a moment, then the ones that stay
// in play sit in a row under their archenemy's seat.

import { reconcile } from './render';
import { cardImageSrc, setImage } from './images';
import { players, stateOf, zone, type Model } from './model';
import { hoverable } from './detail';
import { byId, q } from './dom';
import { commandKind } from './command';
import type { CardView, PlayerView } from './protocol';

const REVEAL_MS = 3000;

/** Schemes in play now that were not at the last look. The first look reveals nothing, so a reload replays nothing. */
export function newSchemes(seen: ReadonlySet<number> | null, now: readonly number[]): number[] {
  return seen ? now.filter(k => !seen.has(k)) : [];
}

const schemesOf = (model: Model, player: PlayerView): CardView[] =>
  zone(model, player, 'Command').filter(c => commandKind(c, stateOf(model, c)) === 'scheme');

/** A player with schemes still to come, or one in play, is an archenemy. The variant itself is not sent. */
export function isArchenemy(model: Model, player: PlayerView): boolean {
  return zone(model, player, 'SchemeDeck').length > 0 || schemesOf(model, player).length > 0;
}

/** The ongoing schemes under a seat, drawn landscape as they are printed. */
export function renderOngoing(root: HTMLElement, model: Model, player: PlayerView): void {
  reconcile(root, schemesOf(model, player), c => c.$key,
    () => {
      const el = document.createElement('div');
      el.className = 'scheme';
      el.innerHTML = '<img alt="" draggable="false"><span class="scheme-name"></span>';
      hoverable(el);
      return el;
    },
    (el, card) => {
      const src = cardImageSrc(model, card);
      setImage(q<HTMLImageElement>(el, 'img'), src);
      el.dataset.key = String(card.$key);
      el.dataset.zoom = src;
      const name = stateOf(model, card).Name ?? '';
      q(el, '.scheme-name').textContent = name;
      el.title = name;
    });
}

let seen: Set<number> | null = null;

/** Forgets what was seen, so a new game reveals from its own first look. */
export function resetSchemes(): void {
  seen = null;
}

export function revealSchemes(model: Model): void {
  const all = players(model).flatMap(p => schemesOf(model, p).map(c => ({ card: c, owner: p })));
  const fresh = newSchemes(seen, all.map(s => s.card.$key));
  seen = new Set(all.map(s => s.card.$key));
  if (!fresh.length || document.documentElement.dataset.motion === 'reduced') return;
  const { card, owner } = all.find(s => s.card.$key === fresh[fresh.length - 1])!;
  const reveal = document.createElement('div');
  reveal.className = 'scheme-reveal';
  reveal.innerHTML = '<div class="scheme big"><img alt=""><span class="scheme-name"></span></div><p></p>';
  setImage(q<HTMLImageElement>(reveal, 'img'), cardImageSrc(model, card));
  q(reveal, '.scheme-name').textContent = stateOf(model, card).Name ?? '';
  q(reveal, 'p').textContent = `${owner.Name ?? 'The archenemy'} sets a scheme in motion`;
  byId('match').append(reveal);
  setTimeout(() => reveal.remove(), REVEAL_MS);
}
