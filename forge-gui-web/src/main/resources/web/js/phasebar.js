import { deref, isLocal, me, opponents } from './model.js';
import { playerAvatarUrl } from './looks.js';

// A pill on the divider: whose turn it is, then the five phases with the current step named. Clicking it opens a
// grid of phase stops, one row for your turns and one for your opponents'.

function star() {
  let d = '';
  for (let i = 0; i < 16; i++) {
    const r = i % 2 ? 4.2 : 9.3;
    const a = Math.PI * i / 8 - Math.PI / 2;
    d += `${i ? 'L' : 'M'}${(12 + r * Math.cos(a)).toFixed(2)} ${(12 + r * Math.sin(a)).toFixed(2)}`;
  }
  return `<path d="${d}Z"/>`;
}

const GLYPHS = {
  upkeep: '<path d="M3 19h18"/><path d="M7 19a5 5 0 0 1 10 0"/><path d="M12 8.5v2.5M5.6 11.6l1.7 1.7M18.4 11.6l-1.7 1.7"/>',
  draw: '<rect x="4.5" y="7.5" width="10" height="13" rx="1.8"/><path d="M8.5 4.5h8.2a1.8 1.8 0 0 1 1.8 1.8v10.7"/>',
  main1: '<path d="M12 3.5l8.5 8.5-8.5 8.5L3.5 12z"/><circle cx="12" cy="12" r="1.8" fill="currentColor" stroke="none"/>',
  boc: '<path d="M5 5l14 14M19 5L5 19"/><path d="M14.5 18.5l4-4M5.5 14.5l4 4"/>',
  atk: '<path d="M19.5 4.5L8.5 15.5"/><path d="M19.5 4.5H15M19.5 4.5V9"/><path d="M6 13l5 5M8.5 15.5l-4 4"/>',
  blk: '<path d="M12 3.2l7.5 2.8v5.6c0 4.6-3.1 7.9-7.5 9.4-4.4-1.5-7.5-4.8-7.5-9.4V6z"/>',
  fs: '<path d="M13.5 2.8L5.5 13.2h6l-1 8 8-10.4h-6z"/>',
  dmg: star(),
  eoc: '<path d="M12 3v3M8 6h8"/><path d="M10.5 6v8.5l1.5 2.5 1.5-2.5V6"/><path d="M4.5 20.5h15"/>',
  main2: '<path d="M12 3.5l8.5 8.5-8.5 8.5L3.5 12z"/><circle cx="9.4" cy="12" r="1.55" fill="currentColor" stroke="none"/><circle cx="14.6" cy="12" r="1.55" fill="currentColor" stroke="none"/>',
  end: '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z"/>',
  cleanup: '<path d="M20 12a8 8 0 1 1-2.35-5.65L20 9"/><path d="M20 4.5V9h-4.5"/>',
  skip: '<path d="M4.5 6.5l5.5 5.5-5.5 5.5M11 6.5l5.5 5.5-5.5 5.5"/><path d="M19.5 6v12"/>',
  up: '<path d="M6.5 14.5l5.5-5.5 5.5 5.5"/>',
  down: '<path d="M6.5 9.5l5.5 5.5 5.5-5.5"/>',
};
const glyph = (name, size) => `<svg class="glyph" viewBox="0 0 24 24" style="width:${size}px;height:${size}px;stroke-width:${(1.3 * 24 / size).toFixed(2)}">${GLYPHS[name]}</svg>`;

// Untap takes no stop, as on desktop
const STEPS = [
  ['UPKEEP', 'upkeep', 'Upkeep', 'Upkeep'], ['DRAW', 'draw', 'Draw', 'Draw'], ['MAIN1', 'main1', 'Main 1', 'Main 1'],
  ['COMBAT_BEGIN', 'boc', 'Beginning of combat', 'Combat'], ['COMBAT_DECLARE_ATTACKERS', 'atk', 'Declare attackers', 'Attackers'],
  ['COMBAT_DECLARE_BLOCKERS', 'blk', 'Declare blockers', 'Blockers'], ['COMBAT_FIRST_STRIKE_DAMAGE', 'fs', 'First-strike damage', 'First strike'],
  ['COMBAT_DAMAGE', 'dmg', 'Combat damage', 'Damage'], ['COMBAT_END', 'eoc', 'End of combat', 'End combat'],
  ['MAIN2', 'main2', 'Main 2', 'Main 2'], ['END_OF_TURN', 'end', 'End step', 'End step'], ['CLEANUP', 'cleanup', 'Cleanup', 'Cleanup'],
];
const PHASES = [
  { glyph: 'upkeep', steps: [0, 1] }, { glyph: 'main1', steps: [2] }, { glyph: 'boc', steps: [3, 4, 5, 6, 7, 8] },
  { glyph: 'main2', steps: [9] }, { glyph: 'end', steps: [10, 11] },
];
const stepIndex = phase => STEPS.findIndex(s => s[0] === phase);

let open = false;
let wired = false;
let lastSend = () => {};
let last = null;

export function renderPhaseBar(model, g, send) {
  lastSend = send;
  last = [model, g];
  const root = document.getElementById('phase-strip');
  if (!wired) {
    root.innerHTML = '<div class="pill" role="button" tabindex="0" title="Phase stops"></div><div class="stops" hidden></div>';
    root.querySelector('.pill').onclick = () => {
      open = !open;
      renderPhaseBar(...last, lastSend);
    };
    document.addEventListener('keydown', e => {
      if (e.key === 'Escape' && open) {
        open = false;
        root.querySelector('.stops').hidden = true;
      }
    });
    document.addEventListener('mousedown', e => {
      if (open && !e.target.closest('#phase-strip')) {
        open = false;
        root.querySelector('.stops').hidden = true;
      }
    });
    wired = true;
  }
  const active = deref(model, g.PlayerTurn);
  const myTurn = !!active && isLocal(model, active);
  const opponent = opponents(model);
  const opponentLabel = opponent.length === 1 ? opponent[0].Name : 'Opponents';
  const step = stepIndex(g.Phase);
  // Untap (index -1) belongs to the beginning phase
  const phase = step < 0 ? 0 : PHASES.findIndex(p => p.steps.includes(step));
  const pill = root.querySelector('.pill');
  const avatar = active ? playerAvatarUrl(active) : '';
  const owner = `<span class="owner">${avatar ? `<img alt="" src="${avatar}">` : ''}<b>${myTurn ? 'Your turn' : escapeHtml(active?.Name ?? '')}</b><span class="turn">T${g.Turn ?? 0}${model.controls?.dayTime ? ` · ${model.controls.dayTime}` : ''}</span></span>`;
  const track = PHASES.map((p, n) => {
    if (n !== phase) return `<span class="phase ${n < phase ? 'past' : ''}">${glyph(p.glyph, 12)}<i></i></span>`;
    const name = step < 0 ? 'Untap' : STEPS[step][3];
    const pips = p.steps.length === 6
      ? `<span class="pips">${p.steps.map(i => `<i class="${i < step ? 'past' : i === step ? 'now' : ''}"></i>`).join('')}</span>` : '';
    return `<span class="phase current">${glyph(step < 0 ? p.glyph : STEPS[step][1], 12)}${name}${pips}</span>`;
  }).join('');
  const marker = model.controls?.marker;
  let until = '';
  if (marker) {
    const whose = marker.mine === myTurn ? '' : marker.mine ? 'your ' : `${escapeHtml(opponentLabel)}'s `;
    until = `<span class="until">${glyph('skip', 12)}until ${whose}${STEPS[stepIndex(marker.phase)]?.[2] ?? ''}</span>`;
  }
  pill.innerHTML = `${owner}<span class="track">${track}</span>${until}<span class="caret">${glyph(myTurn ? 'up' : 'down', 12)}</span>`;
  pill.classList.toggle('open', open);
  pill.classList.toggle('priority', !!me(model)?.HasPriority);

  const panel = root.querySelector('.stops');
  panel.hidden = !open;
  // Opens away from the player who is acting, so their half of the board stays visible
  panel.classList.toggle('above', myTurn);
  if (open) panel.innerHTML = stopsGrid(model, step, myTurn, opponentLabel);
  if (open) wireGrid(panel);
}

function stopsGrid(model, step, myTurn, opponentLabel) {
  const marker = model.controls?.marker;
  const rows = [
    { mine: false, label: `${escapeHtml(opponentLabel)}'s turns`, stops: new Set(model.controls?.otherStops ?? []), now: !myTurn },
    { mine: true, label: 'Your turns', stops: new Set(model.controls?.myStops ?? []), now: myTurn },
  ];
  const gap = i => (i > 0 && PHASES.findIndex(p => p.steps.includes(i)) !== PHASES.findIndex(p => p.steps.includes(i - 1))) ? '<td class="gap"></td>' : '';
  const head = '<tr><td></td>' + STEPS.map((s, i) => `${gap(i)}<th title="${s[2]}"><span class="head ${i === step ? 'current' : ''}">${glyph(s[1], 13)}</span><span class="name">${s[3]}</span></th>`).join('') + '</tr>';
  const body = rows.map(r => '<tr>' + `<td class="who">${r.label}${r.now ? ' <span class="now">now</span>' : ''}</td>` + STEPS.map((s, i) => {
    const marked = marker && marker.mine === r.mine && marker.phase === s[0];
    const on = r.stops.has(s[0]);
    const title = `${s[2]} · ${r.mine ? 'your turns' : `${opponentLabel}'s turns`}. Click: ${on ? 'clear the stop' : 'stop here'}. Right-click: pass priority until here.`;
    const cell = marked ? `<span class="skip">${glyph('skip', 13)}</span>` : `<span class="square ${on ? 'on' : ''} ${r.now && i === step ? 'current' : ''}"></span>`;
    return `${gap(i)}<td><button class="cell" data-phase="${s[0]}" data-mine="${r.mine}" title="${escapeHtml(title)}">${cell}</button></td>`;
  }).join('') + '</tr>').join('');
  return `<div class="title">Phase stops<span class="hint"><kbd>Esc</kbd> or click outside to close</span></div><table>${head}${body}</table>`
    + '<p class="foot">Click a square to stop there on that player\'s turns. Right-click to pass priority until that step.</p>';
}

function wireGrid(panel) {
  for (const b of panel.querySelectorAll('.cell')) {
    const msg = type => ({ t: type, phase: b.dataset.phase, mine: b.dataset.mine === 'true' });
    b.onclick = () => lastSend(msg('toggleStop'));
    b.oncontextmenu = e => {
      e.preventDefault();
      lastSend(msg('toggleMarker'));
    };
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
