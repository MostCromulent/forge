import { avatarUrl, sleeveUrl, pickLook } from './looks.js';
import { startMusic } from './audio.js';

let built = false;
// Avatar and sleeve per seat: [you, opponent]
let looks = null;

export function renderStart(model, send) {
  const root = document.getElementById('start');
  if (!built) {
    root.innerHTML = `
      <div class="start-card">
        <h1>Forge</h1>
        <label>Your name <input id="player-name" maxlength="40"></label>
        <div class="seat-row"><div class="looks" data-seat="0"></div><label>Your deck <select id="player-deck"></select></label></div>
        <div class="seat-row"><div class="looks" data-seat="1"></div><label>Opponent deck <select id="ai-deck"></select></label></div>
        <p class="hint">Click the avatar or sleeve to choose; right-click to pick one at random.</p>
        <label class="spectate"><input id="spectate" type="checkbox"> Spectate: two AI players play instead</label>
        <p id="start-error" class="error" hidden></p>
        <div class="actions"><button id="quit">Quit</button><button id="play" class="primary">Play</button></div>
      </div>`;
    root.querySelector('#play').onclick = () => {
      // A browser plays nothing before a click, so the music starts on this one
      startMusic();
      send({
        t: 'start',
        playerName: root.querySelector('#player-name').value,
        playerDeck: root.querySelector('#player-deck').value,
        aiDeck: root.querySelector('#ai-deck').value,
        avatars: looks?.avatars,
        sleeves: looks?.sleeves,
        spectate: root.querySelector('#spectate').checked,
      });
    };
    root.querySelector('#quit').onclick = () => send({ t: 'quit' });
    built = true;
  }
  const name = root.querySelector('#player-name');
  if (!name.value) name.value = model.playerName;
  if (!looks && model.looks) {
    looks = { avatars: [...model.looks.avatars], sleeves: [...model.looks.sleeves] };
  }
  for (const el of root.querySelectorAll('.looks')) renderLooks(el, Number(el.dataset.seat), model.looks);
  fillDecks(root.querySelector('#player-deck'), model.decks);
  fillDecks(root.querySelector('#ai-deck'), model.decks);
  const err = root.querySelector('#start-error');
  err.hidden = !model.error;
  err.textContent = model.error ?? '';
}

function renderLooks(root, seat, available) {
  if (!looks || !available) return;
  if (!root.firstChild) {
    root.innerHTML = '<button class="look avatar-look" title="Avatar"><img alt=""></button><button class="look sleeve-look" title="Sleeve"><img alt=""></button>';
    const who = seat === 0 ? 'your' : 'the opponent\'s';
    const wire = (button, kind, count, urlOf) => {
      button.onclick = async () => {
        const avatar = kind === 'avatars';
        const chosen = await pickLook(`Choose ${who} ${avatar ? 'avatar' : 'sleeve'}`, count, urlOf, looks[kind][seat], !avatar);
        if (chosen !== null) {
          looks[kind][seat] = chosen;
          renderLooks(root, seat, available);
        }
      };
      button.oncontextmenu = e => {
        e.preventDefault();
        looks[kind][seat] = Math.floor(Math.random() * count);
        renderLooks(root, seat, available);
      };
    };
    wire(root.querySelector('.avatar-look'), 'avatars', available.avatarCount, avatarUrl);
    wire(root.querySelector('.sleeve-look'), 'sleeves', available.sleeveCount, sleeveUrl);
  }
  setSrc(root.querySelector('.avatar-look img'), avatarUrl(looks.avatars[seat]));
  setSrc(root.querySelector('.sleeve-look img'), sleeveUrl(looks.sleeves[seat]));
}

function setSrc(img, src) {
  if (img.getAttribute('src') !== src) img.src = src;
}

function fillDecks(select, decks) {
  if (select.options.length === decks.length) return;
  const chosen = select.value;
  select.replaceChildren(...decks.map(d => {
    const o = new Option(d.problem ? `${d.name} (not legal)` : d.name, d.key);
    o.title = d.problem ?? '';
    return o;
  }));
  if (chosen) select.value = chosen;
}
