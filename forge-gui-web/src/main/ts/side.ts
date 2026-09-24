// The column beside the board. It holds the game log and, in a networked game, the chat dock under it. The log
// collapses; the dock has its own header and stays. With the log shut and no dock, the column folds to a tab
// against the edge and the board takes the room.

import { byId, q } from './dom';
import { changeUi, rememberSidePanels, ui } from './ui';
import type { Model } from './model';

const PANELS = ['log'] as const;

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

// Chat is there only in a game others can join. The dock itself is one of the screens (screens.tsx); this only
// opens and shuts the column's panels.
export function renderSide(model: Model): void {
  const hasChat = model.networked;
  const shown = { log: ui.sidePanels.log, chat: hasChat };
  const state = `${hasChat}/${shown.log}/${shown.chat}`;
  if (state === drawn) {
    return;
  }
  drawn = state;
  const side = byId('side');
  q(side, '#chat-panel').hidden = !hasChat;
  side.dataset.log = shown.log ? 'open' : 'shut';
  side.dataset.chat = shown.chat ? 'open' : 'shut';
  q(side, '.side-toggle[data-panel="log"]').setAttribute('aria-expanded', String(shown.log));
  byId('match').classList.toggle('side-folded', !shown.log && !shown.chat);
  // The board changes width; anything placed by measuring it must be placed again
  window.dispatchEvent(new Event('resize'));
}
