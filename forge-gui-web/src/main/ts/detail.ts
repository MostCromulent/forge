import { appendSymbolText, cardImageSrc, hideOnError, imageUrl, setImage, setSymbolText } from './images';
import { COMMANDER_LETHAL, COMMANDER_WARNING } from './board';
import { byId, q } from './dom';
import { changeUi, ui } from './ui';
import type { Actions } from './actions';
import { deref, stateOf, type Model } from './model';
import type { CardView, PlayerDetail, PlayerView } from './protocol';

// Zoomed image and rules text of the hovered card. The host composes the text (CardDetailUtil, as on desktop), and
// it arrives in the model a moment after the pointer does

let actions: Actions | null = null;

export function initDetail(actionsFor: Actions): void {
  actions = actionsFor;
  // A card taken off the page under the pointer never reports the pointer leaving it, so its preview would stay
  document.addEventListener('pointerover', () => {
    if (hoverGone()) hoverCard(null);
  });
}

const hoverGone = () => {
  const hover = ui.hover;
  return !!hover && 'card' in hover && !!hover.at && !hover.at.isConnected;
};

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
    byId('zoom').classList.remove('settling');
    changeUi(u => { u.hover = null; u.faceIndex = 0; });
    return;
  }
  const key = Number(el.dataset.key);
  const card = Number.isInteger(key) ? key : null;
  const src = el.dataset.zoom;
  const from = el.dataset.from;
  changeUi(u => { u.hover = { card, src, from, at: el }; u.faceIndex = 0; });
  if (card !== null) actions?.inspectCard(card);
  // A card in hand grows as it rises, and a preview placed beside it midway would jump, so it waits unseen
  const zoom = byId('zoom');
  const rising = (el.closest<HTMLElement>('.card') ?? el).getAnimations().filter(a => a instanceof CSSTransition);
  zoom.classList.toggle('settling', rising.length > 0);
  if (rising.length) {
    Promise.allSettled(rising.map(a => a.finished)).then(() => {
      const hover = ui.hover;
      if (!hover || !('card' in hover) || hover.at !== el) return;
      zoom.classList.remove('settling');
      placeZoom(zoom, el);
    });
  }
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
  if (hoverGone()) {
    ui.hover = null;
    byId('zoom').classList.remove('settling');
  }
  drawDetail(model);
  const hover = ui.hover;
  const zoom = byId('zoom');
  if (hover && 'card' in hover && hover.at) {
    placeZoom(zoom, hover.at);
  } else {
    zoom.classList.remove('placed');
    zoom.style.left = zoom.style.top = '';
  }
}

/** The gap kept between a card and its preview, and between the preview and the edges it must stay inside. */
const ZOOM_GAP = 16;

/**
 * Puts the preview beside the card it shows, never over it: to its right where there is room, else to its left, else
 * on whichever side has more room. It stays inside the board, clear of the side column.
 */
function placeZoom(zoom: HTMLElement, at: HTMLElement): void {
  if (zoom.hidden || !at.isConnected) return;
  const card = at.getBoundingClientRect();
  const side = document.getElementById('side')?.getBoundingClientRect();
  const left = 8;
  const right = side && side.width < window.innerWidth / 2 && side.left > card.right ? side.left - 8 : window.innerWidth - 8;
  const w = zoom.offsetWidth, h = zoom.offsetHeight;
  const after = card.right + ZOOM_GAP, before = card.left - ZOOM_GAP - w;
  let x: number;
  if (after + w <= right) x = after;
  else if (before >= left) x = before;
  else x = right - card.right > card.left - left ? right - w : left;
  const y = Math.min(Math.max(8, card.top + card.height / 2 - h / 2), window.innerHeight - h - 8);
  zoom.classList.add('placed');
  zoom.style.left = `${Math.round(x)}px`;
  zoom.style.top = `${Math.round(y)}px`;
}

function drawDetail(model: Model): void {
  const zoom = byId('zoom');
  const hover = ui.hover;
  zoom.hidden = !hover;
  if (!hover) return;
  ensureZoom(zoom);
  q(zoom, '.cmdr-taken').hidden = true;
  if ('player' in hover) {
    drawPlayer(zoom, model.playerDetails.get(hover.player));
    drawCommanderDamage(q(zoom, '.cmdr-taken'), model, hover.player);
    return;
  }
  const d = hover.card !== null ? model.cardDetails.get(hover.card) : undefined;
  const face = d?.faces[ui.faceIndex] ?? d?.faces[0];
  ensureZoom(zoom);
  const img = q<HTMLImageElement>(zoom, 'img');
  const src = face?.imageKey ? imageUrl(face.imageKey) : hover.src;
  // A card the viewer may not see has no image, so its text is all there is to show
  const text = ui.cardText || !src;
  zoom.classList.toggle('image-only', !text);
  zoom.classList.toggle('text-card', text);
  img.hidden = text;
  if (!text) setImage(img, src);
  q(zoom, '.detail').hidden = !face;
  setSource(q(zoom, '.from'), hover.from);
  if (!d || !face) return;
  q(zoom, '.name').textContent = face.name ?? '';
  setSymbolText(q(zoom, '.cost'), face.cost);
  q(zoom, '.type').textContent = face.type ?? '';
  setRulesText(q(zoom, '.text'), face.text ?? '');
  q(zoom, '.pt').textContent = face.pt ?? '';
  const faces = d.faces.length > 1 ? `F: next face (${ui.faceIndex + 1}/${d.faces.length})` : '';
  q(zoom, '.hint').textContent = [faces, src ? `T: ${text ? 'card image' : 'rules text'}` : ''].filter(Boolean).join(' · ');
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
  // The preview is redrawn every frame while a card is hovered, so the text is parsed again only when it changes
  if (el.dataset.html === html) return;
  el.dataset.html = html;
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
  zoom.innerHTML = '<img alt=""><div class="detail"><header><b class="name"></b><span class="cost"></span></header><div class="cmdr-taken" hidden></div><div class="type"></div><div class="from"></div><div class="text"></div><div class="pt"></div><div class="hint"></div></div>';
  hideOnError(q<HTMLImageElement>(zoom, 'img'));
}

/**
 * The commander damage a player has taken, one row per commander that has dealt any, most first: its art, its name,
 * a bar to 21 and the number. The server leaves desktop's own lines for it out of the text below.
 */
function drawCommanderDamage(root: HTMLElement, model: Model, key: number): void {
  const player = model.objects.get(key) as PlayerView | undefined;
  const hits = (player?.CommanderDamage ?? []).filter(h => h.value > 0).sort((a, b) => b.value - a.value);
  root.hidden = hits.length === 0;
  root.replaceChildren();
  if (!hits.length) return;
  const title = document.createElement('h6');
  title.textContent = 'Commander damage taken';
  root.append(title);
  for (const { card, value } of hits) {
    const commander = deref(model, card) as CardView | undefined;
    const row = document.createElement('div');
    row.className = value >= COMMANDER_WARNING ? 'cmdr-row near' : 'cmdr-row';
    row.innerHTML = '<span class="art"></span><span class="who"><span class="nm"></span><span class="bar"><i></i></span></span><span class="v"><b></b><small></small></span>';
    const src = cardImageSrc(model, commander);
    if (src) q(row, '.art').style.backgroundImage = `url("${src}")`;
    q(row, '.nm').textContent = (commander ? stateOf(model, commander).Name : undefined) ?? 'Commander';
    q(row, '.bar i').style.width = `${Math.min(value, COMMANDER_LETHAL) / COMMANDER_LETHAL * 100}%`;
    q(row, '.v b').textContent = String(value);
    q(row, '.v small').textContent = ` / ${COMMANDER_LETHAL}`;
    root.append(row);
  }
}

function drawPlayer(zoom: HTMLElement, d: PlayerDetail | undefined): void {
  ensureZoom(zoom);
  zoom.classList.remove('image-only', 'text-card');
  q(zoom, 'img').hidden = true;
  q(zoom, '.detail').hidden = !d;
  setSource(q(zoom, '.from'), undefined);
  if (!d) return;
  q(zoom, '.name').textContent = d.name ?? '';
  q(zoom, '.cost').textContent = '';
  q(zoom, '.type').textContent = '';
  const text = q(zoom, '.text');
  text.textContent = d.lines.join('\n');
  delete text.dataset.html;
  q(zoom, '.pt').textContent = '';
  q(zoom, '.hint').textContent = '';
}
