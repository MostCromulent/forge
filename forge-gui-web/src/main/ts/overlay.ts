import { game, derefAll, players, type Model } from './model';
import { setting } from './settings';
import { byId } from './dom';
import { cardElement, pileTopFor } from './motion';
import { ui } from './ui';
import type { CardView, Ref, Refs, StackItemView, TrackedObject } from './protocol';

// Arrows on the full-window canvas: attackers to what they attack, blockers to what they block, and the targets
// of the hovered stack item. All are one arrow in three colours, drawn as the attack chevron is: a coloured body that
// swells and narrows to a neck, a faceted head, and a hairline of white-hot light down both. Red, blue and yellow
// differ in brightness as well as hue, so they stay apart for red-green colour blindness.
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

let settleTimer = 0;
let drawn: Model | null = null;
let repaintQueued = false;

export function initOverlay(schedule: () => void): void {
  window.addEventListener('resize', schedule);
  // A scrolling log or zone panel moves the cards the arrows point at and nothing else, so only the arrows are
  // redrawn, once a frame, rather than the whole page
  document.addEventListener('scroll', () => {
    if (repaintQueued || !drawn) return;
    repaintQueued = true;
    requestAnimationFrame(() => {
      repaintQueued = false;
      if (drawn) paint(drawn);
    });
  }, true);
}

export function drawOverlay(model: Model): void {
  drawn = model;
  paint(model);
  // Cards animate into place (tapping, attacking, flying in from another zone), so measure again until they settle
  clearTimeout(settleTimer);
  const settle = () => {
    paint(model);
    if (boardMoving()) settleTimer = setTimeout(settle, 120);
  };
  settleTimer = setTimeout(settle, 200);
}

/** Whether a card on the board is still on its way somewhere. Endless effects, such as a breathing glow, never settle. */
function boardMoving(): boolean {
  return document.getAnimations().some(a => {
    const target = (a.effect as KeyframeEffect | null)?.target;
    return a.playState === 'running' && a.effect?.getTiming().iterations !== Infinity
      && target instanceof Element && !!target.closest('#match .card, #match .slot');
  });
}

function paint(model: Model): void {
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
  placeCharges(chargingAtPlayer(model));
  // A block being dragged is drawn whatever the arrows setting, as it is the player's own hand on the board
  drawDrag(ctx);
  const mode = setting('arrows');
  if (mode === '0') return;
  // "On hover" keeps combat arrows off and leaves only the ones for the stack item under the pointer
  for (const band of mode === '1' ? [] : g.CombatView ?? []) {
    const attackers = present(band.attackers);
    attackers.forEach((attacker, i) => {
      if (!atFace.has(attacker.ref)) {
        ribbon(ctx, elementFor(attacker.ref), elementFor(band.defender?.ref), KINDS.attack, i, attackers.length);
      }
      for (const blocker of present(band.blockers)) {
        ribbon(ctx, elementFor(blocker.ref), elementFor(attacker.ref), KINDS.block, 0, 1);
      }
      for (const blocker of present(band.plannedBlockers)) {
        ribbon(ctx, elementFor(blocker.ref), elementFor(attacker.ref), KINDS.plannedBlock, 0, 1);
      }
    });
  }
  // A creature that has to block something is tied to what it has to block, while blockers are being declared.
  // Without this an illegal block is simply refused and nothing on screen says why.
  if (g.Phase === 'COMBAT_DECLARE_BLOCKERS') {
    for (const obj of model.objects.values()) {
      const forced = present((obj as CardView).MustBlockCards);
      forced.forEach((attacker, i) =>
        ribbon(ctx, elementFor(obj.$key), elementFor(attacker.ref), KINDS.mustBlock, i, forced.length));
    }
  }
  const item = ui.hoveredStackItem !== null ? model.objects.get(ui.hoveredStackItem) : null;
  if (item) {
    const from = document.querySelector<HTMLElement>(`.stack-item[data-key="${item.$key}"]`);
    const targets = stackTargets(model, item);
    targets.forEach((target, i) => ribbon(ctx, from, elementFor(target.$key), KINDS.target, i, targets.length));
  }
}

const present = (refs: Refs | null | undefined): Ref[] => (refs ?? []).filter((r): r is Ref => !!r);

/** A block being dragged out: from the blocker to the attacker under the pointer, or to the pointer itself. */
let drag: { from: HTMLElement; to: HTMLElement | Point } | null = null;

export function setDragArrow(from: HTMLElement | null, to: HTMLElement | Point | null): void {
  const next = from && to ? { from, to } : null;
  if (!next && !drag) return;
  drag = next;
  if (drawn) paint(drawn);
}

function drawDrag(ctx: CanvasRenderingContext2D): void {
  if (!drag) return;
  if (drag.to instanceof HTMLElement) {
    ribbon(ctx, drag.from, drag.to, KINDS.block, 0, 1);
  } else {
    arrow(ctx, edge(drag.from, drag.to, 2), drag.to, KINDS.block);
  }
}

// The attack mark's drawing (board.css) is 64 by 54 units; its two chevrons fill it from 16 units below the top to 13.7
// above the bottom, and the rest is room for their glow
const MARK_W = 64;
const MARK_H = 54;
const MARK_TOP = 16;
const MARK_BELOW = 13.7;

/** The chevron over each charging attacker, kept between paints so its halo breathes on rather than restarting. */
const charges = new Map<number, HTMLElement>();

/**
 * Puts a chevron over each attacker in keys, measured from the card as it stands on screen, so a tapped card's is
 * over its turned edge. It points up from your side and down from the opponent's, towards the defender.
 */
function placeCharges(keys: Set<number>): void {
  for (const [key, mark] of charges) {
    if (!keys.has(key)) {
      mark.remove();
      charges.delete(key);
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
    if (!mark) {
      mark = document.createElement('div');
      mark.className = 'charge';
      byId('charges').append(mark);
      charges.set(key, mark);
    }
    const r = card.getBoundingClientRect();
    const width = card.offsetWidth * .66;
    const height = width * MARK_H / MARK_W;
    // The chevrons stand a twentieth of the card's width clear of it
    const sink = height * MARK_BELOW / MARK_H - card.offsetWidth * .05;
    const down = !!card.closest('#opponent');
    // A crowded battlefield scrolls, and its front row can sit at the very edge; the chevron stays inside the
    // battlefield's box then, rather than spilling over the phase pill beyond it
    const field = card.closest('.battlefield')?.getBoundingClientRect();
    mark.classList.toggle('down', down);
    mark.style.width = `${width}px`;
    mark.style.left = `${r.left + r.width / 2}px`;
    mark.style.top = `${down
      ? Math.min(r.bottom - sink, (field?.bottom ?? Infinity) - height * (MARK_H - MARK_TOP) / MARK_H)
      : Math.max(r.top + sink, (field?.top ?? -Infinity) + height * (MARK_H - MARK_TOP) / MARK_H)}px`;
  }
}

/**
 * Attackers that can only be attacking the one opponent's face, each with whether it is blocked: in a two-player
 * game, those attacking a player rather than a planeswalker or battle. An arrow would only point at the portrait, so
 * none of them gets one.
 */
function atLoneFace(model: Model): Map<number, boolean> {
  const out = new Map<number, boolean>();
  const everyone = players(model);
  if (everyone.length !== 2) return out;
  const faces = new Set(everyone.map(p => p.$key));
  for (const band of game(model)?.CombatView ?? []) {
    const blocked = present(band.blockers).length > 0 || present(band.plannedBlockers).length > 0;
    if (band.defender && faces.has(band.defender.ref)) present(band.attackers).forEach(a => out.set(a.ref, blocked));
  }
  return out;
}

/**
 * The attackers at the lone opponent's face that wear a chevron: the unblocked ones. A blocked one's block arrow is
 * what matters now, and a chevron at the face would say otherwise.
 */
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

// Several arrivals on one defender land on separate points, so six attackers stay countable
function spread(a: Point, b: Point, index: number, count: number): Point {
  if (count < 2) return b;
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const len = Math.hypot(dx, dy) || 1;
  const off = (index - (count - 1) / 2) * Math.min(22, 90 / count);
  return { x: b.x + (-dy / len) * off, y: b.y + (dx / len) * off };
}

function ribbon(ctx: CanvasRenderingContext2D, fromEl: HTMLElement | null, toEl: HTMLElement | null, kind: ArrowKind, index: number, count: number): void {
  if (!fromEl || !toEl || fromEl === toEl) return;
  const target = spread(center(fromEl), center(toEl), index, count);
  const a = edge(fromEl, target, 2);
  const b = edge(toEl, a, 6);
  arrow(ctx, a, spread(a, b, index, count), kind);
}

function arrow(ctx: CanvasRenderingContext2D, a: Point, end: Point, kind: ArrowKind): void {
  // A deeper bow keeps two arrows between the same rows apart and reads as a throw rather than a ruler line
  const bow = 0.34;
  const bend = { x: (a.x + end.x) / 2 + (end.y - a.y) * bow, y: (a.y + end.y) / 2 - (end.x - a.x) * bow };
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

/**
 * The head, drawn pointing along its angle with its tip on the point: two barbs swept back on curved edges, one facet
 * in shadow and one lit, and a hairline ridge of white-hot light between them.
 */
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
