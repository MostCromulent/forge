import { combatShown, game, derefAll, players, type Model } from './model';
import { setting } from './settings';
import { byId } from './dom';
import { cardElement, pileTopFor } from './motion';
import { ui } from './ui';
import type { CardView, Ref, Refs, StackItemView, TrackedObject } from './protocol';

// Attack, block and target arrows differ in brightness as well as hue, so they stay apart for red-green colour blindness
interface ArrowKind {
  sheath: string;
  rim: string;
  glow: string;
  core: string;
  /** A block only planned, or one a card is made to make, is drawn fainter than one declared. */
  alpha: number;
}

const ATTACK = { sheath: '#e8321f', rim: '#ff6a4c', glow: '#ff2a14', core: '#fff4ee' };
const BLOCK = { sheath: '#2f7df0', rim: '#6fb0ff', glow: '#2a8cff', core: '#eef6ff' };
const TARGET = { sheath: '#f0b81f', rim: '#ffd95a', glow: '#ffc21a', core: '#fffbea' };

const KINDS: Record<'attack' | 'block' | 'plannedBlock' | 'target' | 'mustBlock', ArrowKind> = {
  attack: { ...ATTACK, alpha: 1 },
  block: { ...BLOCK, alpha: 1 },
  plannedBlock: { ...BLOCK, alpha: 0.55 },
  target: { ...TARGET, alpha: 1 },
  mustBlock: { ...BLOCK, alpha: 0.4 },
};

/** The arrow was designed at a smaller card size; this is how much larger it is drawn over the board's cards. */
const SCALE = 1.4;
/** How far back from its tip the head's neck is, where the body meets it, in the head's design units. */
const NECK = 14;

interface Point {
  x: number;
  y: number;
}

let settleFrame = 0;
/** How long after a redraw the overlay follows the board even before anything is seen moving. */
const SETTLE_START_MS = 100;
let drawn: Model | null = null;
let repaintQueued = false;

export function initOverlay(schedule: () => void): void {
  window.addEventListener('resize', schedule);
  // A scrolling log or zone panel moves the cards the arrows point at, so only the arrows are redrawn, once a frame
  document.addEventListener('scroll', repaintSoon, true);
  document.addEventListener('pointermove', e => {
    if (e.pointerType !== 'mouse') return;
    pointer = { x: e.clientX, y: e.clientY };
    if (aiming) repaintSoon();
  });
}

function repaintSoon(): void {
  if (repaintQueued || !drawn) return;
  repaintQueued = true;
  requestAnimationFrame(() => {
    repaintQueued = false;
    if (drawn) paint(drawn);
  });
}

export function drawOverlay(model: Model): void {
  drawn = model;
  paint(model);
  // A card's move starts a frame after the change that causes it, so the first frames repaint whether or not anything moves yet
  cancelAnimationFrame(settleFrame);
  const since = performance.now();
  const settle = () => {
    if (drawn !== model) return;
    paint(model);
    if (performance.now() - since < SETTLE_START_MS || boardMoving() || growing) settleFrame = requestAnimationFrame(settle);
  };
  settleFrame = requestAnimationFrame(settle);
}

/** Whether a card on the board is still on its way somewhere. Endless effects, such as a breathing glow, never settle. */
function boardMoving(): boolean {
  return document.getAnimations().some(a => {
    const target = (a.effect as KeyframeEffect | null)?.target;
    return a.playState === 'running' && a.effect?.getTiming().iterations !== Infinity
      && target instanceof Element && !!target.closest('#match .card, #match .slot, #match .seat, #match .group');
  });
}

function paint(model: Model): void {
  growing = false;
  drawnNow = new Map();
  glowNext = null;
  paintArrows(model);
  born = drawnNow;
  // Set while the arrows were drawn, which the compiler cannot see from here
  const next = glowNext as typeof glowing;
  if (next?.el !== glowing?.el || next?.colour !== glowing?.colour) {
    glowing?.el.classList.remove('aimed');
    if (next) {
      next.el.classList.add('aimed');
      next.el.style.setProperty('--aimed', next.colour);
    }
    glowing = next;
  }
}

/** What the arrow being aimed or dragged lands on glows in the arrow's colour, as the one a click or drop picks. */
let glowing: { el: HTMLElement; colour: string } | null = null;
let glowNext: { el: HTMLElement; colour: string } | null = null;
const glow = (el: HTMLElement, kind: ArrowKind) => { glowNext = { el, colour: kind.glow }; };

function paintArrows(model: Model): void {
  const canvas = byId<HTMLCanvasElement>('overlay');
  const ratio = window.devicePixelRatio || 1;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  if (canvas.width !== innerWidth * ratio || canvas.height !== innerHeight * ratio) {
    canvas.width = innerWidth * ratio;
    canvas.height = innerHeight * ratio;
    canvas.style.width = `${innerWidth}px`;
    canvas.style.height = `${innerHeight}px`;
  } else {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
  }
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  const g = game(model);
  if (!g || byId('match').hidden) {
    placeCharges(new Set());
    return;
  }
  // A chevron marks the attacker's state, as tapping does, so it shows whatever the arrows setting
  const atFace = atLoneFace(model);
  // Once combat damage is dealt the attack has landed, so the chevrons go, though the attackers stay marked
  const landed = g.Phase === 'COMBAT_DAMAGE';
  placeCharges(landed ? new Set() : chargingAtPlayer(model), landed);
  // A block being dragged is drawn whatever the arrows setting, as it is the player's own hand on the board
  drawDrag(ctx);
  drawAim(ctx, model);
  const mode = setting('arrows');
  if (mode === '0') {
    drawQueued(ctx);
    return;
  }
  // "On hover" keeps combat arrows off and leaves only the ones for the stack item under the pointer
  for (const band of mode === '1' || !combatShown(model) ? [] : g.CombatView ?? []) {
    const attackers = present(band.attackers);
    // A blocked attacker's block arrow is what matters now, so it loses its arrow at the defender
    const blocked = present(band.blockers).length > 0 || present(band.plannedBlockers).length > 0;
    attackers.forEach(attacker => {
      if (!atFace.has(attacker.ref) && !blocked) {
        ribbon(ctx, elementFor(attacker.ref), elementFor(band.defender?.ref), KINDS.attack);
      }
      for (const blocker of present(band.blockers)) {
        ribbon(ctx, elementFor(blocker.ref), elementFor(attacker.ref), KINDS.block);
      }
      for (const blocker of present(band.plannedBlockers)) {
        ribbon(ctx, elementFor(blocker.ref), elementFor(attacker.ref), KINDS.plannedBlock);
      }
    });
  }
  // Without an arrow to what a creature has to block, an illegal block is refused and nothing on screen says why
  if (g.Phase === 'COMBAT_DECLARE_BLOCKERS') {
    for (const obj of model.objects.values()) {
      const forced = present((obj as CardView).MustBlockCards);
      forced.forEach(attacker => ribbon(ctx, elementFor(obj.$key), elementFor(attacker.ref), KINDS.mustBlock));
    }
  }
  const item = ui.hoveredStackItem !== null ? model.objects.get(ui.hoveredStackItem) : null;
  if (item) {
    const from = document.querySelector<HTMLElement>(`.stack-item[data-key="${item.$key}"]`);
    const targets = stackTargets(model, item);
    targets.forEach(target => ribbon(ctx, from, elementFor(target.$key), KINDS.target));
  }
  drawQueued(ctx);
}

const present = (refs: Refs | null | undefined): Ref[] => (refs ?? []).filter((r): r is Ref => !!r);

/** A block or an attack being dragged out: from the creature to what it would block or attack, or to the pointer. */
let drag: { from: HTMLElement; to: HTMLElement | Point; kind: 'block' | 'attack' } | null = null;

export function setDragArrow(from: HTMLElement | null, to: HTMLElement | Point | null, kind: 'block' | 'attack' = 'block'): void {
  const next = from && to ? { from, to, kind } : null;
  if (!next && !drag) return;
  drag = next;
  if (drawn) paint(drawn);
}

function drawDrag(ctx: CanvasRenderingContext2D): void {
  if (!drag) return;
  const kind = KINDS[drag.kind];
  if (drag.to instanceof HTMLElement) {
    ribbon(ctx, drag.from, drag.to, kind, false);
    glow(drag.to, kind);
  } else {
    arrow(ctx, edge(drag.from, drag.to, 2), drag.to, kind);
  }
}

const onStack = (key: string): HTMLElement | null =>
  document.querySelector<HTMLElement>(`#stack .stack-item img[data-key="${key}"]`)?.closest<HTMLElement>('.stack-item') ?? null;

let pointer: Point | null = null;
/** The card being aimed and the targets picked for it, which the payment prompt that follows no longer carries. */
let lastAim: { key: string; targets: number[] } | null = null;

/** Keeps a clicked target, because a spell with one target goes straight to payment and the prompt never shows it picked. */
export function notePick(key: number): void {
  const p = drawn?.prompt;
  const offered = !!p && (p.selectable.some(r => r.ref === key) || p.selectablePlayers.some(r => r.ref === key));
  if (aiming && lastAim && offered && !lastAim.targets.includes(key)) lastAim.targets.push(key);
}
/** Whether the last paint drew the aim, so moving the mouse only repaints while there is one to follow it. */
let aiming = false;

/** Whether a target is being chosen, with the arrow following the pointer. */
export function isAiming(): boolean {
  return aiming;
}
/** The target the aim has landed on, held until the pointer leaves its box so that moving about on it never drops the arrow. */
let aimedAt: HTMLElement | null = null;
/** How far past that target's box the pointer can stray before the arrow lets go of it. */
const LET_GO = 4;

/** Draws the aim while targets are chosen, which only a prompt that both names a card and offers picks marks. */
function drawAim(ctx: CanvasRenderingContext2D, model: Model): void {
  const p = model.prompt;
  const aimed = !!p?.card && !p.paying && !p.priority && (p.selectable.length > 0 || p.selectablePlayers.length > 0)
    && !(model.zones.length > 0 && !ui.zonesMinimised);
  const key = String(p?.card?.ref);
  // A spell is already on the stack, awaiting payment, while its targets are chosen; an ability's card is on the board
  const from = aimed ? onStack(key) ?? cardElement(key) ?? pileTopFor(key) : null;
  aiming = !!from;
  if (from && p) {
    // Picks clicked for this card stay until the prompt shows them itself
    const clicked = lastAim?.key === key ? lastAim.targets : [];
    lastAim = { key, targets: [...new Set([...p.highlighted, ...clicked])] };
  } else {
    // Paying for it, the prompt no longer names the targets, so the ones last picked stay drawn from its waiting slot
    const waiting = p?.paying && lastAim ? onStack(lastAim.key) : null;
    if (waiting && lastAim) {
      const targets = lastAim.targets;
      targets.forEach(k => ribbon(ctx, waiting, elementFor(k) ?? onStack(String(k)), KINDS.target));
    } else {
      lastAim = null;
    }
  }
  if (!from || !p) {
    aimedAt = null;
    return;
  }
  p.highlighted.forEach(k => ribbon(ctx, from, elementFor(k) ?? onStack(String(k)), KINDS.target));
  if (!pointer) return;
  const r = from.getBoundingClientRect();
  if (within(r, pointer, 0)) return;
  const hit = document.elementFromPoint(pointer.x, pointer.y)?.closest<HTMLElement>('.selectable, .targetable') ?? null;
  const held = aimedAt?.isConnected && aimedAt.matches('.selectable, .targetable') && within(aimedAt.getBoundingClientRect(), pointer, LET_GO);
  const over = hit ?? (held ? aimedAt : null);
  aimedAt = over;
  if (over) {
    // Full grown at once: the arrow was already out to the pointer, so only its head moves onto the target
    ribbon(ctx, from, over, KINDS.target, false);
    glow(over, KINDS.target);
  } else {
    arrow(ctx, edge(from, pointer, 2), pointer, KINDS.target);
  }
}

// The attack mark's drawing (board.css) is 64 by 54 units, with its chevrons from 16 below the top to 13.7 above the bottom
const MARK_W = 64;
const MARK_H = 54;
const MARK_TOP = 16;
const MARK_BELOW = 13.7;

/** The chevron over each charging attacker, kept between paints so its halo breathes on rather than restarting. */
const charges = new Map<number, HTMLElement>();

/** Places each chevron from its card as it stands on screen, so a tapped card's chevron is over its turned edge. */
function placeCharges(keys: Set<number>, landed = false): void {
  for (const [key, mark] of charges) {
    if (!keys.has(key)) {
      charges.delete(key);
      retreat(mark, !!elementFor(key), landed);
    }
  }
  for (const key of keys) {
    const card = elementFor(key);
    let mark = charges.get(key);
    if (!card) {
      mark?.remove();
      charges.delete(key);
      continue;
    }
    const arriving = !mark;
    if (!mark) {
      mark = document.createElement('div');
      mark.className = 'charge';
      byId('charges').append(mark);
      charges.set(key, mark);
    }
    const r = card.getBoundingClientRect();
    const width = card.offsetWidth * .66;
    const height = width * MARK_H / MARK_W;
    // The chevrons stand an eighth of the card's width clear of it, so the trailing one is not lost behind the card
    const sink = height * MARK_BELOW / MARK_H - card.offsetWidth * .12;
    const down = !!card.closest('#opponent');
    // A crowded battlefield scrolls, so the chevron is kept inside the battlefield's box and off the phase pill beyond it
    const field = card.closest('.battlefield')?.getBoundingClientRect();
    mark.classList.toggle('down', down);
    mark.style.width = `${width}px`;
    mark.style.left = `${r.left + r.width / 2}px`;
    mark.style.top = `${down
      ? Math.min(r.bottom - sink, (field?.bottom ?? Infinity) - height * (MARK_H - MARK_TOP) / MARK_H)
      : Math.max(r.top + sink, (field?.top ?? -Infinity) + height * (MARK_H - MARK_TOP) / MARK_H)}px`;
    if (arriving && document.documentElement.dataset.motion !== 'reduced') {
      // Out from the attacker, growing and brightening: the retreat played forwards
      const back = down ? -1 : 1;
      mark.animate([
        { opacity: 0, translate: `0 ${back * height * 0.7}px`, scale: '.55' },
        { opacity: 1, translate: '0 0', scale: '1' },
      ], { duration: ARRIVE_MS, easing: 'cubic-bezier(.2, .8, .3, 1)' });
    }
  }
}

const ARRIVE_MS = 220;

/** After combat damage, long enough for the hit to read before the chevrons draw back. */
const RETREAT_DELAY_MS = 250;
const RETREAT_MS = 300;

/** A chevron whose card has left the battlefield has nothing to draw back to, so it goes at once. */
function retreat(mark: HTMLElement, cardStays: boolean, afterHit: boolean): void {
  if (!cardStays || document.documentElement.dataset.motion === 'reduced') {
    mark.remove();
    return;
  }
  // translate and scale are separate properties, so they add to the transform that places and turns the mark
  const back = mark.classList.contains('down') ? -1 : 1;
  const run = mark.animate([
    { opacity: 1, translate: '0 0', scale: '1' },
    { opacity: 0, translate: `0 ${back * mark.offsetHeight * 0.7}px`, scale: '.55' },
  ], { duration: RETREAT_MS, delay: afterHit ? RETREAT_DELAY_MS : 0, easing: 'cubic-bezier(.5, 0, .75, 0)', fill: 'forwards' });
  run.finished.then(() => mark.remove(), () => mark.remove());
}

/** Attackers aimed at the one opponent's face in a two-player game, each mapped to whether it is blocked. */
function atLoneFace(model: Model): Map<number, boolean> {
  const out = new Map<number, boolean>();
  const everyone = players(model);
  if (everyone.length !== 2 || !combatShown(model)) return out;
  const faces = new Set(everyone.map(p => p.$key));
  for (const band of game(model)?.CombatView ?? []) {
    const blocked = present(band.blockers).length > 0 || present(band.plannedBlockers).length > 0;
    if (band.defender && faces.has(band.defender.ref)) present(band.attackers).forEach(a => out.set(a.ref, blocked));
  }
  return out;
}

/** The unblocked attackers at the lone opponent's face, which are the only ones that wear a chevron. */
export function chargingAtPlayer(model: Model): Set<number> {
  return new Set([...atLoneFace(model)].filter(([, blocked]) => !blocked).map(([key]) => key));
}

export function stackTargets(model: Model, item: StackItemView): TrackedObject[] {
  const out: TrackedObject[] = [];
  for (let i: StackItemView | undefined = item; i; i = i.SubInstance ? model.objects.get(i.SubInstance.ref) : undefined) {
    out.push(...derefAll(model, i.TargetCards), ...derefAll(model, i.TargetPlayers));
  }
  return out;
}

// A card inside a pile is drawn by the pile's top card
function elementFor(key: number | undefined): HTMLElement | null {
  if (key === undefined) return null;
  return document.querySelector<HTMLElement>(`.seat[data-player="${key}"] .avatar`)
    ?? cardElement(String(key)) ?? pileTopFor(String(key));
}

function center(el: HTMLElement): Point {
  const r = el.getBoundingClientRect();
  return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
}

/** Whether a point lies in a box, or within pad of it. */
function within(r: DOMRect, at: Point, pad: number): boolean {
  return at.x >= r.left - pad && at.x <= r.right + pad && at.y >= r.top - pad && at.y <= r.bottom + pad;
}

// Where the line from the element's middle towards `to` leaves its box
function edge(el: HTMLElement, to: Point, pad: number): Point {
  const r = el.getBoundingClientRect();
  const c = { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  const dx = to.x - c.x;
  const dy = to.y - c.y;
  const len = Math.hypot(dx, dy) || 1;
  const step = Math.min(dx ? (r.width / 2 + pad) / Math.abs(dx / len) : 1e9,
    dy ? (r.height / 2 + pad) / Math.abs(dy / len) : 1e9);
  return { x: c.x + (dx / len) * step, y: c.y + (dy / len) * step };
}

const rgba = (hex: string, a: number) => {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
};

/** The arrows of this paint, drawn together at its end so the ones sharing a target can share where they land. */
let queued: { from: HTMLElement; to: HTMLElement; kind: ArrowKind; grows: boolean }[] = [];

function ribbon(ctx: CanvasRenderingContext2D, fromEl: HTMLElement | null, toEl: HTMLElement | null, kind: ArrowKind, grows = true): void {
  if (!fromEl || !toEl || fromEl === toEl) return;
  // A block the engine lists as both declared and planned is drawn once, as declared, which is queued first
  if (queued.some(q => q.from === fromEl && q.to === toEl)) return;
  queued.push({ from: fromEl, to: toEl, kind, grows });
}

/** How far apart the heads of arrows at one target sit, at most, so each can still be told from the others. */
const HEAD_GAP = 6;

/** Arrows at one target aim at its middle and stop at its edge, spaced in the order their sources stand so they never cross. */
function drawQueued(ctx: CanvasRenderingContext2D): void {
  const byTarget = new Map<HTMLElement, typeof queued>();
  for (const q of queued) byTarget.set(q.to, [...byTarget.get(q.to) ?? [], q]);
  for (const [to, arrows] of byTarget) {
    const from = arrows.map(q => center(q.from));
    const middle = { x: from.reduce((n, p) => n + p.x, 0) / from.length, y: from.reduce((n, p) => n + p.y, 0) / from.length };
    const c = center(to);
    // Across the way the arrows come in: each aims a little off the middle by where its source stands
    const len = Math.hypot(c.x - middle.x, c.y - middle.y) || 1;
    const across = { x: -(c.y - middle.y) / len, y: (c.x - middle.x) / len };
    const order = arrows.map((_, i) => i)
      .sort((i, j) => (from[i].x - from[j].x) * across.x + (from[i].y - from[j].y) * across.y);
    const gap = Math.min(HEAD_GAP, 28 / arrows.length);
    order.forEach((i, rank) => {
      const q = arrows[i];
      const off = (rank - (arrows.length - 1) / 2) * gap;
      const aim = { x: c.x + across.x * off, y: c.y + across.y * off };
      const a = edge(q.from, aim, 2);
      arrow(ctx, a, aim, q.kind, reach(a, aim, to, 6) * (q.grows ? growth(q.from, q.to, q.kind) : 1));
    });
  }
  queued = [];
}

/** How far along the arrow's curve it first comes within pad of the target: a portrait by its circle, a card by its box. */
function reach(a: Point, b: Point, to: HTMLElement, pad: number): number {
  const r = to.getBoundingClientRect();
  const c = { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  const round = to.classList.contains('avatar');
  const bend = bendOf(a, b);
  for (let t = 0; t <= 1; t += 0.005) {
    const u = 1 - t;
    const p = { x: u * u * a.x + 2 * u * t * bend.x + t * t * b.x, y: u * u * a.y + 2 * u * t * bend.y + t * t * b.y };
    if (round ? Math.hypot(p.x - c.x, p.y - c.y) <= r.width / 2 + pad : within(r, p, pad)) return t;
  }
  return 1;
}

/** The control point of an arrow's curve. A deeper bow keeps two arrows between the same rows apart and reads as a throw rather than a ruler line. */
function bendOf(a: Point, b: Point): Point {
  const bow = 0.34;
  return { x: (a.x + b.x) / 2 + (b.y - a.y) * bow, y: (a.y + b.y) / 2 - (b.x - a.x) * bow };
}

/** How long an arrow takes to reach its target when it first appears. */
const GROW_MS = 220;
/** When each arrow drawn was first drawn, by its ends and kind; an arrow not drawn in a paint is forgotten. */
let born = new Map<string, number>();
let drawnNow = new Map<string, number>();
/** Whether an arrow is still growing, so the overlay keeps painting until it has. */
let growing = false;
const ids = new WeakMap<Element, number>();
let nextId = 0;
const idOf = (el: Element) => ids.get(el) ?? (ids.set(el, ++nextId), nextId);

/** How far along an arrow is, from 0 as it first appears to 1 once it has reached its target. */
function growth(from: HTMLElement, to: HTMLElement, kind: ArrowKind): number {
  if (document.documentElement.dataset.motion === 'reduced') return 1;
  const key = `${idOf(from)}>${idOf(to)}>${kind.core}`;
  const first = born.get(key) ?? performance.now();
  drawnNow.set(key, first);
  const t = Math.min(1, (performance.now() - first) / GROW_MS);
  if (t < 1) growing = true;
  return 1 - (1 - t) ** 3;
}

function arrow(ctx: CanvasRenderingContext2D, a: Point, full: Point, kind: ArrowKind, grown = 1): void {
  const whole = bendOf(a, full);
  // A growing arrow, or one stopped at its target's edge, is the same curve cut short at how far it has got, so it follows the path it will end on
  const u = 1 - grown;
  const end = grown < 1 ? { x: u * u * a.x + 2 * u * grown * whole.x + grown * grown * full.x, y: u * u * a.y + 2 * u * grown * whole.y + grown * grown * full.y } : full;
  const bend = grown < 1 ? { x: a.x + (whole.x - a.x) * grown, y: a.y + (whole.y - a.y) * grown } : whole;
  // Too short yet to carry a head
  if (grown < 1 && Math.hypot(end.x - a.x, end.y - a.y) < NECK * SCALE * 1.5) return;
  // The body stops at the head's neck, so the head stands on it rather than covering its end
  const [bodyBend, bodyEnd] = trim(a, bend, end, NECK * SCALE);
  // Fine at the source, fullest a little past the middle, and narrow again at the neck
  const half = (t: number) => (0.9 + 1.7 * Math.sin(Math.PI * Math.pow(t, 1.35))) * SCALE;
  ctx.save();
  ctx.globalAlpha = kind.alpha;
  taper(ctx, a, bodyBend, bodyEnd, half);
  ctx.shadowColor = rgba(kind.glow, 0.6);
  ctx.shadowBlur = 7 * SCALE;
  ctx.fillStyle = kind.sheath;
  ctx.fill();
  ctx.shadowColor = 'transparent';
  ctx.strokeStyle = kind.rim;
  ctx.lineWidth = 0.6;
  ctx.stroke();
  taper(ctx, a, bodyBend, bodyEnd, t => half(t) * 0.28);
  ctx.fillStyle = kind.core;
  ctx.fill();
  head(ctx, end, Math.atan2(end.y - bend.y, end.x - bend.x), kind);
  ctx.restore();
}

/** The same quadratic curve cut short where it comes within `back` pixels of its end, as a control point and an end. */
function trim(a: Point, bend: Point, b: Point, back: number): [Point, Point] {
  const at = (t: number): Point => {
    const u = 1 - t;
    return { x: u * u * a.x + 2 * u * t * bend.x + t * t * b.x, y: u * u * a.y + 2 * u * t * bend.y + t * t * b.y };
  };
  let t = 1;
  while (t > 0.5 && Math.hypot(at(t).x - b.x, at(t).y - b.y) < back) t -= 0.005;
  return [{ x: a.x + (bend.x - a.x) * t, y: a.y + (bend.y - a.y) * t }, at(t)];
}

// The outline of a quadratic curve given a half-width at each point along it
function taper(ctx: CanvasRenderingContext2D, a: Point, bend: Point, b: Point, half: (t: number) => number): void {
  const steps = 26;
  const side: [Point[], Point[]] = [[], []];
  for (let i = 0; i <= steps; i++) {
    const t = i / steps;
    const u = 1 - t;
    const x = u * u * a.x + 2 * u * t * bend.x + t * t * b.x;
    const y = u * u * a.y + 2 * u * t * bend.y + t * t * b.y;
    const dx = 2 * u * (bend.x - a.x) + 2 * t * (b.x - bend.x);
    const dy = 2 * u * (bend.y - a.y) + 2 * t * (b.y - bend.y);
    const len = Math.hypot(dx, dy) || 1;
    const w = half(t);
    side[0].push({ x: x - (dy / len) * w, y: y + (dx / len) * w });
    side[1].push({ x: x + (dy / len) * w, y: y - (dx / len) * w });
  }
  ctx.beginPath();
  side[0].forEach((p, i) => (i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y)));
  for (let i = side[1].length - 1; i >= 0; i--) {
    ctx.lineTo(side[1][i].x, side[1][i].y);
  }
  ctx.closePath();
}

/** Draws the head pointing along its angle with its tip on the point. */
function head(ctx: CanvasRenderingContext2D, at: Point, angle: number, kind: ArrowKind): void {
  ctx.save();
  ctx.translate(at.x, at.y);
  ctx.rotate(angle);
  ctx.scale(SCALE, SCALE);
  ctx.beginPath();
  ctx.moveTo(0, 0);
  ctx.quadraticCurveTo(-9, 2, -21, 9.5);
  ctx.lineTo(-NECK, 0);
  ctx.lineTo(-21, -9.5);
  ctx.quadraticCurveTo(-9, -2, 0, 0);
  ctx.closePath();
  ctx.shadowColor = rgba(kind.glow, 0.53);
  ctx.shadowBlur = 6;
  ctx.fillStyle = shade(kind.sheath, -0.35);
  ctx.fill();
  ctx.shadowColor = 'transparent';
  ctx.beginPath();
  ctx.moveTo(0, 0);
  ctx.quadraticCurveTo(-9, -2, -21, -9.5);
  ctx.lineTo(-NECK, 0);
  ctx.closePath();
  ctx.fillStyle = kind.sheath;
  ctx.fill();
  ctx.beginPath();
  ctx.moveTo(0, 0);
  ctx.quadraticCurveTo(-8, -1.4, -16, -5);
  ctx.lineTo(-12, 0);
  ctx.closePath();
  ctx.globalAlpha *= 0.55;
  ctx.fillStyle = shade(kind.rim, 0.15);
  ctx.fill();
  ctx.globalAlpha /= 0.55;
  ctx.beginPath();
  ctx.moveTo(-0.5, 0);
  ctx.lineTo(-NECK + 0.5, 0);
  ctx.strokeStyle = kind.core;
  ctx.lineWidth = 0.8;
  ctx.stroke();
  ctx.restore();
}

/** A colour darkened (amount below 0) or lightened (above 0) by that fraction of the way to black or white. */
function shade(hex: string, amount: number): string {
  const n = parseInt(hex.slice(1), 16);
  const move = (v: number) => Math.round(amount < 0 ? v * (1 + amount) : v + (255 - v) * amount);
  return `rgb(${move((n >> 16) & 255)},${move((n >> 8) & 255)},${move(n & 255)})`;
}
