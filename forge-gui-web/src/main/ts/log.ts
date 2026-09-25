// The game log panel: each entry as it arrives, with player names tinted, kept scrolled to the newest unless the
// player has scrolled back.

import { imageUrl, setSymbolText } from './images';
import { hoverable } from './detail';
import { byId } from './dom';
import type { LogMessage } from './protocol';

const MAX_ENTRIES = 400;

// The log follows the game unless the player scrolls back through it
let stick = true;

export function initLog(): void {
  const log = byId('log');
  // Only the player scrolling decides it: a thumbnail loading, or the browser keeping the view steady as entries
  // above are trimmed, also moves the log, and once read as scrolling back it stopped following for good
  let handled = 0;
  const byHand = () => { handled = Date.now(); };
  for (const type of ['wheel', 'touchmove', 'pointerdown', 'keydown']) log.addEventListener(type, byHand, { passive: true });
  log.addEventListener('scroll', () => {
    if (Date.now() - handled < 1000) stick = log.scrollHeight - log.scrollTop - log.clientHeight < 24;
  });
}

// Told apart by lightness as well as hue, so they hold for red-green colour blindness
const PLAYER_TINTS = ['#8fc3ff', '#f0a35e', '#c3a8ff', '#5fd0c0'];

/**
 * Each player's name in a colour of its own, in games of three or more where a line is otherwise hard to place;
 * you keep the brass. Longer names first, so "Forge AI 2" is never read as "Forge AI".
 */
function tintNames(el: HTMLElement, names: readonly { name: string; colour: string }[]): void {
  if (!names.length) return;
  const byName = new Map(names.map(n => [n.name, n.colour]));
  const pattern = new RegExp(names.map(n => n.name).sort((a, b) => b.length - a.length)
    .map(n => n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'g');
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  const texts: Text[] = [];
  while (walker.nextNode()) texts.push(walker.currentNode as Text);
  for (const text of texts) {
    const value = text.data;
    pattern.lastIndex = 0;
    if (!pattern.test(value)) continue;
    pattern.lastIndex = 0;
    const parts: Node[] = [];
    let at = 0;
    for (const match of value.matchAll(pattern)) {
      parts.push(document.createTextNode(value.slice(at, match.index)));
      const who = document.createElement('b');
      who.className = 'log-player';
      who.style.color = byName.get(match[0]) ?? '';
      who.textContent = match[0];
      parts.push(who);
      at = (match.index ?? 0) + match[0].length;
    }
    parts.push(document.createTextNode(value.slice(at)));
    text.replaceWith(...parts);
  }
}

/** The colour each player's name takes in the log: none in a two-player game. */
export function logTints(players: readonly { name: string; local: boolean }[]): { name: string; colour: string }[] {
  if (players.length <= 2) return [];
  let next = 0;
  return players.filter(p => p.name).map(p => ({ name: p.name, colour: p.local ? 'var(--accent-2)' : PLAYER_TINTS[next++ % PLAYER_TINTS.length] }));
}

// The server replays the whole log on connect (full) and sends new entries after that
export function appendLog(msg: LogMessage, tints: readonly { name: string; colour: string }[] = []): void {
  const root = byId('log');
  if (msg.full) {
    root.replaceChildren();
    stick = true;
  }
  for (const entry of msg.entries) {
    const el = document.createElement('div');
    el.className = `log-entry ${entry.type.toLowerCase()}`;
    // Entries about a card show it, as the desktop log does
    if (entry.imageKey) {
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
    tintNames(text, tints);
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
