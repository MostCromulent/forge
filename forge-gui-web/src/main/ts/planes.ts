// Planechase on the board. The face-up planes rule the whole table, so they sit in a dock of their own on the
// divider between the halves rather than among a player's tokens, and the die that moves them sits beside them.

import { reconcile } from './render';
import { cardImageSrc, setImage } from './images';
import { deref, game, me, players, stateOf, zone, type Model } from './model';
import { hoverable } from './detail';
import { cssUrl } from './looks';
import { byId, q } from './dom';
import { commandKind } from './command';
import type { Actions } from './actions';
import type { CardView, GameView, PlayerView } from './protocol';

/**
 * The planes and phenomena face up now. The engine moves them into the command zone of whoever's turn it is, while
 * the game's PlanarPlayer can still name the first player, so every command zone is looked through.
 */
export function faceUpPlanes(model: Model): CardView[] {
  return players(model).flatMap(p => zone(model, p, 'Command')).filter(c => commandKind(c, stateOf(model, c)) === 'plane');
}

/**
 * Whether the viewer may roll now. The roll is a special action at sorcery speed, so the viewer must hold priority
 * on their own turn in a main phase with nothing on the stack. The engine refuses anything else in any case.
 */
export function mayRoll(prompt: { priority: boolean } | null | undefined, g: GameView | undefined, viewer: number | undefined): boolean {
  return !!prompt?.priority && g?.PlayerTurn?.ref === viewer && (g?.Phase === 'MAIN1' || g?.Phase === 'MAIN2')
    && !(g?.Stack ?? []).some(Boolean);
}

export function renderPlanes(model: Model, actions: Actions): void {
  const match = byId('match');
  let dock = document.getElementById('plane-dock');
  if (!dock) {
    dock = document.createElement('section');
    dock.id = 'plane-dock';
    dock.innerHTML = '<div class="planes"></div><div class="plane-side"><p class="plane-from"></p>'
      + '<button class="die-button" title="Roll the planar die" disabled>Roll the planar die</button></div>';
    match.append(dock);
  }
  const planes = faceUpPlanes(model);
  dock.hidden = planes.length === 0;
  match.classList.toggle('planar', planes.length > 0);
  if (!planes.length) return;

  reconcile(q(dock, '.planes'), planes, c => c.$key,
    () => {
      const el = document.createElement('div');
      el.className = 'plane';
      el.innerHTML = '<img alt="" draggable="false"><span class="plane-name"></span>';
      hoverable(el);
      return el;
    },
    (el, card) => {
      const src = cardImageSrc(model, card);
      setImage(q<HTMLImageElement>(el, 'img'), src);
      el.dataset.key = String(card.$key);
      el.dataset.zoom = src;
      q(el, '.plane-name').textContent = stateOf(model, card).Name ?? '';
    });
  // The plane's art tints the table, as the mobile client does
  match.style.setProperty('--plane-art', cssUrl(cardImageSrc(model, planes[0])));
  const owner = deref(model, planes[0].Owner) as PlayerView | undefined;
  q(dock, '.plane-from').textContent = owner?.Name ? `From ${owner.Name}'s planar deck` : '';

  const viewer = me(model);
  const dice = viewer ? zone(model, viewer, 'Command').find(c => commandKind(c, stateOf(model, c)) === 'dice') : undefined;
  const button = q<HTMLButtonElement>(dock, '.die-button');
  button.hidden = !dice;
  button.disabled = !dice || !mayRoll(model.prompt, game(model), viewer?.$key);
  button.onclick = () => {
    if (!dice) return;
    const box = button.getBoundingClientRect();
    actions.selectCard(dice.$key, false, box.left, box.top);
  };
  // The dock hangs just above the divider between the two halves of the table
  const strip = byId('phase-strip');
  dock.style.top = `${Math.max(8, strip.offsetTop - dock.offsetHeight - 6)}px`;
}
