import { appendSymbolText, hideOnError, imageUrl, setImage, setSymbolText } from './images';
import { byId, q } from './dom';
import { changeUi, ui } from './ui';
import type { Actions } from './actions';
import type { Model } from './model';
import type { PlayerDetail } from './protocol';

// Zoomed image and rules text of the hovered card. The host composes the text (CardDetailUtil, as on desktop), and
// it arrives in the model a moment after the pointer does

let actions: Actions | null = null;

export function initDetail(actionsFor: Actions): void {
  actions = actionsFor;
}

/** The F key turns the hovered card to its next face. Needs the model, which knows how many faces it has. */
export function nextFace(model: Model): void {
  const hover = ui.hover;
  const count = hover && 'card' in hover && hover.card !== null ? model.cardDetails.get(hover.card)?.faces.length ?? 0 : 0;
  if (count > 1) {
    changeUi(u => { u.faceIndex = (u.faceIndex + 1) % count; });
  }
}

// el carries data-key (the card), data-zoom (its image, empty when the viewer may not see it) and, for a card in
// hand that is really somewhere else, data-from (the zone it is in)
export function hoverCard(el: HTMLElement | null): void {
  if (!el || !el.dataset.zoom) {
    changeUi(u => { u.hover = null; u.faceIndex = 0; });
    return;
  }
  const key = Number(el.dataset.key);
  const card = Number.isInteger(key) ? key : null;
  const src = el.dataset.zoom;
  const from = el.dataset.from;
  changeUi(u => { u.hover = { card, src, from }; u.faceIndex = 0; });
  if (card !== null) actions?.inspectCard(card);
}

export function hoverable(el: HTMLElement, target: HTMLElement = el): void {
  el.addEventListener('mouseenter', () => hoverCard(target));
  el.addEventListener('mouseleave', () => hoverCard(null));
}

// Hovering an avatar shows desktop's player details (life, counters, hand size, commander damage and tax)
export function hoverPlayer(key: number | null): void {
  changeUi(u => { u.hover = key === null ? null : { player: key }; u.faceIndex = 0; });
  if (key !== null) actions?.inspectPlayer(key);
}

export function renderDetail(model: Model): void {
  const zoom = byId('zoom');
  const hover = ui.hover;
  zoom.hidden = !hover;
  if (!hover) return;
  if ('player' in hover) {
    drawPlayer(zoom, model.playerDetails.get(hover.player));
    return;
  }
  const d = hover.card !== null ? model.cardDetails.get(hover.card) : undefined;
  const face = d?.faces[ui.faceIndex] ?? d?.faces[0];
  ensureZoom(zoom);
  const img = q<HTMLImageElement>(zoom, 'img');
  img.hidden = false;
  setImage(img, face?.imageKey ? imageUrl(face.imageKey) : hover.src);
  q(zoom, '.detail').hidden = !face;
  setSource(q(zoom, '.from'), hover.from);
  if (!d || !face) return;
  q(zoom, '.name').textContent = face.name ?? '';
  setSymbolText(q(zoom, '.cost'), face.cost);
  q(zoom, '.type').textContent = face.type ?? '';
  setRulesText(q(zoom, '.text'), face.text ?? '');
  q(zoom, '.pt').textContent = face.pt ?? '';
  q(zoom, '.hint').textContent = d.faces.length > 1 ? `F: next face (${ui.faceIndex + 1}/${d.faces.length})` : '';
}

// "your graveyard" rather than "your exile": the zones a card is played out of do not all take a possessive
const ZONE_PHRASE: Record<string, string> = {
  Graveyard: 'your graveyard',
  Exile: 'exile',
  Command: 'your command zone',
  Library: 'your library',
  Sideboard: 'your sideboard',
};

/** The line that says a card in hand is not really in hand. The glow around it is in this same zone's colour. */
function setSource(el: HTMLElement, zone: string | undefined): void {
  el.textContent = zone ? `Playable from ${ZONE_PHRASE[zone] ?? zone.toLowerCase()}` : '';
  if (zone) {
    el.dataset.from = zone;
  } else {
    delete el.dataset.from;
  }
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
  zoom.innerHTML = '<img alt=""><div class="detail"><header><b class="name"></b><span class="cost"></span></header><div class="type"></div><div class="from"></div><div class="text"></div><div class="pt"></div><div class="hint"></div></div>';
  hideOnError(q<HTMLImageElement>(zoom, 'img'));
}

function drawPlayer(zoom: HTMLElement, d: PlayerDetail | undefined): void {
  ensureZoom(zoom);
  q(zoom, 'img').hidden = true;
  q(zoom, '.detail').hidden = !d;
  setSource(q(zoom, '.from'), undefined);
  if (!d) return;
  q(zoom, '.name').textContent = d.name ?? '';
  q(zoom, '.cost').textContent = '';
  q(zoom, '.type').textContent = '';
  q(zoom, '.text').textContent = d.lines.join('\n');
  q(zoom, '.pt').textContent = '';
  q(zoom, '.hint').textContent = '';
}
