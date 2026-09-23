import { reconcile } from './render';
import { deref, isLocal, me, opponents, players, type Model } from './model';
import { playerAvatarUrl } from './looks';
import { q } from './dom';
import { changeUi, ui } from './ui';
import type { Actions } from './actions';
import type { GameView, PhaseType, TurnMarker } from './protocol';

// A pill on the divider: whose turn it is, then the five phases with the current step named. The track is a
// read-out and never changes shape under the cursor; clicking the pill opens the grid of phase stops, one row
// for your turns and one for your opponents', which is where a stop is set.

function star(): string {
  let d = '';
  for (let i = 0; i < 16; i++) {
    const r = i % 2 ? 4.2 : 9.3;
    const a = Math.PI * i / 8 - Math.PI / 2;
    d += `${i ? 'L' : 'M'}${(12 + r * Math.cos(a)).toFixed(2)} ${(12 + r * Math.sin(a)).toFixed(2)}`;
  }
  return `<path d="${d}Z"/>`;
}

const GLYPHS: Record<string, string> = {
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
  wait: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
};
const glyph = (name: string, size: number): string => `<svg class="glyph" viewBox="0 0 24 24" style="width:${size}px;height:${size}px;stroke-width:${(1.3 * 24 / size).toFixed(2)}">${GLYPHS[name]}</svg>`;

// Untap takes no stop, as on desktop
// [PhaseType, glyph, full name, short name]
const STEPS: [PhaseType, string, string, string][] = [
  ['UPKEEP', 'upkeep', 'Upkeep', 'Upkeep'], ['DRAW', 'draw', 'Draw', 'Draw'], ['MAIN1', 'main1', 'Main 1', 'Main 1'],
  ['COMBAT_BEGIN', 'boc', 'Beginning of combat', 'Combat'], ['COMBAT_DECLARE_ATTACKERS', 'atk', 'Declare attackers', 'Attackers'],
  ['COMBAT_DECLARE_BLOCKERS', 'blk', 'Declare blockers', 'Blockers'], ['COMBAT_FIRST_STRIKE_DAMAGE', 'fs', 'First-strike damage', 'First strike'],
  ['COMBAT_DAMAGE', 'dmg', 'Combat damage', 'Damage'], ['COMBAT_END', 'eoc', 'End of combat', 'End combat'],
  ['MAIN2', 'main2', 'Main 2', 'Main 2'], ['END_OF_TURN', 'end', 'End step', 'End step'], ['CLEANUP', 'cleanup', 'Cleanup', 'Cleanup'],
];
// A segment covers more than the step its glyph is named for, so it is named for what it spans
interface Phase {
  glyph: string;
  name: string;
  steps: number[];
}
const PHASES: Phase[] = [
  { glyph: 'upkeep', name: 'Upkeep and draw', steps: [0, 1] },
  { glyph: 'main1', name: 'Main 1', steps: [2] },
  { glyph: 'boc', name: 'Combat', steps: [3, 4, 5, 6, 7, 8] },
  { glyph: 'main2', name: 'Main 2', steps: [9] },
  { glyph: 'end', name: 'End of turn', steps: [10, 11] },
];
const stepIndex = (phase: PhaseType | undefined): number => STEPS.findIndex(s => s[0] === phase);
export const stepName = (phase: PhaseType | undefined): string => STEPS[stepIndex(phase)]?.[2] ?? 'Untap';

let wired = false;

export function renderPhaseBar(model: Model, g: GameView, actions: Actions): void {
  const open = ui.stopsOpen;
  const root = document.getElementById('phase-strip') as HTMLElement;
  if (!wired) {
    build(root);
    wired = true;
  }
  const active = deref(model, g.PlayerTurn);
  const myTurn = !!active && isLocal(model, active);
  const opponent = opponents(model);
  const opponentLabel = opponent.length === 1 ? opponent[0].Name ?? '' : 'Opponents';
  const step = stepIndex(g.Phase);
  // Untap (index -1) belongs to the beginning phase
  const phase = step < 0 ? 0 : PHASES.findIndex(p => p.steps.includes(step));
  const pill = q(root, '.pill');
  const avatar = active ? playerAvatarUrl(active) : '';
  const portrait = q<HTMLImageElement>(pill, '.owner img');
  portrait.hidden = !avatar;
  if (avatar) {
    portrait.src = avatar;
  }
  q(pill, '.owner b').textContent = myTurn ? 'Your turn' : active?.Name ?? '';
  q(pill, '.owner .turn').textContent = `T${g.Turn ?? 0}${model.controls?.dayTime ? ` · ${model.controls.dayTime}` : ''}`;
  drawTrack(pill, model, step, phase, myTurn, actions);
  drawWaiting(pill, model);
  drawUntil(pill, model, myTurn, opponentLabel);
  q(pill, '.caret').innerHTML = glyph(myTurn ? 'up' : 'down', 12);
  pill.classList.toggle('open', open);
  pill.classList.toggle('priority', !!me(model)?.HasPriority);

  const panel = q(root, '.stops');
  panel.hidden = !open;
  // Opens away from the player who is acting, so their half of the board stays visible
  panel.classList.toggle('above', myTurn);
  if (open) {
    panel.innerHTML = stopsGrid(model, step, myTurn, opponentLabel);
    wireGrid(panel, actions);
  }
}

// The pill is built once and updated in place, so the step can slide from one phase to the next
function build(root: HTMLElement): void {
  root.innerHTML = '<div class="pill" role="button" tabindex="0" title="Phase stops"></div><div class="stops" hidden></div>';
  const pill = q(root, '.pill');
  const track = PHASES.map(p => `<span class="phase">${glyph(p.glyph, 12)}<span class="label"></span><span class="pips"></span><i></i></span>`).join('');
  pill.innerHTML = `<span class="owner"><img alt="" hidden><b></b><span class="turn"></span></span>`
    + `<span class="track">${track}</span>`
    + `<span class="waiting" hidden>${glyph('wait', 11)}<span class="who"></span><b></b></span>`
    + `<span class="until" hidden>${glyph('skip', 12)}<span class="text"></span></span>`
    + '<span class="caret"></span>';
  pill.onclick = () => changeUi(u => { u.stopsOpen = !u.stopsOpen; });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && ui.stopsOpen) {
      // Escape was for the grid, not for the prompt's Cancel
      e.preventDefault();
      changeUi(u => { u.stopsOpen = false; });
    }
  });
  document.addEventListener('mousedown', e => {
    if (ui.stopsOpen && !(e.target instanceof Element && e.target.closest('#phase-strip'))) {
      changeUi(u => { u.stopsOpen = false; });
    }
  });
}

function drawTrack(pill: HTMLElement, model: Model, step: number, phase: number, myTurn: boolean, actions: Actions): void {
  const stops = new Set((myTurn ? model.controls?.myStops : model.controls?.otherStops) ?? []);
  const marker = model.controls?.marker;
  pill.querySelectorAll<HTMLElement>('.track .phase').forEach((el, n) => {
    const p = PHASES[n];
    const current = n === phase;
    el.classList.toggle('current', current);
    el.classList.toggle('stop', !current && p.steps.some(i => stops.has(STEPS[i][0])));
    // Desktop passes priority until a phase by right-clicking it, so the pill offers the same gesture and
    // not only the grid behind it. The marker lands on the first step the segment covers.
    el.title = `${p.name}. Right-click: pass priority until here.`;
    el.oncontextmenu = e => {
      e.preventDefault();
      actions.toggleMarker(STEPS[p.steps[0]][0], myTurn);
    };
    q(el, '.glyph').outerHTML = glyph(current && step >= 0 ? STEPS[step][1] : p.glyph, 12);
    q(el, '.label').textContent = current ? (step < 0 ? 'Untap' : STEPS[step][3]) : '';
    const pips = q(el, '.pips');
    pips.hidden = !current;
    if (current) {
      drawPips(pips, p, step, stops, marker, myTurn);
    }
  });
}

/** How far the turn has reached within the current phase, and which of its steps are set to stop. */
function drawPips(root: HTMLElement, phase: Phase, step: number, stops: Set<string>, marker: TurnMarker | undefined, myTurn: boolean): void {
  reconcile(root, phase.steps, i => i,
    () => {
      const el = document.createElement('span');
      el.className = 'pip';
      return el;
    },
    (el, i) => {
      el.classList.toggle('past', i < step);
      el.classList.toggle('now', i === step);
      el.classList.toggle('on', stops.has(STEPS[i][0]));
      el.classList.toggle('marked', !!marker && marker.mine === myTurn && marker.phase === STEPS[i][0]);
    });
}

function drawUntil(pill: HTMLElement, model: Model, myTurn: boolean, opponentLabel: string): void {
  const marker = model.controls?.marker;
  const until = q(pill, '.until');
  until.hidden = !marker;
  if (marker) {
    const whose = marker.mine === myTurn ? '' : marker.mine ? 'your ' : `${opponentLabel}'s `;
    q(until, '.text').textContent = `until ${whose}${STEPS[stepIndex(marker.phase)]?.[2] ?? ''}`;
  }
}

// Who the game is waiting on, and for how long. Your own priority lights the whole pill instead
let waitingFor: number | null = null;
let waitingSince = 0;
let waitingTimer = 0;

function drawWaiting(pill: HTMLElement, model: Model): void {
  // Nobody is waited on while the game is waiting on you, whether that is priority or a declaration to make
  const onMe = me(model)?.HasPriority || model.prompt?.ok?.enabled || model.prompt?.cancel?.enabled;
  const holder = onMe ? null : players(model).find(p => p.HasPriority && !isLocal(model, p));
  const chip = q(pill, '.waiting');
  chip.hidden = !holder;
  if (!holder) {
    waitingFor = null;
    clearInterval(waitingTimer);
    waitingTimer = 0;
    return;
  }
  if (waitingFor !== holder.$key) {
    waitingFor = holder.$key;
    waitingSince = Date.now();
  }
  q(chip, '.who').textContent = holder.Name ?? '';
  const show = () => {
    q(chip, 'b').textContent = `${Math.floor((Date.now() - waitingSince) / 1000)}s`;
  };
  show();
  if (!waitingTimer) {
    waitingTimer = setInterval(show, 500);
  }
}

function stopsGrid(model: Model, step: number, myTurn: boolean, opponentLabel: string): string {
  const marker = model.controls?.marker;
  const rows = [
    { mine: false, label: `${escapeHtml(opponentLabel)}'s turns`, stops: new Set(model.controls?.otherStops ?? []), now: !myTurn },
    { mine: true, label: 'Your turns', stops: new Set(model.controls?.myStops ?? []), now: myTurn },
  ];
  const gap = (i: number) => (i > 0 && PHASES.findIndex(p => p.steps.includes(i)) !== PHASES.findIndex(p => p.steps.includes(i - 1))) ? '<td class="gap"></td>' : '';
  const head = '<tr><td></td>' + STEPS.map((s, i) => `${gap(i)}<th title="${s[2]}"><span class="head ${i === step ? 'current' : ''}">${glyph(s[1], 13)}</span><span class="name">${s[3]}</span></th>`).join('') + '</tr>';
  const body = rows.map(r => '<tr>' + `<td class="who">${r.label}${r.now ? ' <span class="now">now</span>' : ''}</td>` + STEPS.map((s, i) => {
    const marked = marker && marker.mine === r.mine && marker.phase === s[0];
    const on = r.stops.has(s[0]);
    const title = `${s[2]} · ${r.mine ? 'your turns' : `${opponentLabel}'s turns`}. Click: ${on ? 'clear the stop' : 'stop here'}. Right-click: pass priority until here.`;
    const cell = marked ? `<span class="skip">${glyph('skip', 13)}</span>` : `<span class="square ${on ? 'on' : ''} ${r.now && i === step ? 'current' : ''}"></span>`;
    return `${gap(i)}<td><button class="cell" data-phase="${s[0]}" data-mine="${r.mine}" title="${escapeHtml(title)}">${cell}</button></td>`;
  }).join('') + '</tr>').join('');
  return `<div class="title">Phase stops</div><table>${head}${body}</table>`;
}

function wireGrid(panel: HTMLElement, actions: Actions): void {
  for (const b of panel.querySelectorAll<HTMLElement>('.cell')) {
    // The cell was drawn from STEPS, so its phase is one of them
    const phase = b.dataset.phase as PhaseType;
    const mine = b.dataset.mine === 'true';
    b.onclick = () => actions.toggleStop(phase, mine);
    b.oncontextmenu = e => {
      e.preventDefault();
      actions.toggleMarker(phase, mine);
    };
  }
}

const ESCAPES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

function escapeHtml(s: string): string {
  return String(s).replace(/[&<>"']/g, c => ESCAPES[c]);
}
