// Choosing a deck. One query runs across every source at once, so knowing a deck's name is enough and
// picking a source is optional. Colour and source narrow the list rather than hiding anything for good.
// The panel beside the results carries the deck's statistics and its whole card list, which is why there
// is no separate window for reading one.

import { setImage, imageUrl } from './images.js';

const SEARCH_DEBOUNCE_MS = 200;
const PEEK_W = 240;
const PEEK_H = 336;
// Colour identity, in the order Magic writes it, plus colourless
const COLOURS = [['W', 'White'], ['U', 'Blue'], ['B', 'Black'], ['R', 'Red'], ['G', 'Green'], ['C', 'Colourless']];
const SORTS = [['name', 'Name'], ['colors', 'Colour'], ['formats', 'Format'], ['size', 'Size'], ['legal', 'Legal first']];

let overlay = null;
let seatIndex = 0;
let send = null;
let decks = [];
let chosen = null;
let query = '';
let source = 'all';
let colours = new Set();
let sort = 'name';
let cardFormat = 'any';
let legalOnly = false;
let cardFormats = [];
let timer = 0;
let details = null;
let isHost = true;

export function openDeckFinder(index, seat, model, sendFn) {
  seatIndex = index;
  send = sendFn;
  decks = model.decks ?? [];
  isHost = model.host !== false;
  chosen = seat.deck ?? null;
  query = '';
  source = 'all';
  colours = new Set();
  sort = 'name';
  cardFormat = 'any';
  legalOnly = false;
  details = null;
  build();
  if (chosen) {
    send({ t: 'deckDetails', key: chosen });
  }
}

export function onDeckDetails(deck) {
  if (overlay) {
    details = deck;
    renderPanel();
  }
}

/** A fresh deck list arrived, usually because the format changed under us. */
export function onDecks(list, formats) {
  decks = list ?? [];
  cardFormats = formats ?? cardFormats;
  if (overlay) {
    renderFacets();
    renderColours();
    renderResults();
  }
}

export function deckFinderOpen() {
  return !!overlay;
}

export function closeDeckFinder() {
  overlay?.remove();
  overlay = null;
  details = null;
}

function build() {
  closeDeckFinder();
  overlay = document.createElement('div');
  overlay.className = 'finder-back';
  overlay.innerHTML = `
    <div class="finder">
      <header class="finder-head">
        <h2>Choose a deck</h2>
        <button class="dk-close" title="Close">&times;</button>
      </header>
      <div class="finder-body">
        <div class="results">
          <div class="find-row">
            <input class="find" type="search" placeholder="Search every deck by name" autocomplete="off">
            <label class="sort">Format
              <select class="format-by"><option value="any">Any</option>${cardFormats.map(f => `<option value="${f}">${f}</option>`).join('')}</select>
            </label>
            <label class="sort">Sort
              <select class="sort-by">${SORTS.map(([id, name]) => `<option value="${id}">${name}</option>`).join('')}</select>
            </label>
          </div>
          <div class="facets"></div>
          <button class="get-net">Get net decks…</button>
          <div class="colours"></div>
          <label class="legal-only"><input type="checkbox"> Playable decks only</label>
          <p class="shown"></p>
          <div class="dk-hits"></div>
        </div>
        <aside class="dk-chosen"></aside>
      </div>
      <footer class="finder-foot">
        <button class="cancel">Cancel</button>
        <button class="use primary">Use this deck</button>
      </footer>
      <div class="deck-peek" hidden><img alt=""></div>
    </div>`;
  document.getElementById('dialog-layer').append(overlay);
  overlay.querySelector('.dk-close').onclick = closeDeckFinder;
  overlay.querySelector('.cancel').onclick = closeDeckFinder;
  overlay.querySelector('.use').onclick = useChosen;
  overlay.querySelector('.sort-by').onchange = e => { sort = e.target.value; renderResults(); };
  const net = overlay.querySelector('.get-net');
  // Core asks which category through a dialog on the host's screen, so only the host can answer it
  net.hidden = !isHost;
  net.onclick = () => send({ t: 'netDecks' });
  overlay.querySelector('.format-by').onchange = e => { cardFormat = e.target.value; renderResults(); };
  overlay.querySelector('.legal-only input').onchange = e => { legalOnly = e.target.checked; renderResults(); };
  const find = overlay.querySelector('.find');
  // A long list costs an image request per row, so typing waits for a pause
  find.oninput = () => {
    clearTimeout(timer);
    timer = setTimeout(() => { query = find.value.trim().toLowerCase(); renderResults(); }, SEARCH_DEBOUNCE_MS);
  };
  overlay.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
      e.stopPropagation();
      closeDeckFinder();
    }
  });
  find.focus();
  renderFacets();
  renderColours();
  renderResults();
  renderPanel();
}

function useChosen() {
  if (chosen) {
    send({ t: 'setSeat', index: seatIndex, deck: chosen });
    closeDeckFinder();
  }
}

function sources() {
  const counts = new Map();
  for (const d of decks) {
    counts.set(d.source, (counts.get(d.source) ?? 0) + 1);
  }
  return [['all', decks.length], ...counts];
}

function renderFacets() {
  const row = overlay.querySelector('.facets');
  row.replaceChildren(...sources().map(([id, count]) => {
    const b = document.createElement('button');
    b.className = 'facet';
    b.textContent = id === 'all' ? 'All sources' : id;
    b.append(Object.assign(document.createElement('span'), { className: 'dk-count', textContent: count }));
    b.setAttribute('aria-pressed', String(id === source));
    b.onclick = () => { source = id; renderFacets(); renderResults(); };
    return b;
  }));
}

/** Colour identity as a filter: several ticked means a deck carrying any of them. */
function renderColours() {
  const row = overlay.querySelector('.colours');
  row.replaceChildren(...COLOURS.map(([letter, name]) => {
    const n = decks.filter(d => (d.colors ?? '').includes(letter)).length;
    const b = document.createElement('button');
    b.className = 'colour';
    b.title = name;
    b.innerHTML = `<i class="pip pip-${letter}">${letter}</i><span class="dk-count">${n}</span>`;
    b.setAttribute('aria-pressed', String(colours.has(letter)));
    b.onclick = () => {
      if (!colours.delete(letter)) {
        colours.add(letter);
      }
      renderColours();
      renderResults();
    };
    return b;
  }));
}

function matching() {
  const list = decks.filter(d => (source === 'all' || d.source === source)
    && (!query || d.name.toLowerCase().includes(query))
    && (!colours.size || [...colours].some(c => (d.colors ?? '').includes(c)))
    && (d.generated || !legalOnly || !d.problem)
    && (d.generated || cardFormat === 'any' || (d.legalIn ?? []).includes(cardFormat)));
  const byName = (a, b) => a.name.localeCompare(b.name);
  const by = {
    name: byName,
    colors: (a, b) => (a.colors ?? '').localeCompare(b.colors ?? '') || byName(a, b),
    formats: (a, b) => (a.formats ?? '').localeCompare(b.formats ?? '') || byName(a, b),
    size: (a, b) => b.main - a.main || byName(a, b),
    legal: (a, b) => Number(!!a.problem) - Number(!!b.problem) || byName(a, b),
  };
  return list.sort(by[sort] ?? byName);
}

function renderResults() {
  if (!overlay) {
    return;
  }
  const list = matching();
  overlay.querySelector('.shown').textContent = list.length === decks.length
    ? `${decks.length} decks`
    : `${list.length} of ${decks.length} decks`;
  const rows = overlay.querySelector('.dk-hits');
  if (!list.length) {
    rows.replaceChildren(Object.assign(document.createElement('p'),
      { className: 'none', textContent: 'No deck matches. Clear a filter, or search a different name.' }));
    return;
  }
  rows.replaceChildren(...list.map(row));
}

function row(d) {
  const el = document.createElement('button');
  el.className = 'dk-hit';
  el.setAttribute('aria-pressed', String(d.key === chosen));
  // A generator has nothing to measure until it has built something, so it says what it is instead
  if (d.generated) {
    el.classList.add('generated');
    el.innerHTML = `<span class="dk-hit-name">${d.name}</span>`
      + `<span class="pips">${pips(d.colors)}</span>`
      + `<span class="note">${d.note ?? ''}</span>`
      + `<span class="tag">${d.source}</span>`;
    el.onclick = () => choose(d.key);
    el.ondblclick = () => { choose(d.key); useChosen(); };
    return el;
  }
  // An illegal deck is marked rather than hidden, so nobody hunts the editor for a deck that is here
  el.innerHTML = `<span class="dk-hit-name">${d.name}</span>`
    + `<span class="pips">${pips(d.colors)}</span>`
    + `<span class="size">${d.main}${d.sideboard ? `+${d.sideboard}` : ''}</span>`
    + `<span class="deck-formats">${d.formats ?? ''}</span>`
    + `<span class="tag">${d.source}</span>`
    + `<span class="legal ${d.problem ? 'no' : 'yes'}">${d.problem ? 'Illegal' : 'Legal'}</span>`;
  el.title = d.problem ?? '';
  el.onclick = () => choose(d.key);
  el.ondblclick = () => { choose(d.key); useChosen(); };
  return el;
}

const pips = colors => [...(colors ?? '')].map(c => `<i class="pip pip-${c}">${c}</i>`).join('');

function choose(key) {
  if (chosen === key) {
    return;
  }
  chosen = key;
  details = null;
  send({ t: 'deckDetails', key });
  renderResults();
  renderPanel();
}

function renderPanel() {
  if (!overlay) {
    return;
  }
  const panel = overlay.querySelector('.dk-chosen');
  overlay.querySelector('.use').disabled = !chosen;
  if (!chosen) {
    panel.innerHTML = '<p class="none">Pick a deck on the left and its cards appear here.</p>';
    return;
  }
  if (!details || details.key !== chosen) {
    panel.innerHTML = '<p class="none">Reading the deck…</p>';
    return;
  }
  const s = details.stats;
  const tallest = Math.max(1, ...s.curve);
  panel.innerHTML = `
    <div class="dk-chosen-head">
      <h3>${details.name} <span class="pips">${pips(details.colors)}</span></h3>
      <p class="sizes">${s.main} cards${s.sideboard ? ` · ${s.sideboard} sideboard` : ''} · ${s.lands} lands</p>
      <p class="${details.problem ? 'verdict no' : 'verdict yes'}">${details.problem ?? 'Legal for this format.'}</p>
      <div class="stats">
        <div class="curve">
          <h4>Mana curve</h4>
          <div class="bars">${s.curve.map((n, i) => bar(n, i, s.curve.length, tallest)).join('')}</div>
        </div>
        <div class="types">
          ${s.types.map(t => `<div class="type"><span>${t.name}</span><b>${t.count}</b></div>`).join('')}
          <div class="type avg"><span>Average mana value</span><b>${s.averageMana}</b></div>
        </div>
      </div>
    </div>
    <div class="dk-cards">${section(details.main)}${sideboard(details.sideboard)}</div>`;
  peeking(panel);
}

// The last bucket holds everything at that mana value and above. Heights are in pixels because a
// percentage would resolve against an auto-sized row and collapse to nothing.
const CURVE_PX = 42;
const bar = (n, i, buckets, tallest) => {
  const label = i === buckets - 1 ? `${i}+` : `${i}`;
  const h = n === 0 ? 2 : Math.max(3, Math.round((n / tallest) * CURVE_PX));
  return `<span class="bar" title="${n} at ${label}"><i style="height:${h}px"></i><em>${label}</em></span>`;
};

const section = groups => groups.map(g => `
  <div class="group">
    <h4>${g.heading}<span>${g.cards.reduce((n, c) => n + c.count, 0)}</span></h4>
    ${g.cards.map(line).join('')}
  </div>`).join('');

const sideboard = cards => (cards.length ? `
  <div class="group">
    <h4>Sideboard<span>${cards.reduce((n, c) => n + c.count, 0)}</span></h4>
    ${cards.map(line).join('')}
  </div>` : '');

const line = c => `<div class="dk-line" data-image="${c.image}"><span class="n">${c.count}</span>${c.name}</div>`;

/** Hovering a card in the list shows it, the way hovering one on the table does. */
function peeking(panel) {
  const peek = overlay.querySelector('.deck-peek');
  const img = peek.querySelector('img');
  panel.onpointerover = e => {
    const el = e.target.closest('.dk-line');
    if (!el) {
      return;
    }
    setImage(img, imageUrl(el.dataset.image));
    // Beside the line being pointed at, pushed left of it so the cursor never covers the card
    const box = el.getBoundingClientRect();
    const frame = overlay.getBoundingClientRect();
    peek.style.left = `${Math.max(12, box.left - frame.left - PEEK_W - 16)}px`;
    peek.style.top = `${Math.min(frame.height - PEEK_H - 12, Math.max(12, box.top - frame.top - PEEK_H / 2))}px`;
    peek.hidden = false;
  };
  panel.onpointerleave = () => { peek.hidden = true; };
}
