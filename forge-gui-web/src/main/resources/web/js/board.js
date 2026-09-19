import { reconcile } from './render.js';
import { createCard, updateCard } from './cards.js';
import { game, me, opponents, zone, derefAll, deref, stateOf } from './model.js';
import { renderHand } from './hand.js';
import { renderZones, togglePile } from './zones.js';

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
  renderPhaseStrip(model, g);
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
        <div class="piles"><button class="pile library"></button><button class="pile graveyard"></button><button class="pile exile"></button></div>
        <div class="mana"></div>
      </div>
      <div class="battlefield"><div class="row lands"></div><div class="row permanents"></div></div>`;
    root.querySelector('.avatar').onclick = () => send({ t: 'selectPlayer', key: Number(root.dataset.player) });
    for (const [cls, zoneName] of [['graveyard', 'Graveyard'], ['exile', 'Exile'], ['library', 'Library']]) {
      root.querySelector(`.pile.${cls}`).onclick = () => togglePile(Number(root.dataset.player), zoneName);
    }
  }
  root.dataset.player = player.$key;
  const avatar = root.querySelector('.avatar');
  root.querySelector('.initial').textContent = (player.Name ?? '?').slice(0, 1).toUpperCase();
  root.querySelector('.life').textContent = player.Life ?? 0;
  root.querySelector('.name').textContent = player.Name ?? '';
  avatar.classList.toggle('highlighted', (model.prompt?.highlighted ?? []).includes(player.$key));
  avatar.classList.toggle('active', game(model)?.PlayerTurn?.ref === player.$key);
  root.querySelector('.library').textContent = `Library ${zone(model, player, 'Library').length}`;
  root.querySelector('.graveyard').textContent = `Graveyard ${zone(model, player, 'Graveyard').length}`;
  root.querySelector('.exile').textContent = `Exile ${zone(model, player, 'Exile').length}`;
  root.querySelector('.mana').textContent = MANA.map(([bit, sym]) => (player.Mana?.[bit] ? `${sym}${player.Mana[bit]}` : '')).filter(Boolean).join(' ');
  const battlefield = zone(model, player, 'Battlefield');
  const isLand = c => /Land/.test(stateOf(model, c).Type ?? '');
  const draw = (rowEl, cards) => reconcile(rowEl, cards, c => c.$key, () => createCard(select), (e, c) => updateCard(e, model, c));
  draw(root.querySelector('.lands'), battlefield.filter(isLand));
  draw(root.querySelector('.permanents'), battlefield.filter(c => !isLand(c)));
}

function renderPhaseStrip(model, g) {
  const root = document.getElementById('phase-strip');
  if (!root.firstChild) {
    root.innerHTML = PHASES.map(([id, label]) => `<span data-phase="${id}">${label}</span>`).join('') + '<span class="turn"></span>';
  }
  for (const el of root.querySelectorAll('[data-phase]')) el.classList.toggle('current', el.dataset.phase === g.Phase);
  const active = deref(model, g.PlayerTurn);
  root.querySelector('.turn').textContent = `Turn ${g.Turn ?? 0}${active ? ` · ${active.Name}` : ''}`;
}

function renderStack(model) {
  reconcile(document.getElementById('stack'), derefAll(model, game(model)?.Stack), i => i.$key,
    () => {
      const el = document.createElement('div');
      el.className = 'stack-item';
      return el;
    },
    (el, item) => { el.textContent = item.Description ?? ''; });
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
