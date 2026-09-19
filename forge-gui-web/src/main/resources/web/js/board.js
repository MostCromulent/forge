import { reconcile } from './render.js';
import { imageUrl } from './cards.js';
import { game, me, opponents, players, zone, deref, stateOf, isLocal } from './model.js';
import { renderHand } from './hand.js';
import { renderZones, togglePile } from './zones.js';
import { renderBattlefield } from './battlefield.js';
import { hoverCard, hoverPlayer } from './detail.js';
import { renderStack } from './stack.js';
import { renderPhaseBar } from './phasebar.js';
import { playerAvatarUrl, playerSleeveUrl, cssUrl, ROBOT_ICON } from './looks.js';

const MANA = [[1, 'W'], [2, 'U'], [4, 'B'], [8, 'R'], [16, 'G'], [32, 'C']];

export function renderMatch(model, send) {
  const g = game(model);
  if (!g) return;
  const select = el => send({ t: 'selectCard', key: Number(el.dataset.key) });
  // Attachments can cross players (an aura on an opponent's creature), so slots are built from every battlefield
  const onField = players(model).flatMap(p => zone(model, p, 'Battlefield'));
  renderSeat(document.getElementById('opponent'), model, opponents(model)[0], onField, send, select);
  renderSeat(document.getElementById('me'), model, me(model), onField, send, select);
  renderPhaseBar(model, g, send);
  renderStack(model);
  renderHand(model, me(model), select);
  renderZones(model, select);
  renderGameOver(model, g, send);
}

function renderSeat(root, model, player, onField, send, select) {
  if (!player) {
    root.replaceChildren();
    return;
  }
  if (!root.firstChild) {
    root.innerHTML = `
      <div class="player">
        <div class="avatar"><img class="portrait" alt="" draggable="false"><span class="initial"></span><span class="ai-badge" title="Computer player">${ROBOT_ICON}</span><span class="life"></span></div>
        <div class="name"></div>
        <div class="player-counters"></div>
        <div class="emblems"></div>
        <div class="zone-tiles"></div>
        <div class="mana"></div>
      </div>
      <div class="battlefield"><div class="row lands"></div><div class="row permanents"></div></div>`;
    const avatarEl = root.querySelector('.avatar');
    avatarEl.onclick = () => send({ t: 'selectPlayer', key: Number(root.dataset.player) });
    avatarEl.addEventListener('mouseenter', () => hoverPlayer(Number(root.dataset.player)));
    avatarEl.addEventListener('mouseleave', () => hoverPlayer(null));
  }
  root.dataset.player = player.$key;
  const avatar = root.querySelector('.avatar');
  root.querySelector('.initial').textContent = (player.Name ?? '?').slice(0, 1).toUpperCase();
  const portrait = root.querySelector('.portrait');
  const portraitSrc = playerAvatarUrl(player);
  if ((portrait.getAttribute('src') ?? '') !== portraitSrc) {
    portrait.hidden = true;
    portrait.onload = () => { portrait.hidden = false; };
    if (portraitSrc) portrait.src = portraitSrc;
    else portrait.removeAttribute('src');
  }
  avatar.classList.toggle('ai', !!player.IsAI);
  root.style.setProperty('--sleeve', cssUrl(playerSleeveUrl(player)));
  root.querySelector('.life').textContent = player.Life ?? 0;
  root.querySelector('.name').textContent = player.Name ?? '';
  avatar.classList.toggle('highlighted', (model.prompt?.highlighted ?? []).includes(player.$key));
  avatar.classList.toggle('selectable', (model.prompt?.selectablePlayers ?? []).some(r => r?.ref === player.$key));
  avatar.classList.toggle('active', game(model)?.PlayerTurn?.ref === player.$key);
  renderZoneTiles(root.querySelector('.zone-tiles'), model, player);
  renderManaPool(root.querySelector('.mana'), player, isLocal(model, player), send);
  const badges = Object.entries(player.Counters ?? {}).map(([name, n]) => ({ key: name, text: `${name.toLowerCase()} ${n}`, title: '' }));
  for (const { card, value } of player.CommanderDamage ?? []) {
    const name = stateOf(model, deref(model, card) ?? {}).Name ?? 'Commander';
    if (value > 0) badges.push({ key: `cmdr-${card.ref}`, text: `${name} ${value}`, title: `Commander damage from ${name}` });
  }
  reconcile(root.querySelector('.player-counters'), badges, b => b.key,
    () => {
      const el = document.createElement('span');
      el.className = 'player-counter';
      return el;
    },
    (el, b) => {
      el.textContent = b.text;
      el.title = b.title;
      el.classList.toggle('commander-damage', !!b.title);
    });
  renderEmblems(root.querySelector('.emblems'), model, zone(model, player, 'Command'), select);
  renderBattlefield(root, model, zone(model, player, 'Battlefield'), onField, select);
}

// Clicking your own mana pays with that colour, as on desktop
function renderManaPool(root, player, own, send) {
  const pool = MANA.filter(([bit]) => player.Mana?.[bit]);
  reconcile(root, pool, ([bit]) => bit,
    ([bit]) => {
      const el = document.createElement('button');
      el.className = 'mana-button';
      el.onclick = () => send({ t: 'useMana', color: bit });
      return el;
    },
    (el, [bit, sym]) => {
      el.textContent = `${sym} ${player.Mana[bit]}`;
      el.disabled = !own;
    });
}

// Your own hand is laid out along the bottom, so only opponents get a Hand tile
function renderZoneTiles(root, model, player) {
  const zones = isLocal(model, player) ? ['Library', 'Graveyard', 'Exile'] : ['Hand', 'Library', 'Graveyard', 'Exile'];
  reconcile(root, zones, z => z,
    zoneName => {
      const el = document.createElement('button');
      el.className = 'zone-tile';
      el.innerHTML = '<img alt="" draggable="false"><span class="zone-name"></span><span class="zone-count"></span>';
      el.querySelector('.zone-name').textContent = zoneName;
      el.querySelector('img').addEventListener('error', e => { e.target.hidden = true; });
      el.onclick = () => togglePile(Number(el.closest('.seat').dataset.player), zoneName);
      // The hover data sits on the image: the tile's own data-key is how the render finds it again
      el.addEventListener('mouseenter', () => hoverCard(el.querySelector('img')));
      el.addEventListener('mouseleave', () => hoverCard(null));
      return el;
    },
    (el, zoneName) => {
      const cards = zone(model, player, zoneName);
      // New cards go to the end of the graveyard and exile lists, so the last is on top
      const top = zoneName === 'Graveyard' || zoneName === 'Exile' ? cards[cards.length - 1] : undefined;
      const state = top ? stateOf(model, top) : {};
      const src = top && model.visible.has(top.$key) && state.ImageKey ? imageUrl(state.ImageKey) : '';
      const img = el.querySelector('img');
      if (img.getAttribute('src') !== src) {
        img.hidden = !src;
        if (src) img.src = src;
      }
      img.dataset.key = top?.$key ?? '';
      img.dataset.zoom = src;
      el.classList.toggle('back', (zoneName === 'Library' || zoneName === 'Hand') && cards.length > 0);
      el.classList.toggle('empty', cards.length === 0);
      el.querySelector('.zone-count').textContent = cards.length;
    });
}

// The command zone: the monarch, the initiative, emblems and commanders, shown as round tokens beside the player
function renderEmblems(root, model, cards, select) {
  reconcile(root, cards, c => c.$key,
    () => {
      const el = document.createElement('div');
      el.className = 'emblem';
      el.innerHTML = '<img alt="" draggable="false"><span class="initials"></span>';
      el.querySelector('img').addEventListener('error', () => el.classList.add('noimg'));
      el.onclick = () => select(el);
      el.addEventListener('mouseenter', () => hoverCard(el));
      el.addEventListener('mouseleave', () => hoverCard(null));
      return el;
    },
    (el, card) => {
      const state = stateOf(model, card);
      const src = model.visible.has(card.$key) && state.ImageKey ? imageUrl(state.ImageKey) : '';
      const img = el.querySelector('img');
      if (img.getAttribute('src') !== src) {
        el.classList.toggle('noimg', !src);
        if (src) img.src = src;
      }
      el.dataset.zoom = src;
      el.title = state.Name ?? '';
      const words = (state.Name ?? '').replace(/^(The|Emblem) /, '').split(/[\s-]+/).filter(w => /^\w/.test(w));
      el.querySelector('.initials').textContent = words.map(w => w[0]).join('').slice(0, 2).toUpperCase();
      el.classList.toggle('selectable', (model.prompt?.selectable ?? []).some(r => r?.ref === card.$key));
    });
}

function renderGameOver(model, g, send) {
  const root = document.getElementById('game-over');
  root.hidden = !model.gameOver;
  if (!model.gameOver) return;
  const matchOver = !!g.MatchOver;
  root.innerHTML = '<div class="panel"><h2></h2><div class="actions"></div></div>';
  root.querySelector('h2').textContent = g.WinningPlayerName ? `${g.WinningPlayerName} wins` : 'Game over';
  const actions = root.querySelector('.actions');
  const add = (label, primary, onClick) => {
    const b = document.createElement('button');
    b.textContent = label;
    if (primary) b.className = 'primary';
    b.onclick = onClick;
    actions.append(b);
  };
  if (!matchOver) add('Next game', true, () => send({ t: 'nextGame', decision: 'CONTINUE' }));
  add(matchOver ? 'Back to start' : 'Quit match', matchOver, () => {
    if (!matchOver) send({ t: 'nextGame', decision: 'QUIT' });
    send({ t: 'leave' });
  });
}
