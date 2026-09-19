import { reconcile } from './render.js';
import { imageUrl } from './cards.js';
import { game, me, opponents, zone, deref, stateOf, isLocal } from './model.js';
import { renderHand } from './hand.js';
import { renderZones, togglePile } from './zones.js';
import { renderBattlefield } from './battlefield.js';
import { hoverCard } from './detail.js';
import { hoverStackItem, stackTargets } from './overlay.js';

const PHASES = [
  ['UPKEEP', 'Upkeep'], ['DRAW', 'Draw'], ['MAIN1', 'Main 1'], ['COMBAT_BEGIN', 'Combat'],
  ['COMBAT_DECLARE_ATTACKERS', 'Attack'], ['COMBAT_DECLARE_BLOCKERS', 'Block'], ['COMBAT_DAMAGE', 'Damage'],
  ['MAIN2', 'Main 2'], ['END_OF_TURN', 'End'],
];
const MANA = [[1, 'W'], [2, 'U'], [4, 'B'], [8, 'R'], [16, 'G'], [32, 'C']];

export function renderMatch(model, send) {
  const g = game(model);
  if (!g) return;
  const select = el => send({ t: 'selectCard', key: Number(el.dataset.key) });
  renderSeat(document.getElementById('opponent'), model, opponents(model)[0], send, select);
  renderSeat(document.getElementById('me'), model, me(model), send, select);
  renderPhaseStrip(model, g, send);
  renderStack(model);
  renderHand(model, me(model), select);
  renderZones(model, select);
  renderGameOver(model, g, send);
}

function renderSeat(root, model, player, send, select) {
  if (!player) {
    root.replaceChildren();
    return;
  }
  if (!root.firstChild) {
    root.innerHTML = `
      <div class="player">
        <div class="avatar"><span class="initial"></span><span class="life"></span></div>
        <div class="name"></div>
        <div class="player-counters"></div>
        <div class="emblems"></div>
        <div class="zone-tiles"></div>
        <div class="mana"></div>
      </div>
      <div class="battlefield"><div class="row lands"></div><div class="row permanents"></div></div>`;
    root.querySelector('.avatar').onclick = () => send({ t: 'selectPlayer', key: Number(root.dataset.player) });
  }
  root.dataset.player = player.$key;
  const avatar = root.querySelector('.avatar');
  root.querySelector('.initial').textContent = (player.Name ?? '?').slice(0, 1).toUpperCase();
  root.querySelector('.life').textContent = player.Life ?? 0;
  root.querySelector('.name').textContent = player.Name ?? '';
  avatar.classList.toggle('highlighted', (model.prompt?.highlighted ?? []).includes(player.$key));
  avatar.classList.toggle('active', game(model)?.PlayerTurn?.ref === player.$key);
  renderZoneTiles(root.querySelector('.zone-tiles'), model, player);
  root.querySelector('.mana').textContent = MANA.map(([bit, sym]) => (player.Mana?.[bit] ? `${sym}${player.Mana[bit]}` : '')).filter(Boolean).join(' ');
  reconcile(root.querySelector('.player-counters'), Object.entries(player.Counters ?? {}), ([name]) => name,
    () => {
      const el = document.createElement('span');
      el.className = 'player-counter';
      return el;
    },
    (el, [name, n]) => { el.textContent = `${name.toLowerCase()} ${n}`; });
  renderEmblems(root.querySelector('.emblems'), model, zone(model, player, 'Command'), select);
  renderBattlefield(root, model, zone(model, player, 'Battlefield'), select);
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
      el.addEventListener('mouseenter', () => hoverCard(el));
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
      el.dataset.key = top?.$key ?? '';
      el.dataset.zoom = src;
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

// One row of phase stops for the local player's turns and one for everyone else's; clicking a phase toggles its stop
function renderPhaseStrip(model, g, send) {
  const root = document.getElementById('phase-strip');
  if (!root.firstChild) {
    const row = (mine, label) => `<div class="phase-row" data-mine="${mine}"><span class="whose">${label}</span>`
      + PHASES.map(([id, name]) => `<button class="phase" data-phase="${id}">${name}</button>`).join('') + '</div>';
    root.innerHTML = row(false, 'Opponent') + row(true, 'You') + '<span class="turn"></span>';
    for (const b of root.querySelectorAll('.phase')) {
      b.onclick = () => send({ t: 'toggleStop', phase: b.dataset.phase, mine: b.parentElement.dataset.mine === 'true' });
    }
  }
  const active = deref(model, g.PlayerTurn);
  const myTurn = !!active && isLocal(model, active);
  for (const rowEl of root.querySelectorAll('.phase-row')) {
    const mine = rowEl.dataset.mine === 'true';
    const stops = new Set(model.controls?.[mine ? 'myStops' : 'otherStops'] ?? []);
    rowEl.classList.toggle('active', mine === myTurn);
    for (const b of rowEl.querySelectorAll('.phase')) {
      const stop = stops.has(b.dataset.phase);
      b.classList.toggle('stop', stop);
      b.classList.toggle('current', mine === myTurn && b.dataset.phase === g.Phase);
      b.title = `${stop ? 'Stops' : 'Skips'} here on ${mine ? 'your' : 'opponents\''} turns. Click to toggle.`;
    }
  }
  root.querySelector('.turn').textContent = `Turn ${g.Turn ?? 0}${active ? ` · ${active.Name}` : ''}`;
}

function renderStack(model) {
  const items = (game(model)?.Stack ?? []).map(r => model.objects.get(r.ref)).filter(Boolean);
  reconcile(document.getElementById('stack'), items, i => i.$key,
    () => {
      const el = document.createElement('div');
      el.className = 'stack-item';
      el.innerHTML = '<img class="thumb" alt="" draggable="false"><div class="body"><div class="who"></div><div class="desc"></div><div class="targets"></div></div>';
      const thumb = el.querySelector('.thumb');
      thumb.addEventListener('error', () => { thumb.hidden = true; });
      thumb.addEventListener('mouseenter', () => hoverCard(thumb));
      thumb.addEventListener('mouseleave', () => hoverCard(null));
      el.addEventListener('mouseenter', () => hoverStackItem(Number(el.dataset.key)));
      el.addEventListener('mouseleave', () => hoverStackItem(null));
      return el;
    },
    (el, item) => {
      const source = deref(model, item.SourceCard);
      const state = source ? stateOf(model, source) : {};
      const src = source && model.visible.has(source.$key) && state.ImageKey ? imageUrl(state.ImageKey) : '';
      const thumb = el.querySelector('.thumb');
      if (thumb.getAttribute('src') !== src) {
        thumb.hidden = !src;
        if (src) thumb.src = src;
      }
      thumb.dataset.key = source?.$key ?? '';
      thumb.dataset.zoom = src;
      const caster = deref(model, item.ActivatingPlayer);
      el.querySelector('.who').textContent = caster?.Name ?? '';
      el.querySelector('.desc').textContent = item.Description ?? '';
      const targets = stackTargets(model, item).map(t => t.Name ?? stateOf(model, t).Name ?? '?');
      el.querySelector('.targets').textContent = targets.length ? `→ ${targets.join(', ')}` : '';
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
