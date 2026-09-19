import { game, derefAll } from './model.js';

// Arrows on the full-window canvas: attackers to what they attack, blockers to what they block, and the targets
// of the hovered stack item. Styles differ by dash and luminance, never by red versus green.
const STYLES = {
  attack: { color: '#ffffff', dash: [] },
  block: { color: '#ffffff', dash: [8, 6] },
  target: { color: '#ffcc33', dash: [] },
};

let hoveredStackItem = null;
let lastModel = null;
let settleTimer = 0;

export function initOverlay() {
  window.addEventListener('resize', () => drawOverlay(lastModel));
  document.addEventListener('scroll', () => drawOverlay(lastModel), true);
}

export function hoverStackItem(key) {
  hoveredStackItem = key;
  drawOverlay(lastModel);
}

export function drawOverlay(model) {
  lastModel = model;
  paint(model);
  // Cards animate into place (tapping, attacking), so measure again once they settle
  clearTimeout(settleTimer);
  settleTimer = setTimeout(() => paint(lastModel), 200);
}

function paint(model) {
  const canvas = document.getElementById('overlay');
  const ratio = window.devicePixelRatio || 1;
  canvas.width = innerWidth * ratio;
  canvas.height = innerHeight * ratio;
  canvas.style.width = `${innerWidth}px`;
  canvas.style.height = `${innerHeight}px`;
  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  const g = model && game(model);
  if (!g || document.getElementById('match').hidden) return;
  for (const band of g.CombatView ?? []) {
    for (const attacker of band.attackers ?? []) {
      arrow(ctx, elementFor(attacker.ref), elementFor(band.defender?.ref), STYLES.attack);
      for (const blocker of band.blockers ?? []) {
        arrow(ctx, elementFor(blocker.ref), elementFor(attacker.ref), STYLES.block);
      }
    }
  }
  const item = hoveredStackItem !== null ? model.objects.get(hoveredStackItem) : null;
  if (item) {
    const from = document.querySelector(`.stack-item[data-key="${item.$key}"]`);
    for (const target of stackTargets(model, item)) arrow(ctx, from, elementFor(target.$key), STYLES.target);
  }
}

export function stackTargets(model, item) {
  const out = [];
  for (let i = item; i; i = model.objects.get(i.SubInstance?.ref)) {
    out.push(...derefAll(model, i.TargetCards), ...derefAll(model, i.TargetPlayers));
  }
  return out;
}

// A card inside a pile is drawn by the pile's top card
function elementFor(key) {
  if (key === undefined) return null;
  const player = document.querySelector(`.seat[data-player="${key}"] .avatar`);
  if (player) return player;
  const card = document.querySelector(`#opponent .card[data-key="${key}"], #me .card[data-key="${key}"], #hand .card[data-key="${key}"], #zones .card[data-key="${key}"]`);
  if (card) return card;
  const pile = [...document.querySelectorAll('.slot[data-members]')].find(s => s.dataset.members.split(',').includes(String(key)));
  return pile?.lastChild ?? null;
}

function center(el) {
  const r = el.getBoundingClientRect();
  return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
}

function arrow(ctx, fromEl, toEl, style) {
  if (!fromEl || !toEl || fromEl === toEl) return;
  const a = center(fromEl);
  const b = center(toEl);
  const bend = { x: (a.x + b.x) / 2 + (b.y - a.y) * 0.15, y: (a.y + b.y) / 2 - (b.x - a.x) * 0.15 };
  const angle = Math.atan2(b.y - bend.y, b.x - bend.x);
  const path = () => {
    ctx.beginPath();
    ctx.moveTo(a.x, a.y);
    ctx.quadraticCurveTo(bend.x, bend.y, b.x, b.y);
  };
  const head = () => {
    ctx.beginPath();
    ctx.moveTo(b.x, b.y);
    ctx.lineTo(b.x - 16 * Math.cos(angle - 0.4), b.y - 16 * Math.sin(angle - 0.4));
    ctx.lineTo(b.x - 16 * Math.cos(angle + 0.4), b.y - 16 * Math.sin(angle + 0.4));
    ctx.closePath();
  };
  ctx.lineCap = 'round';
  ctx.setLineDash(style.dash);
  ctx.strokeStyle = '#000000b0';
  ctx.lineWidth = 7;
  path();
  ctx.stroke();
  ctx.strokeStyle = style.color;
  ctx.lineWidth = 3;
  path();
  ctx.stroke();
  ctx.setLineDash([]);
  ctx.fillStyle = style.color;
  ctx.strokeStyle = '#000000b0';
  ctx.lineWidth = 2;
  head();
  ctx.fill();
  ctx.stroke();
}
