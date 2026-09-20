// Match setup. Each seat is a plate anchored by its deck's sleeve: the sleeve is the largest thing on it,
// clicking it chooses one, and a deck with card art on its sleeve shows that art instead of a numbered back.
// Seats are added, never presented as empty slots waiting to be filled.
//
// A seat's type is the one a netplay lobby slot carries, so OPEN and REMOTE already have a place to land;
// offline only ever sends LOCAL and AI.

import { sleeveUrl, avatarUrl, pickLook } from './looks.js';
import { setImage } from './images.js';
import { openDeckFinder } from './deckfinder.js';
import { openSleevePicker } from './sleeves.js';
import { startMusic } from './audio.js';

let built = false;
let send = null;

export function renderLobby(model, sendFn) {
  send = sendFn;
  const root = document.getElementById('lobby');
  if (!built) {
    root.innerHTML = `
      <header class="lobby-head">
        <span class="wordmark">Forge</span>
        <div class="formats" id="formats"></div>
        <div class="head-right">
          <label class="spectate"><input id="spectate" type="checkbox"> Watch the computer play</label>
          <button id="lobby-back">Back</button>
        </div>
      </header>
      <div class="seats" id="seats"></div>
      <div class="seat-add"><button id="add-seat">+ Add a seat</button></div>
      <div class="play-row">
        <button id="play" class="primary play">Play</button>
        <p class="match-line" id="match-line"></p>
        <div class="not-yet" id="not-yet" hidden>
          <b>Not playable yet</b>
          <ul id="problems"></ul>
        </div>
      </div>`;
    root.querySelector('#lobby-back').onclick = () => send({ t: 'leaveLobby' });
    root.querySelector('#add-seat').onclick = () => send({ t: 'addSeat' });
    root.querySelector('#play').onclick = () => {
      // A browser plays nothing before a click, so the music starts on this one
      startMusic();
      send({ t: 'start', spectate: root.querySelector('#spectate').checked });
    };
    built = true;
  }
  const lobby = model.lobby;
  if (!lobby) {
    send({ t: 'lobby' });
    return;
  }
  renderFormats(root, lobby);
  renderSeats(root, lobby, model);
  renderVerdict(root, lobby);
}

function renderFormats(root, lobby) {
  const row = root.querySelector('#formats');
  if (row.childElementCount !== lobby.formats.length) {
    row.replaceChildren(...lobby.formats.map(f => {
      const b = document.createElement('button');
      b.className = 'format';
      b.dataset.format = f.id;
      b.textContent = f.name;
      b.onclick = () => send({ t: 'setFormat', format: f.id });
      return b;
    }));
  }
  for (const b of row.children) {
    b.setAttribute('aria-pressed', String(b.dataset.format === lobby.format));
  }
}

function renderSeats(root, lobby, model) {
  const list = root.querySelector('#seats');
  list.replaceChildren(...lobby.seats.map((seat, i) => plate(seat, i, lobby, model)));
  list.dataset.count = String(lobby.seats.length);
  root.querySelector('#add-seat').hidden = lobby.seats.length >= lobby.maxSeats;
}

// The seat kinds a netplay lobby can hold; offline shows only the first two
const KIND = { LOCAL: 'You', AI: 'Computer', OPEN: 'Open seat', REMOTE: 'Another player' };

function plate(seat, index, lobby, model) {
  const el = document.createElement('div');
  el.className = 'plate';
  const mine = seat.type === 'LOCAL';
  el.classList.toggle('mine', mine);
  el.innerHTML = `
    <button class="sleeve" title="Choose a sleeve"><img alt=""></button>
    <div class="plate-body">
      <div class="who">
        <button class="avatar" title="Choose an avatar"><img alt=""></button>
        <span class="who-name"></span>
        <span class="kind"></span>
        <button class="drop" title="Remove this seat" hidden>&times;</button>
      </div>
      <button class="deck-row"><span class="deck-name"></span><span class="deck-size"></span></button>
      <p class="seat-problem" hidden></p>
    </div>`;

  const sleeve = el.querySelector('.sleeve');
  // A deck's own card art wins over the numbered sleeve, exactly as it does in a match
  const art = seat.sleeveArt
    ? `/sleeveart?key=${encodeURIComponent(seat.sleeveArt)}`
    : sleeveUrl(seat.sleeve);
  setImage(sleeve.querySelector('img'), art);
  sleeve.querySelector('img').style.objectPosition = objectPosition(seat.sleeveOffset);
  sleeve.classList.toggle('card-art', !!seat.sleeveArt);
  sleeve.classList.toggle('empty', !seat.deck);
  sleeve.onclick = () => openSleevePicker(index, seat, send);

  const avatar = el.querySelector('.avatar');
  setImage(avatar.querySelector('img'), avatarUrl(seat.avatar));
  avatar.onclick = async () => {
    const chosen = await pickLook(`Choose an avatar for ${seat.name}`, model.looks?.avatarCount ?? 0, avatarUrl, seat.avatar, false);
    if (chosen !== null) {
      send({ t: 'setSeat', index, avatar: chosen });
    }
  };
  const name = el.querySelector('.who-name');
  name.textContent = seat.name || KIND[seat.type] || seat.type;
  if (mine) {
    name.contentEditable = 'plaintext-only';
    name.spellcheck = false;
    name.onblur = () => send({ t: 'setSeat', index, name: name.textContent.trim() });
  }
  el.querySelector('.kind').textContent = KIND[seat.type] ?? seat.type;

  const drop = el.querySelector('.drop');
  drop.hidden = mine || lobby.seats.length <= 2;
  drop.onclick = () => send({ t: 'removeSeat', index });

  const deck = el.querySelector('.deck-row');
  deck.querySelector('.deck-name').textContent = seat.deckName ?? 'Choose a deck';
  deck.querySelector('.deck-size').textContent = seat.deck ? seat.deckSize : '';
  deck.classList.toggle('unset', !seat.deck);
  deck.onclick = () => openDeckFinder(index, seat, model, send);

  const problem = el.querySelector('.seat-problem');
  problem.hidden = !seat.problem || !seat.deck;
  problem.textContent = seat.problem ?? '';
  return el;
}

/** The stored 0-1000 crop offset as a CSS position along whichever axis the art overflows. */
function objectPosition(offset) {
  const pct = `${(offset ?? 500) / 10}%`;
  return `${pct} ${pct}`;
}

function renderVerdict(root, lobby) {
  const problems = lobby.problems ?? [];
  const line = root.querySelector('#match-line');
  const panel = root.querySelector('#not-yet');
  root.querySelector('#play').disabled = !lobby.canStart;
  panel.hidden = lobby.canStart;
  line.hidden = !lobby.canStart;
  if (lobby.canStart) {
    const format = lobby.formats.find(f => f.id === lobby.format)?.name ?? lobby.format;
    line.textContent = `${format} · ${lobby.seats.length} players · Enter starts the match.`;
    return;
  }
  const list = root.querySelector('#problems');
  list.replaceChildren(...problems.map(p => {
    const li = document.createElement('li');
    li.textContent = p;
    return li;
  }));
}
