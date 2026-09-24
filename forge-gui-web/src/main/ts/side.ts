// The column beside the board. It holds the game log and, in a networked game, the chat. Each collapses
// on its own; when both are shut the column folds to two tabs against the edge and the board takes the room.

import { byId, q } from './dom';
import { changeUi, rememberSidePanels, ui } from './ui';
import type { Model } from './model';

const PANELS = ['log', 'chat'] as const;

/**
 * Whether it is day or night, in the corner of the table. Cards with daybound and nightbound transform on it
 * without asking, so it has to be readable without being looked for: the table itself cools at night, and the
 * corner says which it is for anyone checking.
 *
 * The host has sent this in the Controls message all along; nothing had ever read it.
 */
export function renderSky(model: Model): void {
  const sky = byId('sky');
  const time = model.controls?.dayTime;
  sky.hidden = !time;
  byId('match').classList.toggle('night', time === 'Night');
  if (!time) {
    return;
  }
  if (!sky.firstChild) {
    sky.innerHTML = '<i class="orb" aria-hidden="true"></i><span class="when"></span>';
  }
  sky.dataset.time = time;
  q(sky, '.when').textContent = time;
}

export function initSide(): void {
  const side = byId('side');
  for (const panel of PANELS) {
    q(side, `.side-toggle[data-panel="${panel}"]`).onclick = () => changeUi(u => {
      u.sidePanels[panel] = !u.sidePanels[panel];
      rememberSidePanels();
    });
  }
}

/** Folded, per panel, as last drawn; the board is only told to reflow when that changes. */
let drawn = '';

// A panel that is not there cannot be open, so chat stays shut in a game nobody else is in. The chat itself is
// one of the screens (screens.tsx); this only opens and shuts the column's panels.
export function renderSide(model: Model): void {
  const hasChat = model.networked;
  const shown = { log: ui.sidePanels.log, chat: ui.sidePanels.chat && hasChat };
  const state = `${hasChat}/${shown.log}/${shown.chat}`;
  if (state === drawn) {
    return;
  }
  drawn = state;
  const side = byId('side');
  q(side, '#chat-panel').hidden = !hasChat;
  for (const panel of PANELS) {
    side.dataset[panel] = shown[panel] ? 'open' : 'shut';
    q(side, `.side-toggle[data-panel="${panel}"]`).setAttribute('aria-expanded', String(shown[panel]));
  }
  byId('match').classList.toggle('side-folded', !shown.log && !shown.chat);
  // The board changes width; anything placed by measuring it must be placed again
  window.dispatchEvent(new Event('resize'));
}
