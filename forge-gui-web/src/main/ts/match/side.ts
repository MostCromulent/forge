// The column beside the board, holding the game log and, in a networked game, the chat dock, each of which collapses to its tab

import { byId, q } from '../dom';
import { changeUi, eased, rememberSidePanels, ui, type UiState } from '../ui';
import type { Model } from '../model';
import { isSilent } from '../volume';
import { t } from '../text';
import { isPortrait } from '../form';
import { ICONS, iconSvg } from '../icons';

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
  const cog = q(side, '#side-tools .cog');
  cog.innerHTML = iconSvg(ICONS.cog);
  cog.title = t('lblWebHeadOptions');
  cog.onclick = () => changeUi(u => { u.optionsOpen = true; });
  const volume = q(side, '#side-tools .volume');
  volume.title = t('lblWebHeadVolume');
  volume.onclick = () => changeUi(u => { u.volumeOpen = !u.volumeOpen; });
  const menu = Object.assign(document.createElement('button'), { id: 'menu-button', title: t('lblMenu') });
  menu.setAttribute('aria-label', t('lblMenu'));
  menu.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16"/></svg>';
  menu.onclick = () => changeUi(u => { u.menuSheet = u.menuSheet ? null : 'log'; });
  byId('match').append(menu);
  // The dock's height is read by every sheet, some drawn outside the board, and changes with what the prompt says
  let refit = 0;
  new ResizeObserver(() => {
    if (!isPortrait()) return;
    document.documentElement.style.setProperty('--dock-h', `${byId('prompt').offsetHeight}px`);
    // The board's halves take what the dock leaves, so their cards are fitted again once it has settled
    clearTimeout(refit);
    refit = window.setTimeout(() => window.dispatchEvent(new Event('resize')), 200);
  }).observe(byId('prompt'));
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
    volume.innerHTML = iconSvg(silent === 'true' ? ICONS.muted : ICONS.volume);
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
