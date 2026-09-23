import { reconcile } from './render';
import { cardImageSrc, noImageOnError, setImage } from './images';
import { game, deref, derefAll, stateOf, type Model } from './model';
import { hoverCard } from './detail';
import { hoverStackItem, stackTargets } from './overlay';
import { byId, q } from './dom';
import type { Send, StackItemView, StackMenu } from './protocol';

// The stack as a panel on the board's right edge: what resolves next is the card at the top, and the rest
// cascade down behind it. Hovering an item lifts it and pushes its neighbours apart.
const STEP_MAX = 42;
const STEP_MIN = 14;
const PUSH_Y = 26;

let hovered: number | null = null;
let collapsed = false;
let send: Send = () => {};
let schedule: () => void = () => {};

export function initStack(sendFn: Send, scheduleFn: () => void): void {
  send = sendFn;
  schedule = scheduleFn;
  document.addEventListener('click', e => {
    if (!(e.target instanceof Element && e.target.closest('#stack-menu'))) closeMenu();
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') closeMenu();
  });
}

let pickable: number[] | null = null;
let onPick: ((index: number) => void) | null = null;

/** A choice the browser answers by clicking a spell on the stack rather than reading it from a list. */
export function awaitStackPick(keys: number[] | null, answer: ((index: number) => void) | null): void {
  pickable = keys;
  onPick = answer;
}

export function stackPickWanted(): boolean {
  return !!pickable;
}

function pickStack(key: number): void {
  const index = (pickable ?? []).indexOf(key);
  if (index >= 0 && onPick) {
    const answer = onPick;
    pickable = null;
    onPick = null;
    answer(index);
  }
}

export function renderStack(model: Model): void {
  const root = byId('stack');
  if (!root.firstChild) {
    root.innerHTML = '<div class="head"><b>Stack</b><span class="count"></span><button class="collapse"></button></div><div class="pile"></div>';
    q(root, '.collapse').onclick = () => {
      collapsed = !collapsed;
      schedule();
    };
    window.addEventListener('resize', () => place(root));
  }
  const items: StackItemView[] = derefAll(model, game(model)?.Stack);
  if (!items.some(i => i.$key === hovered)) hovered = null;
  root.hidden = items.length === 0;
  root.classList.toggle('collapsed', collapsed);
  // The battlefield rows have no idea the panel is there, so the board is told to keep clear of it
  byId('match').classList.toggle('stack-open', !root.hidden && !collapsed);
  q(root, '.count').textContent = String(items.length);
  const collapse = q(root, '.collapse');
  collapse.textContent = collapsed ? 'Show' : 'Hide';
  collapse.title = collapsed ? 'Show the stack' : 'Collapse the stack to its heading';
  const pile = q(root, '.pile');
  reconcile(pile, items, i => i.$key, createItem, (el, item) => {
    updateItem(el, model, item);
    el.classList.toggle('targetable', (pickable ?? []).includes(item.$key));
  });
  place(root);
  layout(pile, items.length);
}

// The panel hangs from the top of the board and stops short of the hand
function place(root: HTMLElement): void {
  const hand = byId('hand').getBoundingClientRect();
  q(root, '.pile').style.setProperty('--stack-room', `${Math.max(120, hand.top - root.getBoundingClientRect().top - 48)}px`);
}

function createItem(): HTMLElement {
  const el = document.createElement('div');
  el.className = 'stack-item';
  el.innerHTML = '<img alt="" draggable="false"><div class="frame"></div><div class="caption"><div class="who"></div><div class="desc"></div><div class="targets"></div></div>';
  const img = q<HTMLImageElement>(el, 'img');
  noImageOnError(el, img);
  // A spell being targeted is picked on the stack, where it already is
  el.addEventListener('click', () => pickStack(Number(el.dataset.key)));
  el.addEventListener('mouseenter', () => {
    hovered = Number(el.dataset.key);
    const pile = el.parentElement as HTMLElement;
    layout(pile, pile.childElementCount);
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
    const pile = el.parentElement as HTMLElement;
    layout(pile, pile.childElementCount);
    hoverStackItem(null);
    hoverCard(null);
  });
  return el;
}

function updateItem(el: HTMLElement, model: Model, item: StackItemView): void {
  const source = deref(model, item.SourceCard);
  const state = source ? stateOf(model, source) : {};
  const src = cardImageSrc(model, source);
  const img = q<HTMLImageElement>(el, 'img');
  setImage(img, src);
  el.classList.toggle('noimg', !src);
  img.dataset.key = String(source?.$key ?? '');
  img.dataset.zoom = src;
  q(el, '.frame').textContent = state.Name ?? '';
  q(el, '.who').textContent = deref(model, item.ActivatingPlayer)?.Name ?? '';
  // A spell's image says what it does; an ability needs its text
  q(el, '.desc').textContent = item.Ability || !src ? (item.Description ?? '') : '';
  const targets = stackTargets(model, item).map(t => t.Name ?? stateOf(model, t).Name ?? '?');
  q(el, '.targets').textContent = targets.length ? `→ ${targets.join(', ')}` : '';
}

// Every item keeps a strip of itself visible, so a deep stack simply cascades more tightly
function layout(pile: HTMLElement, n: number): void {
  const items = [...pile.children] as HTMLElement[];
  if (!items.length) {
    return;
  }
  const h = items.findIndex(el => Number(el.dataset.key) === hovered);
  const card = items[0].offsetHeight;
  const room = parseFloat(getComputedStyle(pile).getPropertyValue('--stack-room')) || 400;
  const step = n > 1 ? Math.min(STEP_MAX, Math.max(STEP_MIN, (room - card) / (n - 1))) : 0;
  pile.style.height = `${(n - 1) * step + card}px`;
  items.forEach((el, i) => {
    const push = h < 0 || i === h ? 0 : (i < h ? -PUSH_Y : PUSH_Y);
    el.style.top = `${i * step + push}px`;
    el.style.zIndex = String(h < 0 ? n - i : 200 - Math.abs(i - h) * 10 + (i === h ? 5 : 0));
    el.classList.toggle('top', i === 0);
    el.classList.toggle('lifted', i === h);
  });
}

let menuAt: { x: number; y: number } | null = null;

// The server answers a right-click with what applies to that item and the current settings
export function onStackMenu(msg: StackMenu): void {
  if (!menuAt) return;
  const at = menuAt;
  menuAt = null;
  closeMenu();
  const menu = document.createElement('div');
  menu.id = 'stack-menu';
  menu.style.left = `${at.x}px`;
  menu.style.top = `${at.y}px`;
  const item = (label: string, action: string, checked?: boolean) => {
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

function closeMenu(): void {
  document.getElementById('stack-menu')?.remove();
}
