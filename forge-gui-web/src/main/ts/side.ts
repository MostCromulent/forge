// The column beside the board. It holds the game log and, in a networked game, the chat. Each collapses
// on its own; when both are shut the column folds to two tabs against the edge and the board takes the room.

import { wireChatInput, paintChat } from './chat';
import { byId, q } from './dom';
import { changeUi, rememberSidePanels, ui } from './ui';
import type { Actions } from './actions';
import type { Model } from './model';

const PANELS = ['log', 'chat'] as const;

export function initSide(actions: Actions): void {
  const side = byId('side');
  for (const panel of PANELS) {
    q(side, `.side-toggle[data-panel="${panel}"]`).onclick = () => changeUi(u => {
      u.sidePanels[panel] = !u.sidePanels[panel];
      rememberSidePanels();
    });
  }
  wireChatInput(byId<HTMLInputElement>('match-chat-in'), actions.say);
}

/** Folded, per panel, as last drawn; the board is only told to reflow when that changes. */
let drawn = '';

// A panel that is not there cannot be open, so chat stays shut in a game nobody else is in
export function renderSide(model: Model): void {
  const hasChat = model.networked;
  if (hasChat) {
    paintChat(byId('match-chat-log'), model);
  }
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
