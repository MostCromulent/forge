import { reconcile } from './render';
import { deref, isLocal, me, opponents, players, type Model } from './model';
import { playerAvatarUrl } from './looks';
import { byId, q } from './dom';
import { changeUi, ui } from './ui';
import { t, type TextKey } from './text';
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
const STEPS: [PhaseType, string, TextKey, TextKey][] = [
  ['UPKEEP', 'upkeep', 'lblWebPhaseUpkeep', 'lblWebPhaseUpkeep'], ['DRAW', 'draw', 'lblWebPhaseDraw', 'lblWebPhaseDraw'],
  ['MAIN1', 'main1', 'lblWebPhaseMain1', 'lblWebPhaseMain1'],
  ['COMBAT_BEGIN', 'boc', 'lblWebPhaseBeginCombat', 'lblWebPhaseCombat'],
  ['COMBAT_DECLARE_ATTACKERS', 'atk', 'lblWebPhaseDeclareAttackers', 'lblWebPhaseAttackers'],
  ['COMBAT_DECLARE_BLOCKERS', 'blk', 'lblWebPhaseDeclareBlockers', 'lblWebPhaseBlockers'],
  ['COMBAT_FIRST_STRIKE_DAMAGE', 'fs', 'lblWebPhaseFirstStrikeDamage', 'lblWebPhaseFirstStrike'],
  ['COMBAT_DAMAGE', 'dmg', 'lblWebPhaseCombatDamage', 'lblWebPhaseDamage'], ['COMBAT_END', 'eoc', 'lblWebPhaseEndOfCombat', 'lblWebPhaseEndCombat'],
  ['MAIN2', 'main2', 'lblWebPhaseMain2', 'lblWebPhaseMain2'], ['END_OF_TURN', 'end', 'lblEndStep', 'lblEndStep'],
  ['CLEANUP', 'cleanup', 'lblWebPhaseCleanup', 'lblWebPhaseCleanup'],
];
// A segment covers more than the step its glyph is named for, so it is named for what it spans
interface Phase {
  glyph: string;
  /** Under the icon when the phase is not the current one; desktop's step codes, and CB for combat as a whole. */
  code: TextKey;
  name: TextKey;
  /** The phase as the stop grid heads its group of steps. */
  group: TextKey;
  steps: number[];
}
const PHASES: Phase[] = [
  { glyph: 'upkeep', code: 'lblWebPhaseCodeUpkeep', name: 'lblWebPhaseUpkeepAndDraw', group: 'lblWebPhaseBeginning', steps: [0, 1] },
  { glyph: 'main1', code: 'lblWebPhaseCodeMain1', name: 'lblWebPhaseMain1', group: 'lblWebPhaseMain1', steps: [2] },
  { glyph: 'boc', code: 'lblWebPhaseCodeCombat', name: 'lblWebPhaseCombat', group: 'lblWebPhaseCombat', steps: [3, 4, 5, 6, 7, 8] },
  { glyph: 'main2', code: 'lblWebPhaseCodeMain2', name: 'lblWebPhaseMain2', group: 'lblWebPhaseMain2', steps: [9] },
  { glyph: 'end', code: 'lblWebPhaseCodeEnd', name: 'lblWebPhaseEndOfTurn', group: 'lblWebPhaseEnding', steps: [10, 11] },
];
/** Under its phase's heading a step needs less of a name: a lone step none, and combat's own steps no "combat". */
const GRID_NAMES: Partial<Record<PhaseType, TextKey>> = {
  COMBAT_BEGIN: 'lblWebPhaseGridBegin', COMBAT_DECLARE_ATTACKERS: 'lblWebPhaseGridAttack',
  COMBAT_DECLARE_BLOCKERS: 'lblWebPhaseGridBlock', COMBAT_END: 'lblWebPhaseGridEnd',
};
const gridName = (i: number): string =>
  PHASES[phaseOf(i)].steps.length === 1 ? '' : t(GRID_NAMES[STEPS[i][0]] ?? STEPS[i][3]);
const stepIndex = (phase: PhaseType | undefined): number => STEPS.findIndex(s => s[0] === phase);
export const stepName = (phase: PhaseType | undefined): string => t(STEPS[stepIndex(phase)]?.[2] ?? 'lblWebPhaseUntap');
/** A step's full name, or nothing for a phase no step is named for. */
const fullName = (phase: PhaseType): string => {
  const s = STEPS[stepIndex(phase)];
  return s ? t(s[2]) : '';
};
const phaseOf = (step: number): number => PHASES.findIndex(p => p.steps.includes(step));
/** Forge's own default stops (the PHASE_HUMAN_ and PHASE_AI_ preferences), which a row can be reset to. */
const DEFAULT_STOPS: Record<'mine' | 'theirs', PhaseType[]> = {
  mine: ['MAIN1', 'COMBAT_DECLARE_BLOCKERS', 'MAIN2'],
  theirs: ['COMBAT_BEGIN', 'COMBAT_DECLARE_ATTACKERS', 'COMBAT_DECLARE_BLOCKERS', 'END_OF_TURN'],
};

let wired = false;
let stopYield: (() => void) | null = null;

export function renderPhaseBar(model: Model, g: GameView, actions: Actions): void {
  const open = ui.stopsOpen;
  const root = byId('phase-strip');
  if (!wired) {
    build(root);
    wired = true;
  }
  const active = deref(model, g.PlayerTurn);
  const myTurn = !!active && isLocal(model, active);
  const theirs = whoseTurns(model);
  const step = stepIndex(g.Phase);
  // Untap (index -1) belongs to the beginning phase
  const phase = step < 0 ? 0 : phaseOf(step);
  const pill = q(root, '.pill');
  const avatar = active ? playerAvatarUrl(active) : '';
  const portrait = q<HTMLImageElement>(pill, '.owner img');
  portrait.hidden = !avatar;
  if (avatar) {
    portrait.src = avatar;
  }
  swapOwner(q(pill, '.owner'), myTurn ? t('lblWebPhaseYourTurn') : active?.Name ?? '');
  // The host names the time of day as the engine does, in English
  const dayTime = model.controls?.dayTime;
  const time = dayTime === 'Day' ? t('lblDay') : dayTime === 'Night' ? t('lblNight') : dayTime;
  q(pill, '.owner .turn').textContent = time ? t('lblWebPhaseTurnAndTime', g.Turn ?? 0, time) : t('lblWebPhaseTurnNumber', g.Turn ?? 0);
  drawTrack(pill, model, step, phase, myTurn, actions);
  slideTo(q(pill, '.track'), phase, step);
  drawWaiting(pill, model);
  drawUntil(pill, model, myTurn, theirs, active?.Name ?? '');
  stopYield = actions.stopYield;
  const opens = myTurn ? 'up' : 'down';
  // Drawn every frame, so the markup is parsed again only when it changes
  if (pill.dataset.opens !== opens) {
    q(pill, '.caret').innerHTML = glyph(opens, 12);
    pill.dataset.opens = opens;
  }
  pill.classList.toggle('open', open);
  pill.classList.toggle('priority', !!me(model)?.HasPriority);

  const panel = q(root, '.stops');
  panel.hidden = !open;
  // Opens away from the player who is acting, so their half of the board stays visible
  panel.classList.toggle('above', myTurn);
  if (open) {
    const grid = stopsGrid(model, step, myTurn, theirs);
    if (panel.dataset.grid !== grid) {
      panel.dataset.grid = grid;
      panel.innerHTML = grid;
      wireGrid(panel, actions);
    }
  }
}

// The pill is built once and updated in place, so the step can slide from one phase to the next
function build(root: HTMLElement): void {
  root.innerHTML = `<div class="pill" role="button" tabindex="0" title="${escapeHtml(t('lblWebPhaseStops'))}"></div><div class="stops" hidden></div>`;
  const pill = q(root, '.pill');
  // Every segment is laid out alike, the icon over its code, then the step name and pips that only the current one
  // opens, so a change of phase moves nothing by a jump
  const track = PHASES.map(p => `<span class="phase"><span class="mark">${glyph(p.glyph, 12)}<span class="code">${escapeHtml(t(p.code))}</span></span><span class="label"></span><span class="pips"></span></span>`).join('');
  pill.innerHTML = `<span class="owner"><img alt="" hidden><b></b><span class="turn"></span></span>`
    + `<span class="track"><span class="slide" aria-hidden="true"></span>${track}</span>`
    + `<span class="waiting" hidden>${glyph('wait', 11)}<span class="who"></span><b></b></span>`
    + `<span class="until" hidden>${glyph('skip', 12)}<span class="text"></span></span>`
    + '<span class="caret"></span>';
  pill.onclick = () => changeUi(u => { u.stopsOpen = !u.stopsOpen; });
  // The chip naming a pass under way stops it, rather than opening the stops grid
  q(pill, '.until').onclick = e => {
    e.stopPropagation();
    stopYield?.();
  };
  document.addEventListener('mousedown', e => {
    if (ui.stopsOpen && !(e.target instanceof Element && e.target.closest('#phase-strip'))) {
      changeUi(u => { u.stopsOpen = false; });
    }
  });
}

function drawTrack(pill: HTMLElement, model: Model, step: number, phase: number, myTurn: boolean, actions: Actions): void {
  const marker = model.controls?.marker;
  pill.querySelectorAll<HTMLElement>('.track .phase').forEach((el, n) => {
    const p = PHASES[n];
    const current = n === phase;
    el.classList.toggle('current', current);
    el.classList.toggle('marked', !current && !!marker && marker.mine === myTurn && p.steps.some(i => STEPS[i][0] === marker.phase));
    // Desktop passes priority until a phase by right-clicking it, so the pill offers the same gesture and
    // not only the grid behind it. The marker lands on the first step the segment covers.
    el.title = t('lblWebPhaseSegmentTitle', t(p.name));
    el.oncontextmenu = e => {
      e.preventDefault();
      actions.toggleMarker(STEPS[p.steps[0]][0], myTurn);
    };
    q(el, '.glyph').outerHTML = glyph(current && step >= 0 ? STEPS[step][1] : p.glyph, 12);
    q(el, '.label').textContent = current ? t(step < 0 ? 'lblWebPhaseUntap' : STEPS[step][3]) : '';
    const pips = q(el, '.pips');
    // A phase left keeps its last pips while they close
    if (current) {
      drawPips(pips, p, step, marker, myTurn);
    }
  });
}

/** The pill's motions last as long as its CSS transitions (--pill-ms in board.css), and ease out as they do. */
const PILL_MS = 240;
const easeOut = (t: number): number => 1 - (1 - t) ** 3;
const reduced = (): boolean => document.documentElement.dataset.motion === 'reduced';

/**
 * The brass behind the current phase is one piece that glides from segment to segment, since a gradient cannot fade
 * from one to the next. It eases from where it is to where the current segment is, read each frame, as that segment
 * is still growing to fit its step's name; a change arriving mid-glide starts the next from where it has got to.
 */
let slideKey = '';
let slideAt = { left: 0, width: 0 };
let sliding = 0;
function slideTo(track: HTMLElement, phase: number, step: number): void {
  const key = `${phase}:${step}`;
  if (key === slideKey) return;
  const first = !slideKey;
  slideKey = key;
  const slide = q(track, '.slide');
  const target = () => {
    const r = track.querySelectorAll<HTMLElement>('.phase')[phase].getBoundingClientRect();
    return { left: r.left - track.getBoundingClientRect().left, width: r.width };
  };
  const place = (r: { left: number; width: number }) => {
    slideAt = r;
    slide.style.transform = `translateX(${r.left}px)`;
    slide.style.width = `${r.width}px`;
  };
  cancelAnimationFrame(sliding);
  const start = performance.now();
  const from = slideAt;
  const frame = () => {
    const elapsed = performance.now() - start;
    const to = target();
    // With nothing to glide from, the brass only follows the segment as it grows to fit its name
    const k = first || reduced() ? 1 : easeOut(Math.min(1, elapsed / PILL_MS));
    place({ left: from.left + (to.left - from.left) * k, width: from.width + (to.width - from.width) * k });
    // A little past the glide, so the brass settles on the segment's final width
    if (elapsed < PILL_MS + 60) sliding = requestAnimationFrame(frame);
  };
  frame();
}

/** A new turn's owner fades in as the chip eases to the new name's width, rather than the pill jumping sideways. */
function swapOwner(owner: HTMLElement, name: string): void {
  const b = q(owner, 'b');
  if (b.textContent === name) return;
  const before = owner.offsetWidth;
  const had = !!b.textContent;
  b.textContent = name;
  if (!had || reduced()) return;
  const after = owner.offsetWidth;
  const timing = { duration: PILL_MS, easing: 'cubic-bezier(.2,.7,.2,1)' };
  owner.animate([{ width: `${before}px` }, { width: `${after}px` }], timing);
  for (const part of owner.children) part.animate([{ opacity: 0 }, { opacity: 1 }], timing);
}

/** How far the turn has reached within the current phase. */
function drawPips(root: HTMLElement, phase: Phase, step: number, marker: TurnMarker | undefined, myTurn: boolean): void {
  reconcile(root, phase.steps, i => i,
    () => {
      const el = document.createElement('span');
      el.className = 'pip';
      return el;
    },
    (el, i) => {
      el.classList.toggle('past', i < step);
      el.classList.toggle('now', i === step);
      el.classList.toggle('marked', !!marker && marker.mine === myTurn && marker.phase === STEPS[i][0]);
    });
}

/** Whose turns the opponents' row covers: one opponent by name, or several together. */
interface Theirs {
  turns: string;
  /** The one opponent's name, when there is only one. */
  one: string | null;
}

function whoseTurns(model: Model): Theirs {
  const opponent = opponents(model);
  if (opponent.length === 1) {
    const name = opponent[0].Name ?? '';
    return { turns: t('lblWebPhasePlayersTurns', name), one: name };
  }
  return { turns: t('lblWebPhaseOpponentsTurns'), one: null };
}

// Only one yield runs at a time, so the chip names whichever it is and where it stops
function drawUntil(pill: HTMLElement, model: Model, myTurn: boolean, theirs: Theirs, activeName: string): void {
  const controls = model.controls;
  const marker = controls?.marker;
  let text = '';
  if (marker) {
    const name = fullName(marker.phase);
    text = marker.mine === myTurn ? t('lblWebPhaseUntilStep', name)
      : marker.mine ? t('lblWebPhaseUntilYourStep', name)
      : theirs.one !== null ? t('lblWebPhaseUntilPlayersStep', theirs.one, name)
      : t('lblWebPhaseUntilOpponentsStep', name);
  } else if (controls?.untilEndOfTurn) {
    text = myTurn ? t('lblWebPhaseUntilEndOfYourTurn') : t('lblWebPhaseUntilEndOfPlayersTurn', activeName);
  } else if (controls?.untilStackEmpty) {
    text = t('lblWebPhaseUntilStackClears');
  }
  const until = q(pill, '.until');
  until.hidden = !text;
  until.title = text ? t('lblWebPhasePassingPriority', text) : '';
  q(until, '.text').textContent = text;
}

// Who the game is waiting on, and for how long. Your own priority lights the whole pill instead
let waitingFor: number | null = null;
let waitingSince = 0;
let waitingTimer = 0;

/** How long another player must be deciding before the pill says who the game is waiting on. */
const WAIT_SHOWN_AFTER_S = 2;

export function stopWaiting(): void {
  waitingFor = null;
  clearInterval(waitingTimer);
  waitingTimer = 0;
}

function drawWaiting(pill: HTMLElement, model: Model): void {
  // Nobody is waited on while the game is waiting on you, whether that is priority or a declaration to make
  const onMe = me(model)?.HasPriority || model.prompt?.ok?.enabled || model.prompt?.cancel?.enabled;
  const holder = onMe ? null : players(model).find(p => p.HasPriority && !isLocal(model, p));
  const chip = q(pill, '.waiting');
  chip.hidden = !holder;
  if (!holder) {
    stopWaiting();
    return;
  }
  if (waitingFor !== holder.$key) {
    waitingFor = holder.$key;
    waitingSince = Date.now();
  }
  q(chip, '.who').textContent = holder.Name ?? '';
  // A wait of a moment is not worth a chip, and one that appeared reading 0s looked stuck
  const show = () => {
    const seconds = Math.floor((Date.now() - waitingSince) / 1000);
    chip.hidden = seconds < WAIT_SHOWN_AFTER_S;
    q(chip, 'b').textContent = t('lblWebPhaseWaitSeconds', seconds);
  };
  show();
  if (!waitingTimer) {
    waitingTimer = setInterval(show, 500);
  }
}

function stopsGrid(model: Model, step: number, myTurn: boolean, theirs: Theirs): string {
  const marker = model.controls?.marker;
  // Your own turns always head the grid, whoever's turn it is now
  const rows = [
    { mine: true, label: escapeHtml(t('lblWebPhaseYourTurns')), stops: new Set(model.controls?.myStops ?? []), now: myTurn },
    { mine: false, label: escapeHtml(theirs.turns), stops: new Set(model.controls?.otherStops ?? []), now: !myTurn },
  ];
  const gap = (i: number) => (i > 0 && phaseOf(i) !== phaseOf(i - 1)) ? '<td class="gap"></td>' : '';
  // Steps sit under their phase, which a bracketed heading spans
  const groups = '<tr class="groups"><td></td>' + PHASES.map((p, g) =>
    `${g ? '<td class="gap"></td>' : ''}<th class="group" colspan="${p.steps.length}"><span>${escapeHtml(t(p.group))}</span></th>`).join('') + '</tr>';
  const head = '<tr><td></td>' + STEPS.map((s, i) => `${gap(i)}<th class="${i === step ? 'now' : ''}" title="${escapeHtml(t(s[2]))}"><span class="head">${glyph(s[1], 13)}</span><span class="name">${escapeHtml(gridName(i))}</span></th>`).join('') + '</tr>';
  // Whose turn it is lights that player's row, and the step the game is at lights its column the same way
  const body = rows.map(r => `<tr class="${r.now ? 'active' : ''}">` + `<td class="who">${r.label}</td>` + STEPS.map((s, i) => {
    const marked = marker && marker.mine === r.mine && marker.phase === s[0];
    const on = r.stops.has(s[0]);
    const title = t(on ? 'lblWebPhaseCellTitleClear' : 'lblWebPhaseCellTitleStop', t(s[2]), r.mine ? t('lblWebPhaseYourTurnsInSentence') : theirs.turns);
    const cell = marked ? `<span class="skip">${glyph('skip', 13)}</span>` : `<span class="square ${on ? 'on' : ''} ${r.now && i === step ? 'current' : ''}"></span>`;
    return `${gap(i)}<td class="${i === step ? 'now' : ''}"><button class="cell" data-phase="${s[0]}" data-mine="${r.mine}" title="${escapeHtml(title)}">${cell}</button></td>`;
  }).join('') + `<td class="row-tools"><button class="row-tool" data-clear data-mine="${r.mine}">${escapeHtml(t('lblWebPhaseClear'))}</button>`
    + `<button class="row-tool" data-defaults data-mine="${r.mine}">${escapeHtml(t('lblWebPhaseDefaults'))}</button></td></tr>`).join('');
  return `<div class="title">${escapeHtml(t('lblWebPhaseStops'))}</div><table>${groups}${head}${body}</table>`
    + `<div class="hint">${escapeHtml(t('lblWebPhaseStopsHint'))}</div>`;
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
  for (const b of panel.querySelectorAll<HTMLElement>('.row-tool')) {
    const mine = b.dataset.mine === 'true';
    b.onclick = () => actions.setStops(mine, b.dataset.clear !== undefined ? [] : DEFAULT_STOPS[mine ? 'mine' : 'theirs']);
  }
}

const ESCAPES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

function escapeHtml(s: string): string {
  return String(s).replace(/[&<>"']/g, c => ESCAPES[c]);
}
