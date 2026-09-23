// Match setup. Each seat is a plate anchored by its deck's sleeve. The sleeve stands for the deck, so it is
// the largest thing on the plate and clicking it chooses the deck, empty or not. Which sleeve to wear is a
// property of a deck you already have, so it hangs off a small button in the corner of a filled one.
// Seats are added, never presented as empty slots waiting to be filled.
//
// A seat's type is the one a netplay lobby slot carries. The browser reaches the game through a client even
// when it hosts it, so your own seat arrives as REMOTE and is recognised by its "mine" flag, not its type.

import { sleeveUrl, avatarUrl, pickLook } from './looks';
import { setImage } from './images';
import { openDeckFinder } from './deckfinder';
import { openSleevePicker } from './sleeves';
import { startMusic } from './audio';
import { wireChatInput, paintChat } from './chat';
import { q } from './dom';
import type { Model } from './model';
import type { Address, LobbyTable, Seat, Send } from './protocol';

let built = false;
let send: Send = () => {};
// Finding the external address is a web request on the host, so it is asked for once per lobby
let askedAddresses = false;

export function renderLobby(model: Model, sendFn: Send): void {
  send = sendFn;
  const root = document.getElementById('lobby') as HTMLElement;
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
      <div class="lobby-main">
      <div class="seats" id="seats"></div>
      <div class="seat-add"><button id="add-seat">+ Add a seat</button></div>
      <div class="play-row">
        <button id="play" class="primary play">Play</button>
        <p class="match-line" id="match-line"></p>
        <div class="not-yet" id="not-yet" hidden>
          <b>Not playable yet</b>
          <ul id="problems"></ul>
        </div>
      </div>
      <div class="lobby-net" id="lobby-net" hidden>
        <section class="share" id="share" hidden>
          <h3>Others join at</h3>
          <div class="share-list" id="share-list"></div>
        </section>
        <section class="chat">
          <div class="chat-log" id="lobby-chat-log"></div>
          <input id="lobby-chat-in" type="text" placeholder="Say something" maxlength="240">
        </section>
      </div>
      </div>`;
    q(root, '#lobby-back').onclick = () => send({ t: 'leaveLobby' });
    wireChatInput(q<HTMLInputElement>(root, '#lobby-chat-in'));
    q(root, '#add-seat').onclick = () => send({ t: 'addSeat' });
    q(root, '#play').onclick = () => {
      // A browser plays nothing before a click, so the music starts on this one
      startMusic();
      send({ t: 'start', spectate: q<HTMLInputElement>(root, '#spectate').checked });
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
  renderNet(root, lobby, model);
}

function renderNet(root: HTMLElement, lobby: LobbyTable, model: Model): void {
  // A game only this machine can reach has nothing to share and nobody to talk to
  q(root, '#lobby-net').hidden = !lobby.shareable && lobby.host;
  // The table belongs to the host, so a joined client changes only its own seat, and has no menu to go back to
  q<HTMLButtonElement>(root, '#add-seat').disabled = !lobby.host;
  q(root, '#lobby-back').hidden = !lobby.host;
  for (const b of root.querySelectorAll<HTMLButtonElement>('.format')) b.disabled = !lobby.host;
  const share = q(root, '#share');
  share.hidden = !lobby.shareable;
  if (lobby.shareable && !model.addresses && !askedAddresses) {
    askedAddresses = true;
    send({ t: 'addresses' });
  }
  if (!lobby.shareable) {
    askedAddresses = false;
  }
  if (lobby.shareable) {
    renderAddresses(root, model.addresses ?? []);
  }
  paintChat(q(root, '#lobby-chat-log'));
}

function renderAddresses(root: HTMLElement, list: Address[]): void {
  const box = q(root, '#share-list');
  if (box.childElementCount === list.length && box.dataset.first === (list[0]?.url ?? '')) {
    return;
  }
  box.dataset.first = list[0]?.url ?? '';
  box.replaceChildren(...list.map(a => {
    const row = document.createElement('button');
    row.className = 'share-row';
    row.innerHTML = `<span class="share-label"></span><code class="share-url"></code><span class="share-copy">Copy</span>`;
    q(row, '.share-label').textContent = a.label;
    q(row, '.share-url').textContent = a.url;
    row.onclick = async () => {
      await navigator.clipboard.writeText(a.url);
      q(row, '.share-copy').textContent = 'Copied';
    };
    return row;
  }));
  if (!list.length) {
    box.textContent = 'Working out your address…';
  }
}

function renderFormats(root: HTMLElement, lobby: LobbyTable): void {
  const row = q(root, '#formats');
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
  for (const b of row.children as HTMLCollectionOf<HTMLElement>) {
    b.setAttribute('aria-pressed', String(b.dataset.format === lobby.format));
  }
}

function renderSeats(root: HTMLElement, lobby: LobbyTable, model: Model): void {
  const list = q(root, '#seats');
  list.replaceChildren(...lobby.seats.map((seat, i) => plate(seat, i, lobby, model)));
  list.dataset.count = String(lobby.seats.length);
  q(root, '#add-seat').hidden = lobby.seats.length >= lobby.maxSeats;
}

// The seat kinds a netplay lobby can hold; offline shows only the first two
const KIND: Record<string, string> = { LOCAL: 'You', AI: 'Computer', OPEN: 'Open seat', REMOTE: 'Another player' };

function plate(seat: Seat, index: number, lobby: LobbyTable, model: Model): HTMLElement {
  const el = document.createElement('div');
  el.className = 'plate';
  // Your own seat is the one the server dealt you, whatever type it wears on the host's side
  const mine = seat.mine;
  el.classList.toggle('mine', mine);
  el.classList.toggle('waiting', seat.type === 'OPEN');
  el.innerHTML = `
    <div class="sleeve-slot">
      <button class="sleeve" title="Choose a deck"><img alt=""></button>
      <button class="sleeve-style" title="Choose a sleeve" hidden>Sleeve</button>
    </div>
    <div class="plate-body">
      <div class="who">
        <button class="avatar" title="Choose an avatar"><img alt=""></button>
        <span class="who-name"></span>
        <button class="kind"></button>
        <button class="drop" title="Remove this seat" hidden>&times;</button>
      </div>
      <button class="deck-row"><span class="pips"></span><span class="deck-name"></span><span class="deck-size"></span></button>
      <p class="seat-problem" hidden></p>
    </div>`;

  const waiting = seat.type === 'OPEN';
  const sleeve = q<HTMLButtonElement>(el, '.sleeve');
  // Nothing is sleeved until a deck is chosen, so the slot stands empty rather than showing a sleeve
  sleeve.classList.toggle('empty', !seat.deck);
  sleeve.dataset.label = seat.mayEdit ? 'Choose a deck' : (waiting ? '' : 'No deck');
  sleeve.classList.toggle('card-art', !!seat.sleeveArt);
  const img = q<HTMLImageElement>(sleeve, 'img');
  if (seat.deck) {
    // A deck's own card art wins over the numbered sleeve, exactly as it does in a match
    setImage(img, seat.sleeveArt ? `/sleeveart?key=${encodeURIComponent(seat.sleeveArt)}` : sleeveUrl(seat.sleeve));
    img.style.objectPosition = objectPosition(seat.sleeveOffset);
    img.hidden = false;
  } else {
    img.hidden = true;
  }
  sleeve.disabled = !seat.mayEdit;
  sleeve.onclick = () => openDeckFinder(index, seat, model, send);
  // A sleeve is worn by a deck, so there is nothing to choose until there is one
  const style = q(el, '.sleeve-style');
  style.hidden = !seat.deck || !seat.mayEdit;
  style.onclick = () => openSleevePicker(index, seat, send);

  const avatar = q<HTMLButtonElement>(el, '.avatar');
  // A seat nobody has taken has no face to show
  avatar.hidden = waiting;
  setImage(q<HTMLImageElement>(avatar, 'img'), avatarUrl(seat.avatar));
  avatar.disabled = !seat.mayEdit;
  avatar.onclick = async () => {
    const chosen = await pickLook(`Choose an avatar for ${seat.name}`, model.looks?.avatarCount ?? 0, avatarUrl, seat.avatar, false);
    if (chosen !== null) {
      send({ t: 'setSeat', index, avatar: chosen });
    }
  };
  const name = q(el, '.who-name');
  // A seat nobody holds has no name of its own, and its kind beside it would only say the same thing twice
  name.hidden = !seat.name && !mine;
  name.textContent = seat.name || KIND[seat.type] || seat.type;
  if (mine) {
    name.contentEditable = 'plaintext-only';
    name.spellcheck = false;
    name.onblur = () => send({ t: 'setSeat', index, name: (name.textContent ?? '').trim() });
  }
  // The host turns a seat between a computer and one someone can join; everyone else only reads it
  const kind = q<HTMLButtonElement>(el, '.kind');
  kind.textContent = mine ? KIND.LOCAL : (KIND[seat.type] ?? seat.type);
  const swappable = lobby.host && !mine && (seat.type === 'AI' || seat.type === 'OPEN');
  kind.disabled = !swappable;
  kind.title = swappable ? 'Swap between a computer and an open seat' : '';
  kind.onclick = () => send({ t: seat.type === 'AI' ? 'openSeat' : 'aiSeat', index });

  const drop = q(el, '.drop');
  drop.hidden = mine || !lobby.host || lobby.seats.length <= 2;
  drop.onclick = () => send({ t: 'removeSeat', index });

  const deck = q<HTMLButtonElement>(el, '.deck-row');
  q(deck, '.pips').innerHTML = [...(seat.colors ?? '')].map(c => `<i class="pip pip-${c}">${c}</i>`).join('');
  q(deck, '.deck-name').textContent = seat.deckName ?? (waiting ? 'Waiting for a player' : '');
  q(deck, '.deck-size').textContent = seat.deck ? String(seat.deckSize) : '';
  deck.classList.toggle('unset', !seat.deck);
  // With no deck the sleeve above already offers to choose one, so an empty row would only repeat it
  deck.hidden = !seat.deck && !waiting;
  deck.disabled = !seat.mayEdit;
  deck.onclick = () => openDeckFinder(index, seat, model, send);

  const problem = q(el, '.seat-problem');
  problem.hidden = !seat.problem || !seat.deck;
  problem.textContent = seat.problem ?? '';
  return el;
}

/** The stored 0-1000 crop offset as a CSS position along whichever axis the art overflows. */
function objectPosition(offset: number | undefined): string {
  const pct = `${(offset ?? 500) / 10}%`;
  return `${pct} ${pct}`;
}

function renderVerdict(root: HTMLElement, lobby: LobbyTable): void {
  const problems = lobby.problems ?? [];
  const line = q(root, '#match-line');
  const panel = q(root, '#not-yet');
  q<HTMLButtonElement>(root, '#play').disabled = !lobby.canStart;
  // Only the host can start, so a joined client is told what it is waiting for rather than shown a dead button
  q(root, '#play').hidden = !lobby.host;
  q(root, '.spectate').hidden = !lobby.host;
  if (!lobby.host) {
    panel.hidden = true;
    line.hidden = false;
    line.textContent = problems.length ? problems[0] : 'Waiting for the host to start the match.';
    return;
  }
  panel.hidden = lobby.canStart;
  line.hidden = !lobby.canStart;
  if (lobby.canStart) {
    const format = lobby.formats.find(f => f.id === lobby.format)?.name ?? lobby.format;
    line.textContent = `${format} · ${lobby.seats.length} players · Enter starts the match.`;
    return;
  }
  const list = q(root, '#problems');
  list.replaceChildren(...problems.map(p => {
    const li = document.createElement('li');
    li.textContent = p;
    return li;
  }));
}
