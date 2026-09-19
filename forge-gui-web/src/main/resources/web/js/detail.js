import { imageUrl } from './cards.js';

// Zoomed image and rules text of the hovered card. The host composes the text (CardDetailUtil, as on desktop).
let send = () => {};
let hovered = null;
let faceIndex = 0;
const details = new Map();

export function initDetail(sendFn) {
  send = sendFn;
  document.addEventListener('keydown', e => {
    if (e.key.toLowerCase() !== 'f' || e.target instanceof HTMLInputElement || !hovered) return;
    const count = details.get(hovered.key)?.faces.length ?? 0;
    if (count > 1) {
      faceIndex = (faceIndex + 1) % count;
      draw();
    }
  });
}

// el carries data-key (the card) and data-zoom (its image, empty when the viewer may not see it)
export function hoverCard(el) {
  faceIndex = 0;
  if (!el || !el.dataset.zoom) {
    hovered = null;
    draw();
    return;
  }
  const key = Number(el.dataset.key);
  hovered = { key: Number.isInteger(key) ? key : null, src: el.dataset.zoom };
  if (hovered.key !== null) send({ t: 'detail', key: hovered.key });
  draw();
}

// Hovering an avatar shows desktop's player details (life, counters, hand size, commander damage and tax)
const playerDetails = new Map();

export function hoverPlayer(key) {
  faceIndex = 0;
  hovered = key === null ? null : { player: key };
  if (key !== null) send({ t: 'playerDetail', key });
  draw();
}

export function onPlayerDetail(msg) {
  playerDetails.set(msg.key, msg);
  if (hovered?.player === msg.key) draw();
}

export function onDetail(msg) {
  details.set(msg.key, msg);
  if (hovered?.key === msg.key) draw();
}

function draw() {
  const zoom = document.getElementById('zoom');
  zoom.hidden = !hovered;
  if (!hovered) return;
  if (hovered.player !== undefined) {
    drawPlayer(zoom, playerDetails.get(hovered.player));
    return;
  }
  const d = hovered.key !== null ? details.get(hovered.key) : null;
  const face = d?.faces[faceIndex] ?? d?.faces[0];
  ensureZoom(zoom);
  const img = zoom.querySelector('img');
  img.hidden = false;
  const src = face?.imageKey ? imageUrl(face.imageKey) : hovered.src;
  if (img.getAttribute('src') !== src) {
    img.hidden = false;
    img.src = src;
  }
  zoom.querySelector('.detail').hidden = !face;
  if (!face) return;
  zoom.querySelector('.name').textContent = face.name ?? '';
  zoom.querySelector('.cost').textContent = face.cost ?? '';
  zoom.querySelector('.type').textContent = face.type ?? '';
  setRulesText(zoom.querySelector('.text'), face.text ?? '');
  zoom.querySelector('.pt').textContent = face.pt ?? '';
  zoom.querySelector('.hint').textContent = d.faces.length > 1 ? `F: next face (${faceIndex + 1}/${d.faces.length})` : '';
}

// CardDetailUtil marks text that does not currently apply with a grey span. Only that survives; every other
// tag is dropped and its text kept, so card text can never inject markup.
function setRulesText(el, html) {
  el.replaceChildren();
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const walk = (node, muted) => {
    for (const child of node.childNodes) {
      if (child.nodeType === Node.TEXT_NODE) {
        const span = document.createElement('span');
        if (muted) span.className = 'muted';
        span.textContent = child.textContent;
        el.append(span);
      } else if (child.nodeName === 'BR') {
        el.append('\n');
      } else if (child.nodeType === Node.ELEMENT_NODE) {
        walk(child, muted || /gray|grey/i.test(child.getAttribute('style') ?? ''));
      }
    }
  };
  walk(doc.body, false);
}

function ensureZoom(zoom) {
  if (zoom.firstChild) return;
  zoom.innerHTML = '<img alt=""><div class="detail"><header><b class="name"></b><span class="cost"></span></header><div class="type"></div><div class="text"></div><div class="pt"></div><div class="hint"></div></div>';
  zoom.querySelector('img').addEventListener('error', e => { e.target.hidden = true; });
}

function drawPlayer(zoom, d) {
  ensureZoom(zoom);
  zoom.querySelector('img').hidden = true;
  zoom.querySelector('.detail').hidden = !d;
  if (!d) return;
  zoom.querySelector('.name').textContent = d.name ?? '';
  zoom.querySelector('.cost').textContent = '';
  zoom.querySelector('.type').textContent = '';
  zoom.querySelector('.text').textContent = d.lines.join('\n');
  zoom.querySelector('.pt').textContent = '';
  zoom.querySelector('.hint').textContent = '';
}
