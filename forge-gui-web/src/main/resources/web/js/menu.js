// The first screen: what you can do, and where each one stands. A mode says what it holds for you
// ("14 decks", "no decks yet") rather than repeating its own name, so the page is worth reading once.

let built = false;
let go = null;

// Everything the desktop client offers. Only the ones marked ready are built; the rest are shown so the
// shape of the product is honest, and greyed so nothing looks broken.
const MODES = [
  { id: 'play', name: 'Play', blurb: 'One match against the computer', ready: true },
  { id: 'multiplayer', name: 'Multiplayer', blurb: 'Play someone else over a network' },
  { id: 'draft', name: 'Draft and Sealed', blurb: 'Build from packs, then play what you opened' },
  { id: 'quest', name: 'Quest', blurb: 'A run of matches with a deck you grow' },
  { id: 'editor', name: 'Deck editor', blurb: 'Build and change decks' },
];

export function renderMenu(model, send) {
  const root = document.getElementById('menu');
  if (!built) {
    go = send;
    root.innerHTML = `
      <div class="menu-page">
        <h1 class="wordmark">Forge</h1>
        <div class="modes" id="modes"></div>
        <div class="menu-foot">
          <span id="menu-who"></span>
          <button id="menu-quit">Quit</button>
        </div>
      </div>`;
    const list = root.querySelector('#modes');
    for (const mode of MODES) {
      const b = document.createElement('button');
      b.className = 'mode';
      b.dataset.mode = mode.id;
      b.disabled = !mode.ready;
      b.innerHTML = `<span class="mode-name">${mode.name}</span>`
        + `<span class="mode-blurb">${mode.blurb}</span>`
        + `<span class="mode-status" data-status="${mode.id}"></span>`;
      if (mode.ready) {
        b.onclick = () => send({ t: 'lobby' });
      }
      list.append(b);
    }
    root.querySelector('#menu-quit').onclick = () => send({ t: 'quit' });
    built = true;
  }
  const decks = model.decks?.length ?? 0;
  status(root, 'play', decks ? `${decks} decks ready` : 'no decks yet — a precon will do');
  status(root, 'multiplayer', 'not built yet');
  status(root, 'draft', 'not built yet');
  status(root, 'quest', 'not built yet');
  status(root, 'editor', 'not built yet');
  root.querySelector('#menu-who').textContent = model.playerName ? `Playing as ${model.playerName}` : '';
}

function status(root, id, text) {
  const el = root.querySelector(`[data-status="${id}"]`);
  if (el) {
    el.textContent = text;
  }
}
