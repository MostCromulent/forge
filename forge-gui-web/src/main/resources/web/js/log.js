import { imageUrl } from './cards.js';
import { hoverCard } from './detail.js';

const MAX_ENTRIES = 400;
const COLLAPSED_KEY = 'forge.logCollapsed';

export function initLog() {
  const button = document.querySelector('#side .log-toggle');
  const apply = collapsed => {
    document.getElementById('match').classList.toggle('log-collapsed', collapsed);
    button.textContent = collapsed ? 'Log' : 'Hide log';
    button.title = collapsed ? 'Show the game log' : 'Collapse the log to the edge';
    // The board changes width; anything placed by measuring it must be placed again
    window.dispatchEvent(new Event('resize'));
  };
  button.onclick = () => {
    const collapsed = !document.getElementById('match').classList.contains('log-collapsed');
    try {
      localStorage.setItem(COLLAPSED_KEY, collapsed ? '1' : '');
    } catch {
      // Storage can be unavailable; the choice then lasts until reload
    }
    apply(collapsed);
  };
  let stored = false;
  try {
    stored = localStorage.getItem(COLLAPSED_KEY) === '1';
  } catch {
    // As above
  }
  apply(stored);
}

// The server replays the whole log on connect (full) and sends new entries after that
export function appendLog(msg) {
  const root = document.getElementById('log');
  if (msg.full) root.replaceChildren();
  const atBottom = root.scrollHeight - root.scrollTop - root.clientHeight < 20;
  for (const entry of msg.entries) {
    const el = document.createElement('div');
    el.className = `log-entry ${entry.type.toLowerCase()}`;
    // Entries about a card show it, as the desktop log does
    if (entry.imageKey) {
      const thumb = document.createElement('img');
      thumb.className = 'thumb';
      thumb.alt = '';
      thumb.src = imageUrl(entry.imageKey);
      thumb.dataset.key = entry.card;
      thumb.dataset.zoom = thumb.src;
      thumb.addEventListener('error', () => thumb.remove());
      thumb.addEventListener('mouseenter', () => hoverCard(thumb));
      thumb.addEventListener('mouseleave', () => hoverCard(null));
      el.append(thumb);
    }
    const text = document.createElement('span');
    text.textContent = entry.message;
    el.append(text);
    root.append(el);
  }
  while (root.childElementCount > MAX_ENTRIES) root.firstChild.remove();
  if (atBottom || msg.full) root.scrollTop = root.scrollHeight;
}
