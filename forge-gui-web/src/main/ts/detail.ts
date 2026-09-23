import { appendSymbolText, hideOnError, imageUrl, setImage, setSymbolText } from './images';
import { byId, q } from './dom';
import type { Detail, PlayerDetail, Send } from './protocol';

// Zoomed image and rules text of the hovered card. The host composes the text (CardDetailUtil, as on desktop)
type Hovered = { key: number | null; src: string; player?: undefined } | { player: number };

let send: Send = () => {};
let hovered: Hovered | null = null;
let faceIndex = 0;
const details = new Map<number, Detail>();

export function resetDetail(): void {
  details.clear();
  playerDetails.clear();
  hovered = null;
  draw();
}

export function initDetail(sendFn: Send): void {
  send = sendFn;
  document.addEventListener('keydown', e => {
    if (e.key.toLowerCase() !== 'f' || e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement || !hovered) return;
    const count = 'key' in hovered && hovered.key !== null ? details.get(hovered.key)?.faces.length ?? 0 : 0;
    if (count > 1) {
      faceIndex = (faceIndex + 1) % count;
      draw();
    }
  });
}

// el carries data-key (the card) and data-zoom (its image, empty when the viewer may not see it)
export function hoverCard(el: HTMLElement | null): void {
  faceIndex = 0;
  if (!el || !el.dataset.zoom) {
    hovered = null;
    draw();
    return;
  }
  const key = Number(el.dataset.key);
  const card = { key: Number.isInteger(key) ? key : null, src: el.dataset.zoom };
  hovered = card;
  if (card.key !== null) send({ t: 'detail', key: card.key });
  draw();
}

export function hoverable(el: HTMLElement, target: HTMLElement = el): void {
  el.addEventListener('mouseenter', () => hoverCard(target));
  el.addEventListener('mouseleave', () => hoverCard(null));
}

// Hovering an avatar shows desktop's player details (life, counters, hand size, commander damage and tax)
const playerDetails = new Map<number, PlayerDetail>();

export function hoverPlayer(key: number | null): void {
  faceIndex = 0;
  hovered = key === null ? null : { player: key };
  if (key !== null) send({ t: 'playerDetail', key });
  draw();
}

export function onPlayerDetail(msg: PlayerDetail): void {
  playerDetails.set(msg.key, msg);
  if (hovered?.player === msg.key) draw();
}

export function onDetail(msg: Detail): void {
  details.set(msg.key, msg);
  if (hovered && 'key' in hovered && hovered.key === msg.key) draw();
}

function draw(): void {
  const zoom = byId('zoom');
  zoom.hidden = !hovered;
  if (!hovered) return;
  if (hovered.player !== undefined) {
    drawPlayer(zoom, playerDetails.get(hovered.player));
    return;
  }
  const d = hovered.key !== null ? details.get(hovered.key) : undefined;
  const face = d?.faces[faceIndex] ?? d?.faces[0];
  ensureZoom(zoom);
  const img = q<HTMLImageElement>(zoom, 'img');
  img.hidden = false;
  setImage(img, face?.imageKey ? imageUrl(face.imageKey) : hovered.src);
  q(zoom, '.detail').hidden = !face;
  if (!d || !face) return;
  q(zoom, '.name').textContent = face.name ?? '';
  setSymbolText(q(zoom, '.cost'), face.cost);
  q(zoom, '.type').textContent = face.type ?? '';
  setRulesText(q(zoom, '.text'), face.text ?? '');
  q(zoom, '.pt').textContent = face.pt ?? '';
  q(zoom, '.hint').textContent = d.faces.length > 1 ? `F: next face (${faceIndex + 1}/${d.faces.length})` : '';
}

// CardDetailUtil marks text that does not currently apply with a grey span. Only that survives; every other
// tag is dropped and its text kept, so card text can never inject markup.
function setRulesText(el: HTMLElement, html: string): void {
  el.replaceChildren();
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const walk = (node: Node, muted: boolean) => {
    for (const child of node.childNodes) {
      if (child.nodeType === Node.TEXT_NODE) {
        appendSymbolText(el, child.textContent ?? '', muted ? 'muted' : '');
      } else if (child.nodeName === 'BR') {
        el.append('\n');
      } else if (child instanceof Element) {
        walk(child, muted || /gray|grey/i.test(child.getAttribute('style') ?? ''));
      }
    }
  };
  walk(doc.body, false);
}

function ensureZoom(zoom: HTMLElement): void {
  if (zoom.firstChild) return;
  zoom.innerHTML = '<img alt=""><div class="detail"><header><b class="name"></b><span class="cost"></span></header><div class="type"></div><div class="text"></div><div class="pt"></div><div class="hint"></div></div>';
  hideOnError(q<HTMLImageElement>(zoom, 'img'));
}

function drawPlayer(zoom: HTMLElement, d: PlayerDetail | undefined): void {
  ensureZoom(zoom);
  q(zoom, 'img').hidden = true;
  q(zoom, '.detail').hidden = !d;
  if (!d) return;
  q(zoom, '.name').textContent = d.name ?? '';
  q(zoom, '.cost').textContent = '';
  q(zoom, '.type').textContent = '';
  q(zoom, '.text').textContent = d.lines.join('\n');
  q(zoom, '.pt').textContent = '';
  q(zoom, '.hint').textContent = '';
}
