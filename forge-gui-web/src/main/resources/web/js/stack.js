import { reconcile } from './render.js';
import { imageUrl } from './cards.js';
import { game, deref, stateOf } from './model.js';
import { hoverCard } from './detail.js';
import { hoverStackItem, stackTargets } from './overlay.js';

// The stack as a pile of cards over the board's right edge. The top item (first in the list) is in front on the
// right; older items fan out to the left behind it. Hovering an item lifts it and spreads its neighbours apart.
const OFFSET_X = 36;
const OFFSET_Y = 4;
const PUSH_X = 42;

let hovered = null;
let collapsed = false;
let lastModel = null;
let send = () => {};

export function initStack(sendFn) {
  send = sendFn;
  document.addEventListener('click', e => {
    if (!e.target.closest('#stack-menu')) closeMenu();
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') closeMenu();
  });
}

export function renderStack(model) {
  lastModel = model;
  const root = document.getElementById('stack');
  if (!root.firstChild) {
    root.innerHTML = '<div class="pile"></div><button class="collapse"></button>';
    root.querySelector('.collapse').onclick = () => {
      collapsed = !collapsed;
      renderStack(lastModel);
    };
    window.addEventListener('resize', () => place(root));
  }
  const items = (game(model)?.Stack ?? []).map(r => model.objects.get(r.ref)).filter(Boolean);
  if (!items.some(i => i.$key === hovered)) hovered = null;
  root.hidden = items.length === 0;
  root.classList.toggle('collapsed', collapsed);
  root.querySelector('.collapse').textContent = collapsed ? `Stack · ${items.length}` : 'Hide';
  root.querySelector('.collapse').title = collapsed ? 'Show the stack' : 'Collapse the stack to the edge';
  const pile = root.querySelector('.pile');
  reconcile(pile, items, i => i.$key, createItem, (el, item) => updateItem(el, model, item));
  layout(pile, items.length);
  place(root);
}

// Just above the phase strip, so the pile never covers the turn line
function place(root) {
  const strip = document.getElementById('phase-strip').getBoundingClientRect();
  root.style.top = `${Math.max(8, strip.top - root.offsetHeight - 8)}px`;
}

function createItem() {
  const el = document.createElement('div');
  el.className = 'stack-item';
  el.innerHTML = '<img alt="" draggable="false"><div class="frame"></div><div class="caption"><div class="who"></div><div class="desc"></div><div class="targets"></div></div>';
  const img = el.querySelector('img');
  img.addEventListener('error', () => el.classList.add('noimg'));
  el.addEventListener('mouseenter', () => {
    hovered = Number(el.dataset.key);
    layout(el.parentElement, el.parentElement.childElementCount);
    hoverStackItem(hovered);
    hoverCard(img);
  });
  // Desktop's stack menu: auto-yield, always accept or decline your optional trigger, yield to the stack
  el.addEventListener('contextmenu', e => {
    e.preventDefault();
    menuAt = { x: e.clientX, y: e.clientY };
    send({ t: 'stackMenu', key: Number(el.dataset.key) });
  });
  el.addEventListener('mouseleave', () => {
    hovered = null;
    layout(el.parentElement, el.parentElement.childElementCount);
    hoverStackItem(null);
    hoverCard(null);
  });
  return el;
}

function updateItem(el, model, item) {
  const source = deref(model, item.SourceCard);
  const state = source ? stateOf(model, source) : {};
  const src = source && model.visible.has(source.$key) && state.ImageKey ? imageUrl(state.ImageKey) : '';
  const img = el.querySelector('img');
  if (img.getAttribute('src') !== src) {
    el.classList.toggle('noimg', !src);
    if (src) img.src = src;
  }
  img.dataset.key = source?.$key ?? '';
  img.dataset.zoom = src;
  el.querySelector('.frame').textContent = state.Name ?? '';
  el.querySelector('.who').textContent = deref(model, item.ActivatingPlayer)?.Name ?? '';
  // A spell's image says what it does; an ability needs its text
  el.querySelector('.desc').textContent = item.Ability || !src ? (item.Description ?? '') : '';
  const targets = stackTargets(model, item).map(t => t.Name ?? stateOf(model, t).Name ?? '?');
  el.querySelector('.targets').textContent = targets.length ? `→ ${targets.join(', ')}` : '';
}

function layout(pile, n) {
  const items = [...pile.children];
  const h = items.findIndex(el => Number(el.dataset.key) === hovered);
  pile.style.setProperty('--span', `${Math.max(0, n - 1) * OFFSET_X + 2 * PUSH_X}px`);
  items.forEach((el, i) => {
    const push = h < 0 || i === h ? 0 : (i < h ? PUSH_X : -PUSH_X);
    el.style.left = `${(n - 1 - i) * OFFSET_X + PUSH_X + push}px`;
    el.style.top = `${i * OFFSET_Y}px`;
    el.style.zIndex = h < 0 ? n - i : 200 - Math.abs(i - h) * 10 + (i === h ? 5 : 0);
    el.classList.toggle('top', i === 0);
    el.classList.toggle('lifted', i === h);
  });
}

let menuAt = null;

// The server answers a right-click with what applies to that item and the current settings
export function onStackMenu(msg) {
  if (!menuAt) return;
  closeMenu();
  const menu = document.createElement('div');
  menu.id = 'stack-menu';
  menu.style.left = `${menuAt.x}px`;
  menu.style.top = `${menuAt.y}px`;
  const item = (label, action, checked) => {
    const b = document.createElement('button');
    b.textContent = (checked === undefined ? '' : checked ? '✓ ' : '    ') + label;
    b.onclick = () => {
      send({ t: 'stackYield', key: msg.key, action });
      closeMenu();
    };
    menu.append(b);
  };
  if (msg.autoYield !== undefined) item('Auto-yield to this ability', 'autoYield', msg.autoYield);
  if (msg.trigger !== undefined) {
    item('Always accept this trigger', 'alwaysYes', msg.trigger === 'ACCEPT');
    item('Always decline this trigger', 'alwaysNo', msg.trigger === 'DECLINE');
  }
  item('Yield until this resolves', 'yieldToStack');
  item('Yield until the stack is empty', 'yieldToEntireStack');
  document.body.append(menu);
}

function closeMenu() {
  document.getElementById('stack-menu')?.remove();
}
