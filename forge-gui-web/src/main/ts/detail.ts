import { appendSymbolText, cardImageSrc, hideOnError, imageUrl, setImage, setSymbolText } from './images';
import { COMMANDER_LETHAL, COMMANDER_WARNING } from './board';
import { frameColour } from './cards';
import { changesOf, plain, sortRulesText, type Block, type Changes, type Line, type SortedText } from './rulestext';
import { byId, q } from './dom';
import { changeUi, ui } from './ui';
import { keyName } from './keys';
import { boundKeys } from './settings';
import type { Actions } from './actions';
import { deref, stateOf, type Model } from './model';
import { isAiming } from './overlay';
import type { CardFace, CardView, KeywordText, PlayerDetail, PlayerView } from './protocol';
import { t, type TextKey } from './text';

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

/** How long the pointer rests on a card or a player before a preview opens; one already open follows at once. */
const HOVER_DELAY_MS = 300;
let opening: ReturnType<typeof setTimeout> | undefined;

/** Shows a preview after the pointer has rested, or at once when one is showing, so passing over the board is quiet. */
function afterRest(show: () => void): void {
  clearTimeout(opening);
  if (ui.hover) show();
  else opening = setTimeout(show, HOVER_DELAY_MS);
}

// el carries data-key (the card), data-zoom (its image, empty when the viewer may not see it) and, for a card in
// hand that is really somewhere else, data-from (the zone it is in)
export function hoverCard(el: HTMLElement | null): void {
  if (!el || !el.dataset.zoom) {
    clearTimeout(opening);
    byId('zoom').classList.remove('settling');
    changeUi(u => { u.hover = null; u.faceIndex = 0; });
    return;
  }
  afterRest(() => {
    if (el.isConnected) showCard(el);
  });
}

function showCard(el: HTMLElement): void {
  const key = Number(el.dataset.key);
  const card = Number.isInteger(key) ? key : null;
  const src = el.dataset.zoom ?? '';
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

/** Where the pointer is over an avatar; the player's details are put beside it, and follow it. */
let pointer = { x: 0, y: 0 };

export function followPointer(e: MouseEvent): void {
  pointer = { x: e.clientX, y: e.clientY };
  const hover = ui.hover;
  if (hover && 'player' in hover) placeAtPointer(byId('zoom'));
}

// Hovering an avatar shows desktop's player details (life, counters, hand size, commander damage and tax)
export function hoverPlayer(key: number | null): void {
  clearTimeout(opening);
  if (key === null) {
    changeUi(u => { u.hover = null; u.faceIndex = 0; });
    return;
  }
  afterRest(() => {
    // A player's details follow the pointer, so while a target is chosen they would cover the arrow landing on them
    if (isAiming()) return;
    changeUi(u => { u.hover = { player: key }; u.faceIndex = 0; });
    actions?.inspectPlayer(key);
  });
}

export function renderDetail(model: Model): void {
  if (hoverGone() || (ui.hover && 'player' in ui.hover && isAiming())) {
    ui.hover = null;
    byId('zoom').classList.remove('settling');
  }
  drawDetail(model);
  const hover = ui.hover;
  const zoom = byId('zoom');
  if (hover && 'card' in hover && hover.at) {
    placeZoom(zoom, hover.at);
  } else if (hover && 'player' in hover) {
    placeAtPointer(zoom);
  } else {
    zoom.classList.remove('placed');
    zoom.style.left = zoom.style.top = '';
  }
}

/** The gap kept between a card and its preview, and between the preview and the edges it must stay inside. */
const ZOOM_GAP = 16;

/** Below and to the right of the pointer, turned back inside the window near its edges. */
function placeAtPointer(zoom: HTMLElement): void {
  if (zoom.hidden) return;
  const w = zoom.offsetWidth, h = zoom.offsetHeight;
  const x = pointer.x + ZOOM_GAP + w > window.innerWidth - 8 ? pointer.x - ZOOM_GAP - w : pointer.x + ZOOM_GAP;
  const y = Math.min(pointer.y + ZOOM_GAP, window.innerHeight - h - 8);
  zoom.classList.add('placed');
  zoom.style.left = `${Math.round(Math.max(8, x))}px`;
  zoom.style.top = `${Math.round(Math.max(8, y))}px`;
}

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

/** The hovered card and the side the preview last showed it on, so turning the same card over can be seen. */
let shown: { hover: object; text: boolean } | null = null;

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
  // Turned to its other side, the same card plays a flip; moving to another card does not
  if (shown?.hover === hover && shown.text !== text) {
    zoom.classList.remove('flipping');
    void zoom.offsetWidth;
    zoom.classList.add('flipping');
  }
  shown = { hover, text };
  zoom.classList.toggle('image-only', !text);
  zoom.classList.toggle('text-card', text);
  img.hidden = text;
  if (!text) setImage(img, src);
  q(zoom, '.detail').hidden = !face;
  setSource(q(zoom, '.from'), hover.from);
  setSource(q(zoom, '.from-tab'), hover.from);
  q(zoom, '.hint').textContent = '';
  if (!d || !face) return;
  q(zoom, '.name').textContent = face.name ?? '';
  setSymbolText(q(zoom, '.cost'), face.cost);
  const detail = q(zoom, '.detail');
  // The frame is in the face's own colours, as a card with no picture is framed on the board
  detail.dataset.frame = frameColour(face.colors, face.type ?? '');
  q(zoom, '.type-line').textContent = face.type ?? '';
  const set = q(zoom, '.set');
  set.textContent = face.set ?? '';
  set.dataset.rarity = face.rarity ?? '';
  const card = face === d.faces[0] && hover.card !== null ? model.objects.get(hover.card) as CardView | undefined : undefined;
  const state = card ? stateOf(model, card) : undefined;
  // A token or a face-down card has no printed text of its own to differ from
  const changes = card && state && !card.Token && !card.Facedown
    ? changesOf(state.OracleText ?? '', state.AbilityText ?? '', { ...card.ChangedTypes, ...card.ChangedColorWords }, state.Keywords ?? [])
    : undefined;
  const sorted = setRulesText(q(zoom, '.text'), q(zoom, '.foot'), face.text ?? '', state?.Keywords ?? [], changes);
  q(zoom, '.kind-chip').textContent = sorted.tag ?? '';
  drawPt(q(zoom, '.pt'), model, hover.card, face, face === d.faces[0]);
  // The keys are the player's own, since either can be changed in the options
  const keys = boundKeys();
  const faces = d.faces.length > 1 ? t('lblWebDetailNextFace', keyName(keys.nextFace), ui.faceIndex + 1, d.faces.length) : '';
  const flip = src ? t(text ? 'lblWebDetailFlipToImage' : 'lblWebDetailFlipToRules', keyName(keys.cardText)) : '';
  q(zoom, '.hint').textContent = [faces, flip].filter(Boolean).join(' · ');
}

// "your graveyard" rather than "your exile": the zones a card is played out of do not all take a possessive
const PLAYABLE_FROM: Record<string, TextKey> = {
  Graveyard: 'lblWebDetailPlayableFromGraveyard',
  Exile: 'lblWebDetailPlayableFromExile',
  Command: 'lblWebDetailPlayableFromCommand',
  Library: 'lblWebDetailPlayableFromLibrary',
  Sideboard: 'lblWebDetailPlayableFromSideboard',
};

/** Says a card in hand is not really in hand: a tab on the image's foot, or a line in the text. The glow around the card
 *  is in this same zone's colour. */
function setSource(el: HTMLElement, zone: string | undefined): void {
  const known = zone ? PLAYABLE_FROM[zone] : undefined;
  el.textContent = known ? t(known) : zone ? t('lblWebDetailPlayableFrom', zone.toLowerCase()) : '';
  if (zone) {
    el.dataset.from = zone;
  } else {
    delete el.dataset.from;
  }
}

// CardDetailUtil marks text that does not currently apply with a grey span, and a word a text-changing effect replaced
// with a strike. Only those survive; every other tag is dropped and its text kept, so card text can never inject markup.
function rulesLines(html: string): Line[] {
  const lines: Line[] = [[]];
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const walk = (node: Node, muted: boolean, struck: boolean) => {
    for (const child of node.childNodes) {
      if (child.nodeType === Node.TEXT_NODE) {
        (child.textContent ?? '').split(/\r?\n/).forEach((text, i) => {
          if (i > 0) lines.push([]);
          if (text) lines[lines.length - 1].push(struck ? { text, muted, struck } : { text, muted });
        });
      } else if (child.nodeName === 'BR') {
        lines.push([]);
      } else if (child instanceof Element) {
        walk(child, muted || /gray|grey/i.test(child.getAttribute('style') ?? ''), struck || /^(STRIKE|S|DEL)$/.test(child.nodeName));
      }
    }
  };
  walk(doc.body, false, false);
  return lines;
}

/** The text last sorted, for the next frame to reuse. */
let lastSorted: SortedText = { blocks: [], chips: [], notes: [] };

/**
 * Lays out a card's rules as its printed text box: keywords as chips, each ability on a row of its own with its cost
 * beside it, then the rest. What the game has done to the card goes under the box: counters and damage as chips by the
 * P/T, anything longer as notes at the foot of the box.
 */
function setRulesText(box: HTMLElement, foot: HTMLElement, html: string, keywords: readonly KeywordText[], changes?: Changes): SortedText {
  // The preview is redrawn every frame while a card is hovered, so the text is sorted again only when it changes
  const drawn = html + JSON.stringify(keywords) + JSON.stringify(changes, (_, v) => (v instanceof Set ? [...v] : v));
  if (box.dataset.html === drawn) return lastSorted;
  box.dataset.html = drawn;
  const sorted = lastSorted = sortRulesText(rulesLines(html), keywords);
  box.replaceChildren(...sorted.blocks.map(b => drawBlock(b, changes)));
  // What the card has lost goes with its rules, struck through: the keywords with its keywords, the rest after
  if (changes?.lostKeywords.length) {
    const kws = box.querySelector('.kws') ?? box.insertBefore(element('div', 'kws'), box.firstChild);
    const firstReminder = kws.querySelector('.kwrem');
    for (const lost of changes.lostKeywords) kws.insertBefore(changed(richLine(element('span', 'kw lost'), [{ text: lost, muted: false }]), 'lost'), firstReminder);
  }
  for (const lost of changes?.lostText ?? []) box.append(changed(richLine(element('div', 'lost'), [{ text: lost, muted: false }]), 'lost'));
  if (sorted.notes.length) {
    const notes = document.createElement('div');
    notes.className = 'notes';
    for (const note of sorted.notes) notes.append(richLine(document.createElement('div'), note));
    box.append(notes);
  }
  foot.replaceChildren(...sorted.chips.map(chip => {
    const el = document.createElement('span');
    el.className = `chip ${chip.kind}`;
    el.textContent = chip.text;
    return el;
  }));
  return sorted;
}

function element(tag: string, className: string): HTMLElement {
  const el = document.createElement(tag);
  el.className = className;
  return el;
}

/** Labels a keyword or a line as gained or lost. */
function changed(el: HTMLElement, how: 'gained' | 'lost'): HTMLElement {
  const tag = element('i', 'change');
  tag.textContent = how === 'gained' ? t('lblWebDetailGained') : t('lblWebDetailLost');
  el.append(tag);
  return el;
}

function drawBlock(block: Block, changes?: Changes): HTMLElement {
  switch (block.kind) {
    case 'keywords': {
      const el = element('div', 'kws');
      for (const item of block.items) {
        const kw = richLine(element('span', 'kw'), item);
        el.append(changes?.gained.has(plain(item).toLowerCase()) ? changed(kw, 'gained') : kw);
      }
      for (const reminder of block.reminders) el.append(richLine(element('span', 'kwrem'), reminder));
      return el;
    }
    case 'ability': {
      const el = element('div', 'ab');
      el.append(richLine(element('span', block.loyalty ? `ac ${block.loyalty}` : 'ac'), block.cost));
      const effect = element('span', 'ef');
      if (block.label) effect.append(richLine(element('b', ''), block.label), ' ');
      el.append(richLine(effect, block.effect));
      return el;
    }
    case 'mode':
      return richLine(element('div', 'mode-option'), block.text);
    case 'text': {
      const el = element('div', '');
      if (block.label) el.append(richLine(element('b', ''), block.label), ' — ');
      return richLine(el, block.text);
    }
  }
}

/** Appends a line's text with its symbols drawn, greying what does not apply and setting reminder text apart. */
function richLine<E extends HTMLElement>(el: E, line: Line): E {
  for (const run of line) {
    run.text.split(/(\((?:[^()]|\([^()]*\))*\))/).forEach((part, i) => {
      if (!part) return;
      const span = document.createElement('span');
      span.className = [run.muted ? 'muted' : '', run.struck ? 'struck' : '', i % 2 ? 'rem' : ''].filter(Boolean).join(' ');
      appendSymbolText(span, part);
      el.append(span);
    });
  }
  return el;
}

/**
 * The P/T, loyalty or defence in the card's corner. The face the card is showing now is counted as the board counts
 * it: damage comes off the toughness, and a number above or below the printed one is coloured so.
 */
function drawPt(el: HTMLElement, model: Model, key: number | null, face: CardFace, current: boolean): void {
  el.classList.toggle('shield', /Planeswalker/.test(face.type ?? ''));
  const card = current && key !== null ? model.objects.get(key) as CardView | undefined : undefined;
  const state = card ? stateOf(model, card) : undefined;
  if (!card || !state || !/Creature/.test(face.type ?? '')) {
    el.textContent = face.pt ?? '';
    return;
  }
  const power = state.Power ?? 0;
  const toughness = (state.Toughness ?? 0) - (card.Damage ?? 0);
  const shift = (now: number, base: number | undefined) => (base === undefined || now === base ? '' : now > base ? 'up' : 'down');
  const p = document.createElement('i');
  p.textContent = String(power);
  p.dataset.shift = shift(power, state.BasePower);
  const tough = document.createElement('i');
  tough.textContent = String(toughness);
  tough.dataset.shift = card.Damage ? 'down' : shift(toughness, state.BaseToughness);
  el.replaceChildren(p, '/', tough);
}

function ensureZoom(zoom: HTMLElement): void {
  if (zoom.firstChild) return;
  zoom.innerHTML = '<span class="shot"><img alt=""><span class="from-tab"></span></span><div class="detail"><header><b class="name"></b><span class="cost"></span></header><div class="cmdr-taken" hidden></div><div class="type"><span class="type-line"></span><span class="kind-chip"></span><span class="set"></span></div><div class="from"></div><div class="text"></div><div class="foot"></div><div class="pt"></div></div><div class="hint"></div>';
  const img = q<HTMLImageElement>(zoom, 'img');
  hideOnError(img);
  // Cleared once played: a preview still carrying the class would flip again the next time it is shown
  zoom.addEventListener('animationend', e => {
    if (e.animationName === 'zoom-flip') zoom.classList.remove('flipping');
  });
  // The preview lets the pointer through, so rules text too long for its box scrolls with the wheel over the card itself
  const box = q(zoom, '.text');
  document.addEventListener('wheel', e => {
    if (zoom.hidden || !zoom.classList.contains('text-card') || box.scrollHeight <= box.clientHeight) return;
    e.preventDefault();
    box.scrollTop += e.deltaMode === WheelEvent.DOM_DELTA_LINE ? e.deltaY * 16 : e.deltaY;
  }, { passive: false });
  // The preview is placed before its image arrives, and grows when it does, so it is placed again to stay on screen
  img.addEventListener('load', () => {
    const hover = ui.hover;
    if (hover && 'card' in hover && hover.at) placeZoom(zoom, hover.at);
  });
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
  title.textContent = t('lblWebDetailCommanderDamageTaken');
  root.append(title);
  for (const { card, value } of hits) {
    const commander = deref(model, card) as CardView | undefined;
    const row = document.createElement('div');
    row.className = value >= COMMANDER_WARNING ? 'cmdr-row near' : 'cmdr-row';
    row.innerHTML = '<span class="art"></span><span class="who"><span class="nm"></span><span class="bar"><i></i></span></span><span class="v"><b></b><small></small></span>';
    const src = cardImageSrc(model, commander);
    if (src) q(row, '.art').style.backgroundImage = `url("${src}")`;
    q(row, '.nm').textContent = (commander ? stateOf(model, commander).Name : undefined) ?? t('lblWebDetailCommander');
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
  setSource(q(zoom, '.from-tab'), undefined);
  q(zoom, '.hint').textContent = '';
  if (!d) return;
  q(zoom, '.name').textContent = d.name ?? '';
  q(zoom, '.cost').textContent = '';
  q(zoom, '.type-line').textContent = '';
  q(zoom, '.kind-chip').textContent = '';
  q(zoom, '.set').textContent = '';
  const text = q(zoom, '.text');
  text.textContent = d.lines.join('\n');
  delete text.dataset.html;
  q(zoom, '.foot').replaceChildren();
  q(zoom, '.pt').textContent = '';
}
