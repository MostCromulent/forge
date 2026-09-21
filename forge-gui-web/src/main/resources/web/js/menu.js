// The first screen: what you can do, and where each one stands. A mode says what it holds for you
// ("14 decks", "no decks yet") rather than repeating its own name, so the page is worth reading once.
//
// Only the browser holding the host's seat sees this. Nobody holds it by arriving: the seat is offered to
// whoever asks first, and every other browser goes straight to a seat in the host's game.

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
  // A browser without the host's seat has no menu: it is offered the seat, or told to wait for one
  if (model.host === false) {
    renderWaiting(root, model, send);
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

/** Remembered so the browser that runs this server does not have to say so on every launch. */
const HOSTED_KEY = 'forge.hostedBefore';

function hostedBefore() {
  try {
    return localStorage.getItem(HOSTED_KEY) === '1';
  } catch {
    return false;
  }
}

let claimed = false;

/**
 * A browser with no seat. Nobody hosts by arriving, so while the host's seat is free this offers it, and a
 * browser that has hosted this server before takes it back without being asked again.
 */
function renderWaiting(root, model, send) {
  if (model.canClaimHost && hostedBefore() && !claimed) {
    claimed = true;
    send({ t: 'claimHost' });
  }
  const page = model.canClaimHost ? 'claim' : 'waiting';
  if (root.dataset.page === page) {
    return;
  }
  root.dataset.page = page;
  root.innerHTML = model.canClaimHost ? `
    <div class="menu-page">
      <h1 class="wordmark">Forge</h1>
      <p class="menu-note">Nobody is running a game on this server yet.</p>
      <div class="connect">
        <section class="connect-card">
          <h2>Host the game</h2>
          <p>You set the table, pick the format and start the match. Everyone else joins you.</p>
          <button id="be-host" class="primary">Host</button>
        </section>
        <section class="connect-card">
          <h2>Wait for a host</h2>
          <p>Someone else takes the seat. You are given one of your own as soon as they open a game.</p>
        </section>
      </div>
    </div>` : `
    <div class="menu-page">
      <h1 class="wordmark">Forge</h1>
      <p class="menu-note">Waiting for the host to open a game.</p>
    </div>`;
  const host = root.querySelector('#be-host');
  if (host) {
    host.onclick = () => {
      try {
        localStorage.setItem(HOSTED_KEY, '1');
      } catch {
        // Storage can be unavailable; the choice then has to be made again next launch
      }
      claimed = true;
      send({ t: 'claimHost' });
    };
  }
}

function status(root, id, text) {
  const el = root.querySelector(`[data-status="${id}"]`);
  if (el) {
    el.textContent = text;
  }
}
