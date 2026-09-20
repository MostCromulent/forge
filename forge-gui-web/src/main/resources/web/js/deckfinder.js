// Choosing a deck. One query runs across every source at once, so knowing a deck's name is enough and
// picking a source is optional. The panel beside the results carries the whole decklist, which is why
// there is no separate "view deck" window.

const SEARCH_DEBOUNCE_MS = 200;

let overlay = null;
let seatIndex = 0;
let send = null;
let decks = [];
let chosen = null;
let query = '';
let source = 'all';
let timer = 0;
let details = null;

export function openDeckFinder(index, seat, model, sendFn) {
  seatIndex = index;
  send = sendFn;
  decks = model.decks ?? [];
  chosen = seat.deck ?? null;
  query = '';
  source = 'all';
  details = null;
  build();
  if (chosen) {
    send({ t: 'deckDetails', key: chosen });
  }
}

/** The host answered with a decklist for the panel. */
export function onDeckDetails(deck) {
  if (!overlay) {
    return;
  }
  details = deck;
  renderPanel();
}

/** A fresh deck list arrived, usually because the format changed under us. */
export function onDecks(list) {
  decks = list ?? [];
  if (overlay) {
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
          <input class="find" type="search" placeholder="Search every deck by name" autocomplete="off">
          <div class="facets"></div>
          <div class="dk-hits"></div>
        </div>
        <aside class="dk-chosen"></aside>
      </div>
      <footer class="finder-foot">
        <button class="cancel">Cancel</button>
        <button class="use primary">Use this deck</button>
      </footer>
    </div>`;
  document.getElementById('dialog-layer').append(overlay);
  overlay.querySelector('.dk-close').onclick = closeDeckFinder;
  overlay.querySelector('.cancel').onclick = closeDeckFinder;
  overlay.querySelector('.use').onclick = () => {
    if (chosen) {
      send({ t: 'setSeat', index: seatIndex, deck: chosen });
      closeDeckFinder();
    }
  };
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
  renderResults();
  renderPanel();
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

function matching() {
  return decks.filter(d => (source === 'all' || d.source === source)
    && (!query || d.name.toLowerCase().includes(query)));
}

function renderResults() {
  if (!overlay) {
    return;
  }
  const rows = overlay.querySelector('.dk-hits');
  const list = matching();
  if (!list.length) {
    rows.replaceChildren(Object.assign(document.createElement('p'),
      { className: 'none', textContent: 'No deck of that name in the sources you have ticked.' }));
    return;
  }
  rows.replaceChildren(...list.map(d => {
    const row = document.createElement('button');
    row.className = 'dk-hit';
    row.setAttribute('aria-pressed', String(d.key === chosen));
    // An illegal deck is marked rather than hidden, so nobody hunts the editor for a deck that is here
    row.innerHTML = `<span class="dk-hit-name">${d.name}</span>`
      + `<span class="tag">${d.source}</span>`
      + `<span class="legal ${d.problem ? 'no' : 'yes'}">${d.problem ? 'Illegal' : 'Legal'}</span>`;
    row.title = d.problem ?? '';
    row.onclick = () => {
      chosen = d.key;
      details = null;
      send({ t: 'deckDetails', key: d.key });
      renderResults();
      renderPanel();
    };
    return row;
  }));
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
  const groups = details.main.map(g => `
    <div class="group">
      <h4>${g.heading}<span>${g.cards.reduce((n, c) => n + c.count, 0)}</span></h4>
      ${g.cards.map(c => `<div class="line"><span class="n">${c.count}</span>${c.name}</div>`).join('')}
    </div>`).join('');
  const side = details.sideboard.length ? `
    <div class="group">
      <h4>Sideboard<span>${details.sideboard.reduce((n, c) => n + c.count, 0)}</span></h4>
      ${details.sideboard.map(c => `<div class="line"><span class="n">${c.count}</span>${c.name}</div>`).join('')}
    </div>` : '';
  panel.innerHTML = `
    <div class="dk-chosen-head">
      <h3>${details.name}</h3>
      <p class="${details.problem ? 'verdict no' : 'verdict yes'}">${details.problem ?? 'Legal for this format.'}</p>
    </div>
    <div class="dk-cards">${groups}${side}</div>`;
}
