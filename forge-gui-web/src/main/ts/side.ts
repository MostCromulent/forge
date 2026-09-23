// The column beside the board. It holds the game log and, in a networked game, the chat. Each collapses
// on its own; when both are shut the column folds to two tabs against the edge and the board takes the room.

import { wireChatInput, paintChat } from './chat';
import { byId, q } from './dom';

const PANELS = ['log', 'chat'] as const;
type PanelName = typeof PANELS[number];
const KEY = 'forge.sidePanels';

// A panel that is not there cannot be open, so chat starts shut and stays shut in an offline game
let open: Record<PanelName, boolean> = { log: true, chat: false };
let hasChat = false;

export function initSide(): void {
  const side = byId('side');
  open = { ...open, ...stored() };
  for (const panel of PANELS) {
    q(side, `.side-toggle[data-panel="${panel}"]`).onclick = () => {
      open[panel] = !open[panel];
      remember();
      apply();
    };
  }
  wireChatInput(byId<HTMLInputElement>('match-chat-in'));
  apply();
}

/** Chat only exists once there is someone else to talk to. */
export function setChatAvailable(available: boolean): void {
  if (hasChat === available) {
    return;
  }
  hasChat = available;
  apply();
}

export function renderSide(): void {
  if (hasChat) {
    paintChat(byId('match-chat-log'));
  }
}

function apply(): void {
  const side = byId('side');
  q(side, '#chat-panel').hidden = !hasChat;
  for (const panel of PANELS) {
    const shown = open[panel] && (panel !== 'chat' || hasChat);
    side.dataset[panel] = shown ? 'open' : 'shut';
    const button = q(side, `.side-toggle[data-panel="${panel}"]`);
    button.setAttribute('aria-expanded', String(shown));
  }
  const folded = !open.log && !(hasChat && open.chat);
  byId('match').classList.toggle('side-folded', folded);
  // The board changes width; anything placed by measuring it must be placed again
  window.dispatchEvent(new Event('resize'));
}

function stored(): Partial<Record<PanelName, boolean>> {
  try {
    return JSON.parse(localStorage.getItem(KEY) ?? 'null') ?? {};
  } catch {
    // Storage can be unavailable or hold something else; the defaults then last until reload
    return {};
  }
}

function remember(): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(open));
  } catch {
    // As above
  }
}
