// Choosing a sleeve. Two kinds: the numbered ones the skin ships, and a card's art cropped to a card back.
// A card-art sleeve belongs to the deck rather than the player, so choosing one writes it onto the deck and
// every other Forge client shows it too.

import { sleeveUrl } from './looks.js';
import { setImage, imageUrl } from './images.js';

const SEARCH_DEBOUNCE_MS = 250;
const CENTRE = 500;

let overlay = null;
let seatIndex = 0;
let send = null;
let sleeveCount = 0;
let saved = [];
let names = [];
let printings = [];
let picked = null;
let offset = CENTRE;
let timer = 0;

export function initSleeves(count, savedArt) {
  sleeveCount = count;
  saved = savedArt ?? [];
}

export function sleevePickerOpen() {
  return !!overlay;
}

export function closeSleevePicker() {
  overlay?.remove();
  overlay = null;
  picked = null;
  printings = [];
}

export function onCardNames(list) {
  names = list ?? [];
  if (overlay) {
    renderNames();
  }
}

export function onPrintings(list) {
  printings = list ?? [];
  if (overlay) {
    renderPrintings();
    if (!picked && printings.length) {
      choose(printings[0]);
    }
  }
}

export function openSleevePicker(index, seat, sendFn) {
  seatIndex = index;
  send = sendFn;
  picked = null;
  offset = seat.sleeveOffset ?? CENTRE;
  build(seat);
}

function build(seat) {
  closeSleevePicker();
  overlay = document.createElement('div');
  overlay.className = 'sleeves-back';
  overlay.innerHTML = `
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
  document.getElementById('dialog-layer').append(overlay);
  overlay.querySelector('.dk-close').onclick = closeSleevePicker;
  overlay.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
      e.stopPropagation();
      closeSleevePicker();
    }
  });
  const find = overlay.querySelector('.card-find');
  find.oninput = () => {
    clearTimeout(timer);
    timer = setTimeout(() => send({ t: 'cardSearch', query: find.value }), SEARCH_DEBOUNCE_MS);
  };
  overlay.querySelector('.use-art').onclick = () => {
    if (picked) {
      send({ t: 'sleeveArt', index: seatIndex, key: picked.key, offset });
      closeSleevePicker();
    }
  };
  overlay.querySelector('.back-to-sleeves').onclick = () => showPicker(false);
  renderSaved(seat);
  renderNumbered(seat);
  dragToCrop();
}

// Choosing a card is a step of its own, so the sleeve grid gives way to it rather than sitting above it
function showPicker(on) {
  overlay.querySelector('.sleeves').classList.toggle('picking', on);
  overlay.querySelector('.art-picker').hidden = !on;
  overlay.querySelector('.sleeves-head h2').textContent = on ? 'Choose a card' : 'Choose a sleeve';
  if (on) {
    overlay.querySelector('.card-find').focus();
  }
}

function renderSaved(seat) {
  // Every card-art route writes to the seat's deck; with no deck the choice would be silently dropped
  const section = overlay.querySelector('.saved');
  section.classList.toggle('no-deck', !seat.deck);
  section.querySelector('.no-deck-why').hidden = !!seat.deck;
  const grid = overlay.querySelector('.art-grid');
  const tiles = saved.map(art => {
    const b = document.createElement('button');
    b.className = 'art-tile';
    b.setAttribute('aria-pressed', String(art.key === seat.sleeveArt));
    const img = document.createElement('img');
    setImage(img, artUrl(art.key));
    img.style.objectPosition = position(art.offset);
    b.append(img);
    b.onclick = () => send({ t: 'sleeveArt', index: seatIndex, key: art.key, offset: art.offset })
      || closeSleevePicker();
    return b;
  });
  const add = document.createElement('button');
  add.className = 'art-tile add';
  add.textContent = '+';
  add.title = 'Pick a card';
  add.onclick = () => showPicker(true);
  grid.replaceChildren(...tiles, add);
}

function renderNumbered(seat) {
  const grid = overlay.querySelector('.sleeve-grid');
  const tiles = [];
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

function renderNames() {
  const list = overlay.querySelector('.name-list');
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

function renderPrintings() {
  const grid = overlay.querySelector('.prints');
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

function choose(print) {
  picked = print;
  // Each newly chosen card starts centred; dragging re-frames it
  offset = CENTRE;
  renderPrintings();
  const img = overlay.querySelector('.preview-frame img');
  setImage(img, artUrl(print.key));
  img.style.objectPosition = position(offset);
  overlay.querySelector('.use-art').disabled = false;
}

// The art is cover-cropped, so only one axis has slack; dragging moves the crop along it
function dragToCrop() {
  const frame = overlay.querySelector('.preview-frame');
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
    frame.querySelector('img').style.objectPosition = position(offset);
  };
  frame.onpointerup = e => frame.releasePointerCapture(e.pointerId);
}

const position = value => `${(value ?? CENTRE) / 10}% ${(value ?? CENTRE) / 10}%`;
const artUrl = key => `/sleeveart?key=${encodeURIComponent(key)}`;
