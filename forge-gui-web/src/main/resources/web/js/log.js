import { imageUrl } from './cards.js';
import { hoverCard } from './detail.js';

const MAX_ENTRIES = 400;

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
