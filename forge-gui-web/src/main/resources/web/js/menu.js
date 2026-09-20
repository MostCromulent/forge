// The first screen: what you can do, and where each one stands. A mode says what it holds for you
// ("14 decks", "no decks yet") rather than repeating its own name, so the page is worth reading once.
//
// Only the browser on the machine running the game sees this. Anyone who opens the invite link is a guest
// and goes straight to the lobby, because the one thing they came to do is take a seat.

let built = false;

// Only the ones marked ready are built; the rest are shown so the shape of the product is honest,
// and greyed so nothing looks broken.
const MODES = [
  { id: 'play', name: 'Offline', blurb: 'A match against the computer', ready: true, msg: 'lobby' },
  { id: 'multiplayer', name: 'Multiplayer', blurb: 'Send a friend a link to your game', ready: true, msg: 'invite' },
  { id: 'editor', name: 'Deck editor', blurb: 'Build and change decks' },
];

export function renderMenu(model, send) {
  const root = document.getElementById('menu');
  // A browser is a host or a guest for as long as it is open, so which page this is never changes under it
  if (model.host === false) {
    renderWaiting(root);
    return;
  }
  if (!built) {
    root.innerHTML = `
      <div class="menu-page">
        <h1 class="wordmark">Forge</h1>
        <div class="modes" id="modes"></div>
        <p class="menu-note" id="menu-note"></p>
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
      if (mode.msg) {
        b.onclick = () => send({ t: mode.msg });
      }
      list.append(b);
    }
    root.querySelector('#menu-quit').onclick = () => send({ t: 'quit' });
    built = true;
  }
  const decks = model.decks?.length ?? 0;
  status(root, 'play', decks ? `${decks} decks ready` : 'no decks yet — a precon will do');
  status(root, 'multiplayer', 'opens a seat and gives you a link');
  status(root, 'editor', 'not built yet');
  root.querySelector('#menu-who').textContent = model.playerName ? `Playing as ${model.playerName}` : '';
  const note = root.querySelector('#menu-note');
  note.textContent = model.error ?? '';
  note.classList.toggle('bad', !!model.error);
}

/** A guest with no game to sit in. It joins the host's the moment there is one, so there is nothing to press. */
function renderWaiting(root) {
  if (root.dataset.page === 'waiting') {
    return;
  }
  root.dataset.page = 'waiting';
  root.innerHTML = `
    <div class="menu-page">
      <h1 class="wordmark">Forge</h1>
      <p class="menu-note">Waiting for the host to open a game.</p>
    </div>`;
}

function status(root, id, text) {
  const el = root.querySelector(`[data-status="${id}"]`);
  if (el) {
    el.textContent = text;
  }
}
