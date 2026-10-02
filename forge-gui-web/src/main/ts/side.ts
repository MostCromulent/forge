// The column beside the board, holding the game log and, in a networked game, the chat dock, each of which collapses to its tab

import { byId, q } from './dom';
import { changeUi, changeUiNow, rememberSidePanels, ui, type UiState } from './ui';
import type { Model } from './model';
import { t } from './text';

const PANELS = ['log', 'chat'] as const;

/** Daybound and nightbound cards transform without asking, so the whole table changes at night as well as the corner that says which it is. */
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
  q(sky, '.when').textContent = time === 'Night' ? t('lblNight') : t('lblDay');
}

export function initSide(): void {
  const side = byId('side');
  for (const panel of PANELS) {
    q(side, `.side-toggle[data-panel="${panel}"]`).onclick = () => eased(u => {
      u.sidePanels[panel] = !u.sidePanels[panel];
      rememberSidePanels();
    });
  }
}

/** CSS cannot ease a change of grid areas, so a view transition eases between the two layouts, with the new one drawn at once. */
function eased(change: (state: UiState) => void): void {
  if (!document.startViewTransition || document.documentElement.dataset.motion === 'reduced') {
    changeUi(change);
    return;
  }
  const root = document.documentElement;
  const match = byId('match');
  const wasFolded = match.classList.contains('side-folded');
  const transition = document.startViewTransition(() => {
    changeUiNow(change);
    // The column slides off the right edge as it folds, and back in as it opens (board.css)
    const folded = match.classList.contains('side-folded');
    if (folded !== wasFolded) root.dataset.side = folded ? 'shut' : 'open';
  });
  transition.finished.finally(() => delete root.dataset.side);
}

/** Folded, per panel, as last drawn; the board is only told to reflow when that changes. */
let drawn = '';

// Chat is there only in a game others can join, and its dock is one of the screens (screens.tsx)
export function renderSide(model: Model): void {
  const hasChat = model.networked;
  const shown = { log: ui.sidePanels.log, chat: hasChat && ui.sidePanels.chat };
  const state = `${hasChat}/${shown.log}/${shown.chat}`;
  if (state === drawn) {
    return;
  }
  drawn = state;
  const side = byId('side');
  q(side, '#chat-panel').hidden = !hasChat;
  side.dataset.log = shown.log ? 'open' : 'shut';
  side.dataset.chat = shown.chat ? 'open' : 'shut';
  for (const panel of PANELS) q(side, `.side-toggle[data-panel="${panel}"]`).setAttribute('aria-expanded', String(shown[panel]));
  byId('match').classList.toggle('side-folded', !shown.log && !shown.chat);
  // The board changes width; anything placed by measuring it must be placed again
  window.dispatchEvent(new Event('resize'));
}
