import { reconcile } from './render';
import { cardImageSrc, noImageOnError, setImage } from './images';
import { game, deref, derefAll, stackPick, stateOf, type Model } from './model';
import { hoverCard } from './detail';
import { stackTargets } from './overlay';
import { byId, q } from './dom';
import { changeUi, ui } from './ui';
import type { Actions } from './actions';
import type { StackItemView, YieldAction } from './protocol';

// The stack as a panel on the board's right edge: what resolves next is the card at the top, and the rest
// cascade down behind it. Hovering an item lifts it and pushes its neighbours apart.
const STEP_MAX = 42;
const STEP_MIN = 14;
const PUSH_Y = 26;

let actions: Actions | null = null;

export function initStack(actionsFor: Actions): void {
  actions = actionsFor;
  document.addEventListener('click', e => {
    if (ui.stackMenuAt && !(e.target instanceof Element && e.target.closest('#stack-menu'))) {
      changeUi(u => { u.stackMenuAt = null; });
    }
  });
}

export function renderStack(model: Model): void {
  const root = byId('stack');
  if (!root.firstChild) {
    root.innerHTML = '<div class="head"><b>Stack</b><span class="count"></span><button class="collapse"></button></div><div class="pile"></div>';
    q(root, '.collapse').onclick = () => changeUi(u => { u.stackCollapsed = !u.stackCollapsed; });
    window.addEventListener('resize', () => place(root));
  }
  const items: StackItemView[] = derefAll(model, game(model)?.Stack);
  if (ui.hoveredStackItem !== null && !items.some(i => i.$key === ui.hoveredStackItem)) {
    ui.hoveredStackItem = null;
  }
  const collapsed = ui.stackCollapsed;
  root.hidden = items.length === 0;
  root.classList.toggle('collapsed', collapsed);
  // The battlefield rows have no idea the panel is there, so the board is told to keep clear of it
  byId('match').classList.toggle('stack-open', !root.hidden && !collapsed);
  q(root, '.count').textContent = String(items.length);
  const collapse = q(root, '.collapse');
  collapse.textContent = collapsed ? 'Show' : 'Hide';
  collapse.title = collapsed ? 'Show the stack' : 'Collapse the stack to its heading';
  const pile = q(root, '.pile');
  const pick = stackPick(model);
  reconcile(pile, items, i => i.$key, () => createItem(model), (el, item) => {
    updateItem(el, model, item);
    el.classList.toggle('targetable', (pick?.stackKeys ?? []).includes(item.$key));
  });
  place(root);
  layout(pile, items.length);
  renderMenu(model);
}

// The panel hangs from the top of the board and stops short of the hand
function place(root: HTMLElement): void {
  const hand = byId('hand').getBoundingClientRect();
  q(root, '.pile').style.setProperty('--stack-room', `${Math.max(120, hand.top - root.getBoundingClientRect().top - 48)}px`);
}

function createItem(model: Model): HTMLElement {
  const el = document.createElement('div');
  el.className = 'stack-item';
  el.innerHTML = '<img alt="" draggable="false"><div class="frame"></div><div class="caption"><div class="who"></div><div class="desc"></div><div class="targets"></div></div>';
  const img = q<HTMLImageElement>(el, 'img');
  noImageOnError(el, img);
  // A spell being chosen is picked on the stack, where it already is
  el.addEventListener('click', () => {
    const pick = stackPick(model);
    const index = (pick?.stackKeys ?? []).indexOf(Number(el.dataset.key));
    if (pick && index >= 0) {
      actions?.answer(pick.id, [index]);
    }
  });
  el.addEventListener('mouseenter', () => {
    const pile = el.parentElement as HTMLElement;
    // Lifted at once, rather than on the next frame, so the pile answers the pointer as it moves along it
    ui.hoveredStackItem = Number(el.dataset.key);
    layout(pile, pile.childElementCount);
    hoverCard(img);
  });
  // Desktop's stack menu: auto-yield, always accept or decline your optional trigger, yield to the stack
  el.addEventListener('contextmenu', e => {
    e.preventDefault();
    const key = Number(el.dataset.key);
    changeUi(u => { u.stackMenuAt = { key, x: e.clientX, y: e.clientY }; });
    actions?.askStackMenu(key);
  });
  el.addEventListener('mouseleave', () => {
    const pile = el.parentElement as HTMLElement;
    ui.hoveredStackItem = null;
    layout(pile, pile.childElementCount);
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
  const h = items.findIndex(el => Number(el.dataset.key) === ui.hoveredStackItem);
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

/** What the menu open on the page was drawn for, so it is rebuilt only when that changes. */
let menuDrawn = '';

// The server answers a right-click with what applies to that item and the current settings; the menu opens where
// the click was once that answer is in
function renderMenu(model: Model): void {
  const at = ui.stackMenuAt;
  const answer = model.stackMenu;
  const wanted = at && answer && answer.key === at.key ? JSON.stringify([at, answer]) : '';
  if (wanted === menuDrawn) {
    return;
  }
  menuDrawn = wanted;
  document.getElementById('stack-menu')?.remove();
  if (!at || !answer || !wanted) {
    return;
  }
  const menu = document.createElement('div');
  menu.id = 'stack-menu';
  menu.style.left = `${at.x}px`;
  menu.style.top = `${at.y}px`;
  const item = (label: string, action: YieldAction, checked?: boolean) => {
    const b = document.createElement('button');
    b.textContent = (checked === undefined ? '' : checked ? '✓ ' : '    ') + label;
    b.onclick = () => {
      actions?.stackYield(answer.key, action);
      changeUi(u => { u.stackMenuAt = null; });
    };
    menu.append(b);
  };
  if (answer.autoYield !== undefined) item('Auto-yield to this ability', 'autoYield', answer.autoYield);
  if (answer.trigger !== undefined) {
    item('Always accept this trigger', 'alwaysYes', answer.trigger === 'ACCEPT');
    item('Always decline this trigger', 'alwaysNo', answer.trigger === 'DECLINE');
  }
  item('Yield until this resolves', 'yieldToStack');
  item('Yield until the stack is empty', 'yieldToEntireStack');
  document.body.append(menu);
}
