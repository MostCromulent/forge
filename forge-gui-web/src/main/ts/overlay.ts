import { game, derefAll, type Model } from './model';
import { setting } from './settings';
import { byId } from './dom';
import type { StackItemView, TrackedObject } from './protocol';

// Arrows on the full-window canvas: attackers to what they attack, blockers to what they block, and the targets
// of the hovered stack item. Each is a soft band with a bright core. Colour names the kind, and width, dash and
// head repeat it, so nothing rests on telling two hues apart.
interface ArrowKind {
  color: string;
  band: number;
  core: number;
  dash: number[];
  head: 'spear' | 'chevron' | 'reticle';
}

const KINDS: Record<'attack' | 'block' | 'plannedBlock' | 'target', ArrowKind> = {
  attack: { color: '#ff7a59', band: 13, core: 2.4, dash: [], head: 'spear' },
  block: { color: '#5cc8ff', band: 8, core: 2, dash: [11, 7], head: 'chevron' },
  plannedBlock: { color: '#8fb7cc', band: 6, core: 2, dash: [2, 8], head: 'chevron' },
  target: { color: '#ffcc33', band: 6, core: 1.8, dash: [3, 7], head: 'reticle' },
};

interface Point {
  x: number;
  y: number;
}

let hoveredStackItem: number | null = null;
let settleTimer = 0;
let schedule: () => void = () => {};

export function initOverlay(scheduleFn: () => void): void {
  schedule = scheduleFn;
  window.addEventListener('resize', schedule);
  // A scrolling log or zone panel moves the cards the arrows point at; schedule coalesces to one render per frame
  document.addEventListener('scroll', schedule, true);
}

export function hoverStackItem(key: number | null): void {
  hoveredStackItem = key;
  schedule();
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
    const attackers = band.attackers ?? [];
    attackers.forEach((attacker, i) => {
      ribbon(ctx, elementFor(attacker.ref), elementFor(band.defender?.ref), KINDS.attack, i, attackers.length);
      for (const blocker of band.blockers ?? []) {
        ribbon(ctx, elementFor(blocker.ref), elementFor(attacker.ref), KINDS.block, 0, 1);
      }
      for (const blocker of band.plannedBlockers ?? []) {
        ribbon(ctx, elementFor(blocker.ref), elementFor(attacker.ref), KINDS.plannedBlock, 0, 1);
      }
    });
  }
  const item = hoveredStackItem !== null ? model.objects.get(hoveredStackItem) : null;
  if (item) {
    const from = document.querySelector<HTMLElement>(`.stack-item[data-key="${item.$key}"]`);
    const targets = stackTargets(model, item);
    targets.forEach((target, i) => ribbon(ctx, from, elementFor(target.$key), KINDS.target, i, targets.length));
  }
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
  const path = () => {
    ctx.beginPath();
    ctx.moveTo(a.x, a.y);
    ctx.quadraticCurveTo(bend.x, bend.y, end.x, end.y);
  };
  ctx.lineCap = 'round';
  ctx.setLineDash([]);
  // The band is the shape; the core is the line the eye follows
  for (const [style, width] of [['rgba(0,0,0,.5)', kind.band + 4], [rgba(kind.color, 0.12), kind.band],
    [rgba(kind.color, 0.24), kind.band * 0.45]] as const) {
    ctx.strokeStyle = style;
    ctx.lineWidth = width;
    path();
    ctx.stroke();
  }
  ctx.setLineDash(kind.dash);
  ctx.strokeStyle = rgba(kind.color, 0.98);
  ctx.lineWidth = kind.core;
  path();
  ctx.stroke();
  ctx.setLineDash([]);
  head(ctx, end, Math.atan2(end.y - bend.y, end.x - bend.x), kind);
}

function head(ctx: CanvasRenderingContext2D, at: Point, angle: number, kind: ArrowKind): void {
  const point = (len: number, spread: number): [Point, Point] => [
    { x: at.x - len * Math.cos(angle - spread), y: at.y - len * Math.sin(angle - spread) },
    { x: at.x - len * Math.cos(angle + spread), y: at.y - len * Math.sin(angle + spread) },
  ];
  ctx.strokeStyle = 'rgba(0,0,0,.6)';
  if (kind.head === 'reticle') {
    ctx.lineWidth = 4;
    ctx.beginPath();
    ctx.arc(at.x, at.y, 9, 0, Math.PI * 2);
    ctx.stroke();
    ctx.strokeStyle = rgba(kind.color, 1);
    ctx.lineWidth = 2;
    ctx.stroke();
    return;
  }
  const [l, r] = point(kind.head === 'spear' ? 17 : 14, 0.45);
  ctx.beginPath();
  ctx.moveTo(l.x, l.y);
  ctx.lineTo(at.x, at.y);
  ctx.lineTo(r.x, r.y);
  if (kind.head === 'spear') {
    ctx.closePath();
    ctx.lineWidth = 3;
    ctx.stroke();
    ctx.fillStyle = rgba(kind.color, 1);
    ctx.fill();
    return;
  }
  ctx.lineWidth = 6;
  ctx.stroke();
  ctx.strokeStyle = rgba(kind.color, 1);
  ctx.lineWidth = 3.2;
  ctx.stroke();
}
