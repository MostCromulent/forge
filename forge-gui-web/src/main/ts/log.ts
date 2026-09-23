import { imageUrl, setSymbolText } from './images';
import { setting } from './settings';
import { hoverable } from './detail';
import { byId } from './dom';
import type { LogMessage } from './protocol';

const MAX_ENTRIES = 400;

// The log follows the game unless the player scrolls back through it
let stick = true;

export function initLog(): void {
  const log = byId('log');
  log.addEventListener('scroll', () => {
    stick = log.scrollHeight - log.scrollTop - log.clientHeight < 24;
  });
}

// The server replays the whole log on connect (full) and sends new entries after that
export function appendLog(msg: LogMessage): void {
  const root = byId('log');
  if (msg.full) {
    root.replaceChildren();
    stick = true;
  }
  for (const entry of msg.entries) {
    const el = document.createElement('div');
    el.className = `log-entry ${entry.type.toLowerCase()}`;
    // Entries about a card show it, as the desktop log does
    if (entry.imageKey && setting('logImages')) {
      const thumb = document.createElement('img');
      thumb.className = 'thumb';
      thumb.alt = '';
      thumb.src = imageUrl(entry.imageKey);
      thumb.dataset.key = String(entry.card);
      thumb.dataset.zoom = thumb.src;
      thumb.addEventListener('error', () => thumb.remove());
      // A thumbnail arriving after the entry makes the log taller, which would leave the newest line off screen
      thumb.addEventListener('load', () => toBottom(root));
      hoverable(thumb);
      el.append(thumb);
    }
    const text = document.createElement('span');
    setSymbolText(text, entry.message);
    el.append(text);
    root.append(el);
  }
  while (root.childElementCount > MAX_ENTRIES) root.firstChild?.remove();
  toBottom(root);
}

function toBottom(root: HTMLElement): void {
  if (stick) {
    root.scrollTop = root.scrollHeight;
  }
}
