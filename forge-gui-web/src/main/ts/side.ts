// The column beside the board, holding the game log and, in a networked game, the chat dock, each of which collapses to its tab

import { byId, q } from './dom';
import { changeUi, eased, rememberSidePanels, ui, type UiState } from './ui';
import type { Model } from './model';
import { isSilent } from './volume';
import { t } from './text';
import { isPortrait } from './form';

const PANELS = ['log', 'chat'] as const;

// Icons from Lucide (ISC, see web/licenses/lucide-license.txt), drawn on the same 24-unit grid
const ICONS = {
  volume: '<path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"/><path d="M16 9a5 5 0 0 1 0 6"/><path d="M19.364 18.364a9 9 0 0 0 0-12.728"/>',
  muted: '<path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"/><line x1="22" x2="16" y1="9" y2="15"/><line x1="16" x2="22" y1="9" y2="15"/>',
  cog: '<path d="M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915"/><circle cx="12" cy="12" r="3"/>',
};
const icon = (name: keyof typeof ICONS) => `<svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[name]}</svg>`;

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
  const cog = q(side, '#side-tools .cog');
  cog.innerHTML = icon('cog');
  cog.title = t('lblWebHeadOptions');
  cog.onclick = () => changeUi(u => { u.optionsOpen = true; });
  const volume = q(side, '#side-tools .volume');
  volume.title = t('lblWebHeadVolume');
  volume.onclick = () => changeUi(u => { u.volumeOpen = !u.volumeOpen; });
  const menu = Object.assign(document.createElement('button'), { id: 'menu-button', title: t('lblWebPortraitMenu') });
  menu.setAttribute('aria-label', t('lblWebPortraitMenu'));
  menu.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16"/></svg>';
  menu.onclick = () => changeUi(u => { u.menuSheet = u.menuSheet ? null : 'log'; });
  byId('match').append(menu);
  for (const panel of PANELS) {
    q(side, `.side-toggle[data-panel="${panel}"]`).onclick = () => folding(u => {
      u.sidePanels[panel] = !u.sidePanels[panel];
      rememberSidePanels();
    });
  }
}

/** CSS cannot ease a change of grid areas, so the column slides off the right edge as it folds, and back in as it opens (board.css). */
function folding(change: (state: UiState) => void): void {
  const match = byId('match');
  const wasFolded = match.classList.contains('side-folded');
  eased(change, (): Record<string, string> => {
    const folded = match.classList.contains('side-folded');
    return folded === wasFolded ? {} : { side: folded ? 'shut' : 'open' };
  });
}

/** On a phone the prompt is the board's own bottom row, so it leaves the column, which is not shown there. */
function placePrompt(): void {
  const prompt = byId('prompt');
  const home = isPortrait() ? byId('match') : byId('side');
  if (prompt.parentElement !== home) home.append(prompt);
  if (isPortrait()) byId('match').style.setProperty('--dock-h', `${prompt.offsetHeight}px`);
}

/** Folded, per panel, as last drawn; the board is only told to reflow when that changes. */
let drawn = '';

// Chat is there only in a game others can join, and its dock is one of the screens (screens.tsx)
export function renderSide(model: Model): void {
  placePrompt();
  const volume = q(byId('side-tools'), '.volume');
  const silent = String(isSilent());
  if (volume.dataset.silent !== silent) {
    volume.dataset.silent = silent;
    volume.innerHTML = icon(silent === 'true' ? 'muted' : 'volume');
  }
  volume.classList.toggle('open', ui.volumeOpen);
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
