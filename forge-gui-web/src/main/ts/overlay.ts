import { game, derefAll, type Model } from './model';
import { setting } from './settings';
import { byId } from './dom';
import { ui } from './ui';
import type { CardView, Ref, Refs, StackItemView, TrackedObject } from './protocol';

// Arrows on the full-window canvas: attackers to what they attack, blockers to what they block, and the targets
// of the hovered stack item. Each is a band that widens towards its target, with a bright core down the middle.
// Colour names the kind, and width, dash and head repeat it, so nothing rests on telling two hues apart.
interface ArrowKind {
  color: string;
  band: number;
  core: number;
  dash: number[];
  head: 'spear' | 'chevron' | 'reticle';
}

const KINDS: Record<'attack' | 'block' | 'plannedBlock' | 'target' | 'mustBlock', ArrowKind> = {
  attack: { color: '#ff7a59', band: 16, core: 3.8, dash: [], head: 'spear' },
  block: { color: '#5cc8ff', band: 13, core: 3, dash: [13, 8], head: 'chevron' },
  plannedBlock: { color: '#8fb7cc', band: 10, core: 2.6, dash: [2, 9], head: 'chevron' },
  target: { color: '#ffcc33', band: 9, core: 2.2, dash: [3, 8], head: 'reticle' },
  // An obligation rather than a choice, so it is drawn thin and tight-dashed, unlike the block a player makes
  mustBlock: { color: '#f2c344', band: 8, core: 2, dash: [4, 5], head: 'chevron' },
};

interface Point {
  x: number;
  y: number;
}

let settleTimer = 0;

export function initOverlay(schedule: () => void): void {
  window.addEventListener('resize', schedule);
  // A scrolling log or zone panel moves the cards the arrows point at; schedule coalesces to one render per frame
  document.addEventListener('scroll', schedule, true);
}

export function drawOverlay(model: Model): void {
  paint(model);
  // Cards animate into place (tapping, attacking), so measure again once they settle
  clearTimeout(settleTimer);
  settleTimer = setTimeout(() => paint(model), 200);
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
  if (!g || byId('match').hidden) return;
  const mode = setting('arrows');
  if (mode === '0') return;
  // "On hover" keeps combat arrows off and leaves only the ones for the stack item under the pointer
  for (const band of mode === '1' ? [] : g.CombatView ?? []) {
    const attackers = present(band.attackers);
    attackers.forEach((attacker, i) => {
      ribbon(ctx, elementFor(attacker.ref), elementFor(band.defender?.ref), KINDS.attack, i, attackers.length);
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
  const player = document.querySelector<HTMLElement>(`.seat[data-player="${key}"] .avatar`);
  if (player) return player;
  const card = document.querySelector<HTMLElement>(`#opponent .card[data-key="${key}"], #me .card[data-key="${key}"], #hand .card[data-key="${key}"], #zones .card[data-key="${key}"]`);
  if (card) return card;
  const pile = [...document.querySelectorAll<HTMLElement>('.slot[data-members]')].find(s => (s.dataset.members ?? '').split(',').includes(String(key)));
  return (pile?.lastChild as HTMLElement | null | undefined) ?? null;
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
  const end = spread(a, b, index, count);
  // A deeper bow keeps two arrows between the same rows apart and reads as a throw rather than a ruler line
  const bow = 0.34;
  const bend = { x: (a.x + end.x) / 2 + (end.y - a.y) * bow, y: (a.y + end.y) / 2 - (end.x - a.x) * bow };
  // A spear's head is solid, so the band and line stop at its waist rather than run on under it to the tip
  const [bodyBend, bodyEnd] = kind.head === 'spear' ? trim(a, bend, end, SPEAR_WAIST) : [bend, end];
  const path = () => {
    ctx.beginPath();
    ctx.moveTo(a.x, a.y);
    ctx.quadraticCurveTo(bodyBend.x, bodyBend.y, bodyEnd.x, bodyEnd.y);
  };
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.setLineDash([]);
  // The band is the shape; the core is the line the eye follows. It narrows at the source and darkens towards
  // the target, so which end is which reads without following the curve.
  taper(ctx, a, bodyBend, bodyEnd, t => (kind.band / 2) * (0.18 + 0.82 * Math.pow(t, 0.75)));
  ctx.strokeStyle = 'rgba(0,0,0,.55)';
  ctx.lineWidth = 3;
  ctx.stroke();
  const wash = ctx.createLinearGradient(a.x, a.y, end.x, end.y);
  wash.addColorStop(0, rgba(kind.color, 0.06));
  wash.addColorStop(1, rgba(kind.color, 0.34));
  ctx.fillStyle = wash;
  ctx.fill();
  ctx.setLineDash(kind.dash);
  ctx.strokeStyle = rgba(kind.color, 0.98);
  ctx.lineWidth = kind.core;
  path();
  ctx.stroke();
  ctx.setLineDash([]);
  head(ctx, end, Math.atan2(end.y - bend.y, end.x - bend.x), kind);
}

/** How far back from its tip a spear head narrows to its waist. */
const SPEAR_WAIST = 13;

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

function head(ctx: CanvasRenderingContext2D, at: Point, angle: number, kind: ArrowKind): void {
  const point = (len: number, spread: number): [Point, Point] => [
    { x: at.x - len * Math.cos(angle - spread), y: at.y - len * Math.sin(angle - spread) },
    { x: at.x - len * Math.cos(angle + spread), y: at.y - len * Math.sin(angle + spread) },
  ];
  ctx.strokeStyle = 'rgba(0,0,0,.6)';
  if (kind.head === 'reticle') {
    ctx.lineWidth = 5;
    ctx.beginPath();
    ctx.arc(at.x, at.y, 11, 0, Math.PI * 2);
    ctx.stroke();
    ctx.strokeStyle = rgba(kind.color, 1);
    ctx.lineWidth = 2.6;
    ctx.stroke();
    return;
  }
  if (kind.head === 'spear') {
    // A barbed head: the outer points swept back past a waist, so the tip reads at a glance on a busy board
    const [l, r] = point(23, 0.42);
    const [wl, wr] = point(13, 0.2);
    ctx.beginPath();
    ctx.moveTo(at.x, at.y);
    ctx.lineTo(l.x, l.y);
    ctx.lineTo(wl.x, wl.y);
    ctx.lineTo(wr.x, wr.y);
    ctx.lineTo(r.x, r.y);
    ctx.closePath();
    ctx.lineWidth = 3.5;
    ctx.stroke();
    ctx.fillStyle = rgba(kind.color, 1);
    ctx.fill();
    return;
  }
  const [l, r] = point(18, 0.45);
  ctx.beginPath();
  ctx.moveTo(l.x, l.y);
  ctx.lineTo(at.x, at.y);
  ctx.lineTo(r.x, r.y);
  ctx.lineWidth = 7.5;
  ctx.stroke();
  ctx.strokeStyle = rgba(kind.color, 1);
  ctx.lineWidth = 4;
  ctx.stroke();
}
