// Choosing a sleeve. Two kinds: the numbered ones the skin ships, and a card's art cropped to a card back.
// A card-art sleeve belongs to the deck rather than the player, so choosing one writes it onto the deck and
// every other Forge client shows it too.

import { sleeveUrl } from './looks';
import { setImage, imageUrl } from './images';
import { byId, q } from './dom';
import type { Printing, SavedSleeveArt, Seat, Send } from './protocol';

const SEARCH_DEBOUNCE_MS = 250;
const CENTRE = 500;

let overlay: HTMLElement | null = null;
let seatIndex = 0;
let send: Send = () => {};
let sleeveCount = 0;
let saved: SavedSleeveArt[] = [];
let names: string[] = [];
let printings: Printing[] = [];
let picked: Printing | null = null;
let offset = CENTRE;
let timer = 0;

export function initSleeves(count: number, savedArt: SavedSleeveArt[] | undefined): void {
  sleeveCount = count;
  saved = savedArt ?? [];
}

export function sleevePickerOpen(): boolean {
  return !!overlay;
}

export function closeSleevePicker(): void {
  overlay?.remove();
  overlay = null;
  picked = null;
  printings = [];
}

export function onCardNames(list: string[] | undefined): void {
  names = list ?? [];
  if (overlay) {
    renderNames();
  }
}

export function onPrintings(list: Printing[] | undefined): void {
  printings = list ?? [];
  if (overlay) {
    renderPrintings();
    if (!picked && printings.length) {
      choose(printings[0]);
    }
  }
}

export function openSleevePicker(index: number, seat: Seat, sendFn: Send): void {
  seatIndex = index;
  send = sendFn;
  picked = null;
  offset = seat.sleeveOffset ?? CENTRE;
  build(seat);
}

function build(seat: Seat): void {
  closeSleevePicker();
  const back = document.createElement('div');
  overlay = back;
  back.className = 'sleeves-back';
  back.innerHTML = `
    <div class="sleeves">
      <header class="sleeves-head">
        <h2>Choose a sleeve</h2>
        <button class="dk-close" title="Close">&times;</button>
      </header>
      <div class="sleeves-body">
        <section class="saved">
          <h3>Card art</h3>
          <div class="art-grid"></div>
          <p class="no-deck-why" hidden>Card art is saved on the deck, so choose a deck for this seat first.</p>
        </section>
        <section class="numbered">
          <h3>Sleeves</h3>
          <div class="sleeve-grid"></div>
        </section>
        <section class="art-picker" hidden>
          <button class="back-to-sleeves">&larr; Back to sleeves</button>
        <div class="names"><input class="card-find" type="search" placeholder="Search a card by name" autocomplete="off"><div class="name-list"></div></div>
        <div class="prints"></div>
        <div class="preview">
          <p class="preview-title">Sleeve preview</p>
          <div class="preview-frame"><img alt=""></div>
          <p class="dk-hint">Drag the art to move the crop</p>
          <button class="primary use-art" disabled>Use this art</button>
        </div>
        </section>
      </div>
    </div>`;
  byId('dialog-layer').append(back);
  q(back, '.dk-close').onclick = closeSleevePicker;
  back.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
      e.stopPropagation();
      closeSleevePicker();
    }
  });
  const find = q<HTMLInputElement>(back, '.card-find');
  find.oninput = () => {
    clearTimeout(timer);
    timer = setTimeout(() => send({ t: 'cardSearch', query: find.value }), SEARCH_DEBOUNCE_MS);
  };
  q(back, '.use-art').onclick = () => {
    if (picked) {
      send({ t: 'sleeveArt', index: seatIndex, key: picked.key, offset });
      closeSleevePicker();
    }
  };
  q(back, '.back-to-sleeves').onclick = () => showPicker(false);
  renderSaved(seat);
  renderNumbered(seat);
  dragToCrop();
}

// Choosing a card is a step of its own, so the sleeve grid gives way to it rather than sitting above it
function showPicker(on: boolean): void {
  if (!overlay) return;
  q(overlay, '.sleeves').classList.toggle('picking', on);
  q(overlay, '.art-picker').hidden = !on;
  q(overlay, '.sleeves-head h2').textContent = on ? 'Choose a card' : 'Choose a sleeve';
  if (on) {
    q(overlay, '.card-find').focus();
  }
}

function renderSaved(seat: Seat): void {
  if (!overlay) return;
  // Every card-art route writes to the seat's deck; with no deck the choice would be silently dropped
  const section = q(overlay, '.saved');
  section.classList.toggle('no-deck', !seat.deck);
  q(section, '.no-deck-why').hidden = !!seat.deck;
  const grid = q(overlay, '.art-grid');
  const tiles = saved.map(art => {
    const b = document.createElement('button');
    b.className = 'art-tile';
    b.setAttribute('aria-pressed', String(art.key === seat.sleeveArt));
    const img = document.createElement('img');
    setImage(img, artUrl(art.key));
    img.style.objectPosition = position(art.offset);
    b.append(img);
    b.onclick = () => {
      send({ t: 'sleeveArt', index: seatIndex, key: art.key, offset: art.offset });
      closeSleevePicker();
    };
    return b;
  });
  const add = document.createElement('button');
  add.className = 'art-tile add';
  add.textContent = '+';
  add.title = 'Pick a card';
  add.onclick = () => showPicker(true);
  grid.replaceChildren(...tiles, add);
}

function renderNumbered(seat: Seat): void {
  if (!overlay) return;
  const grid = q(overlay, '.sleeve-grid');
  const tiles: HTMLElement[] = [];
  for (let i = 0; i < sleeveCount; i++) {
    const b = document.createElement('button');
    b.className = 'sleeve-tile';
    b.setAttribute('aria-pressed', String(!seat.sleeveArt && i === seat.sleeve));
    const img = document.createElement('img');
    setImage(img, sleeveUrl(i));
    b.append(img);
    // Clearing the card art falls back to this numbered sleeve, which the deck no longer overrides
    b.onclick = () => {
      send({ t: 'sleeveArt', index: seatIndex, key: '', offset: CENTRE });
      send({ t: 'setSeat', index: seatIndex, sleeve: i });
      closeSleevePicker();
    };
    tiles.push(b);
  }
  grid.replaceChildren(...tiles);
}

function renderNames(): void {
  if (!overlay) return;
  const list = q(overlay, '.name-list');
  list.replaceChildren(...names.map(name => {
    const b = document.createElement('button');
    b.className = 'dk-cardname';
    b.textContent = name;
    b.onclick = () => {
      for (const other of list.children) {
        other.setAttribute('aria-pressed', String(other === b));
      }
      picked = null;
      send({ t: 'printings', name });
    };
    return b;
  }));
}

function renderPrintings(): void {
  if (!overlay) return;
  const grid = q(overlay, '.prints');
  grid.replaceChildren(...printings.map(p => {
    const b = document.createElement('button');
    b.className = 'print';
    b.setAttribute('aria-pressed', String(picked?.key === p.key));
    const img = document.createElement('img');
    setImage(img, imageUrl(p.key));
    b.append(img, Object.assign(document.createElement('span'), { textContent: p.edition }));
    b.onclick = () => choose(p);
    return b;
  }));
}

function choose(print: Printing): void {
  if (!overlay) return;
  picked = print;
  // Each newly chosen card starts centred; dragging re-frames it
  offset = CENTRE;
  renderPrintings();
  const img = q<HTMLImageElement>(overlay, '.preview-frame img');
  setImage(img, artUrl(print.key));
  img.style.objectPosition = position(offset);
  q<HTMLButtonElement>(overlay, '.use-art').disabled = false;
}

// The art is cover-cropped, so only one axis has slack; dragging moves the crop along it
function dragToCrop(): void {
  if (!overlay) return;
  const frame = q(overlay, '.preview-frame');
  let startX = 0;
  let startY = 0;
  let from = CENTRE;
  frame.onpointerdown = e => {
    if (!picked) {
      return;
    }
    startX = e.clientX;
    startY = e.clientY;
    from = offset;
    frame.setPointerCapture(e.pointerId);
  };
  frame.onpointermove = e => {
    if (!picked || !frame.hasPointerCapture(e.pointerId)) {
      return;
    }
    // Dragging the art with the cursor moves the crop window the other way
    const travel = Math.max(frame.clientWidth, frame.clientHeight) || 1;
    const moved = Math.abs(e.clientX - startX) > Math.abs(e.clientY - startY) ? e.clientX - startX : e.clientY - startY;
    offset = Math.max(0, Math.min(1000, Math.round(from - (moved * 1000) / travel)));
    q(frame, 'img').style.objectPosition = position(offset);
  };
  frame.onpointerup = e => frame.releasePointerCapture(e.pointerId);
}

const position = (value: number | undefined): string => `${(value ?? CENTRE) / 10}% ${(value ?? CENTRE) / 10}%`;
const artUrl = (key: string): string => `/sleeveart?key=${encodeURIComponent(key)}`;
