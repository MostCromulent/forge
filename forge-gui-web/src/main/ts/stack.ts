import { reconcile } from './render';
import { lastPicture } from './cards';
import { cardImageSrc, noImageOnError, setImage } from './images';
import { game, deref, derefAll, me, stackPick, stateOf, type Model } from './model';
import { isPortrait } from './form';
import { hoverCard, hoverable, inspectCard } from './detail';
import { journeys } from './motion';
import { stackTargets } from './overlay';
import { hovers, longPress } from './press';
import { byId, q } from './dom';
import { changeUi, ui } from './ui';
import type { Actions } from './actions';
import { ICONS } from './gamemenu';
import { leave } from './closing';
import { t } from './text';
import type { CardView, GameEvent, StackItemView, YieldAction } from './protocol';

// What resolves next is the card at the top of the pile, and the rest cascade down behind it
const STEP_MAX = 42;
const STEP_MIN = 14;
const PUSH_Y = 26;

let actions: Actions | null = null;

/** Spells being cast, by card key, with a kept picture because the card is missing from the browser's game until its cost is paid. */
const awaiting = new Map<string, { src: string; zoom: string; since: number }>();
/** How long a spell may go on awaiting once its caster has priority again; past this its item is not coming. */
const SETTLE_MS = 900;

/** The model last drawn, and what the phone's chip remembers between draws: how many items it held, and the question it was folded during. */
let current: Model | null = null;
let countSeen = 0;
let foldedFor: object | null = null;
let foldedForPick: object | null = null;

function fold(folded: boolean): void {
  foldedFor = folded ? current?.prompt ?? null : null;
  changeUi(u => { u.stackCollapsed = folded; });
}

/** A tap on the board puts the phone's list away, so the cards under it can be reached. */
export function foldStack(): void {
  if (isPortrait() && !ui.stackCollapsed) fold(true);
}

export function initStack(actionsFor: Actions): void {
  actions = actionsFor;
  document.addEventListener('click', e => {
    if (ui.stackMenuAt && !(e.target instanceof Element && e.target.closest('#stack-menu'))) {
      changeUi(u => { u.stackMenuAt = null; });
    }
  });
}

export function renderStack(model: Model, events: readonly GameEvent[]): void {
  const root = byId('stack');
  if (!root.firstChild) {
    root.innerHTML = '<div class="head"><b></b><span class="count"></span><button class="collapse"></button></div><div class="pile"></div>';
    q(root, '.head b').textContent = t('lblStack');
    q(root, '.collapse').onclick = () => fold(!ui.stackCollapsed);
    // On a phone the whole head is the chip that opens and folds the list
    q(root, '.head').onclick = e => {
      if (isPortrait() && !(e.target as Element).closest('.collapse')) fold(!ui.stackCollapsed);
    };
    window.addEventListener('resize', () => place(root));
  }
  const items: StackItemView[] = derefAll(model, game(model)?.Stack);
  // Spells arrive by flying in, and anything else, an ability or a trigger, has no card move and grows into place instead
  const flown = new Set([...awaiting.keys(), ...[...journeys(events)].filter(([, m]) => m.to?.zone === 'Stack').map(([k]) => k)]);
  noteAwaiting(model, items, events);
  if (ui.hoveredStackItem !== null && !items.some(i => i.$key === ui.hoveredStackItem)) {
    ui.hoveredStackItem = null;
  }
  current = model;
  const portrait = isPortrait();
  root.classList.toggle('chip', portrait);
  const count = items.length + awaiting.size;
  if (portrait) {
    // The list opens for a new arrival while you may answer it, unless you folded it during this same question
    if (count > countSeen && model.prompt?.priority && foldedFor !== model.prompt) ui.stackCollapsed = false;
    // A question answered on the board or from the hand needs the room the list would lie over
    const asked = model.prompt;
    if (asked && asked !== foldedForPick && !asked.priority && !asked.paying && (asked.selectable.length > 0 || asked.selectablePlayers.length > 0)) {
      foldedForPick = asked;
      ui.stackCollapsed = true;
    }
    const top = items[0];
    const source = top ? deref(model, top.SourceCard) as CardView | undefined : undefined;
    // A spell still being paid for has no item yet, and is named from its card
    const paying = top ? undefined : model.objects.get(Number([...awaiting.keys()][0])) as CardView | undefined;
    q(root, '.head b').textContent = top
      ? `${t('lblStack')} \u00b7 ${t('lblWebPortraitStackTop', source ? stateOf(model, source).Name ?? '' : '', deref(model, top.ActivatingPlayer)?.Name ?? '')}`
      : paying ? `${t('lblStack')} \u00b7 ${stateOf(model, paying).Name ?? ''}` : t('lblStack');
    // The dock is drawn after this and can change height, which moves the strip the chip sits on
    requestAnimationFrame(() => place(root));
    // Over whichever half of the board holds fewer of the top item's targets, so they stay in view
    const targets = top ? stackTargets(model, top) : [];
    const mine = targets.filter(o => o.$key === me(model)?.$key || document.querySelector(`#me .card[data-key="${o.$key}"]`)).length;
    root.classList.toggle('over-them', mine > targets.length - mine);
  } else {
    q(root, '.head b').textContent = t('lblStack');
  }
  countSeen = count;
  const collapsed = ui.stackCollapsed;
  showPanel(root, items.length + awaiting.size > 0);
  root.classList.toggle('collapsed', collapsed);
  q(root, '.count').textContent = String(items.length + awaiting.size);
  const collapse = q(root, '.collapse');
  collapse.textContent = t(collapsed ? 'lblShow' : 'lblWebStackHide');
  collapse.title = t(collapsed ? 'lblWebStackShowTitle' : 'lblWebStackHideTitle');
  const pile = q(root, '.pile');
  const pick = stackPick(model);
  // An awaiting spell stands where its item will appear, at the top, so paying for it moves nothing
  const entries: (StackItemView | string)[] = [...awaiting.keys(), ...items];
  reconcile(pile, entries, e => typeof e === 'string' ? `awaiting-${e}` : e.$key,
    e => {
      if (typeof e === 'string') return createAwaiting(e);
      const el = createItem(model);
      el.dataset.fresh = '1';
      return el;
    }, (el, e) => {
      if (typeof e === 'string') return;
      updateItem(el, model, e);
      if (el.dataset.fresh) {
        delete el.dataset.fresh;
        if (!flown.has(String(e.SourceCard?.ref)) && document.documentElement.dataset.motion !== 'reduced') {
          el.animate([{ opacity: 0, scale: '.85' }, { opacity: 1, scale: '1' }], { duration: 200, easing: 'cubic-bezier(.2, .8, .3, 1)' });
        }
      }
      el.classList.toggle('targetable', (pick?.stackKeys ?? []).includes(e.$key));
    });
  place(root);
  layout(pile, entries.length);
  renderMenu(model);
  renderStorm(root, game(model)?.StormCount ?? 0, entries.length > 0);
}

/** The panel fading out as its last item leaves, so a render meanwhile does not bring it back or hide it at once. */
let closing: Animation | null = null;

/** Fades the panel in or out by opacity only, because a scale or slide would move the slot a flying spell is aimed at. */
function showPanel(root: HTMLElement, shown: boolean): void {
  const reduced = document.documentElement.dataset.motion === 'reduced';
  if (shown) {
    if (closing) {
      closing.cancel();
      closing = null;
    } else if (root.hidden && !reduced) {
      root.hidden = false;
      root.animate([{ opacity: 0 }, { opacity: 1 }], { duration: PANEL_FADE_MS, easing: 'ease-out' });
    }
    root.hidden = false;
    return;
  }
  if (root.hidden || closing) return;
  if (reduced) {
    root.hidden = true;
    return;
  }
  closing = root.animate([{ opacity: 1 }, { opacity: 0 }], { duration: PANEL_FADE_MS, easing: 'ease-in', fill: 'forwards' });
  closing.finished.then(() => {
    root.hidden = true;
    closing?.cancel();
    closing = null;
  }, () => { /* cancelled: an item came back */ });
}

const PANEL_FADE_MS = 150;

/** The count of spells cast this turn, shown under the stack while something is on it and otherwise beside the foot of the prompt. */
function renderStorm(stack: HTMLElement, count: number, stacked: boolean): void {
  let chip = document.getElementById('storm');
  if (!chip) {
    chip = Object.assign(document.createElement('div'), { id: 'storm', title: t('lblWebStackStormTitle') });
    stack.after(chip);
  }
  chip.hidden = count <= 0;
  if (chip.hidden) return;
  chip.textContent = t('lblWebStackStorm', count);
  // Measured to where the pile's height is going, not where its transition has got to
  const pile = q(stack, '.pile');
  const going = parseFloat(pile.style.height);
  const easing = pile.offsetParent && Number.isFinite(going) ? going - pile.offsetHeight : 0;
  // Read from what is on the stack, not whether the panel shows, since the panel is still fading as it empties
  const prompt = document.getElementById('prompt');
  if (!stacked && prompt) {
    const at = prompt.getBoundingClientRect();
    chip.style.top = `${at.bottom - chip.offsetHeight}px`;
    chip.style.right = `${innerWidth - at.left + 8}px`;
    return;
  }
  chip.style.right = '';
  chip.style.top = stack.hidden ? '' : `${stack.getBoundingClientRect().bottom + easing + 8}px`;
}

// The panel hangs from the top of the board and stops short of the hand
function place(root: HTMLElement): void {
  if (isPortrait()) {
    // It takes the phase strip's place while anything is on it, and its list opens down over your cards or up over theirs
    const strip = byId('phase-strip').getBoundingClientRect();
    const above = root.classList.contains('over-them') && !ui.stackCollapsed;
    root.style.top = above ? 'auto' : `${Math.round(strip.top)}px`;
    root.style.bottom = above ? `${Math.round(innerHeight - strip.bottom)}px` : 'auto';
    return;
  }
  root.style.top = root.style.bottom = '';
  const hand = byId('hand').getBoundingClientRect();
  q(root, '.pile').style.setProperty('--stack-room', `${Math.max(120, hand.top - root.getBoundingClientRect().top - 48)}px`);
}

function noteAwaiting(model: Model, items: StackItemView[], events: readonly GameEvent[]): void {
  const onStack = new Set(items.map(i => String(i.SourceCard?.ref)));
  for (const [key, move] of journeys(events)) {
    // As on desktop and mobile, another player's spell shows on the stack only once it is paid for
    const mine = !!move.caster && model.localPlayers.includes(move.caster.ref);
    // Read before the zone it left is redrawn without it
    const img = document.querySelector<HTMLImageElement>(`.card[data-key="${key}"] img, .zone-tile img[data-key="${key}"]`);
    // The card may already be gone or hidden when its move is seen, so the picture it last had stands in
    const picture = img?.getAttribute('src') ? { src: img.getAttribute('src') as string, zoom: img.dataset.zoom ?? '' }
      : lastPicture(Number(key));
    if (move.to?.zone === 'Stack' && mine && !onStack.has(key) && picture) {
      awaiting.set(key, { ...picture, since: Date.now() });
    } else {
      awaiting.delete(key);
    }
  }
  for (const [key, spell] of awaiting) {
    // A cancelled cast is undone without an event, and the card is back in a zone the browser can see
    const back = model.objects.get(Number(key)) as CardView | undefined;
    const given = model.prompt?.priority && Date.now() - spell.since > SETTLE_MS;
    if (onStack.has(key) || (back?.Zone && back.Zone !== 'Stack') || given) {
      awaiting.delete(key);
    }
  }
}

function createAwaiting(key: string): HTMLElement {
  const spell = awaiting.get(key);
  const el = document.createElement('div');
  el.className = 'stack-item awaiting';
  el.innerHTML = '<img alt="" draggable="false"><div class="await"></div>';
  q(el, '.await').textContent = t('lblWebStackAwaitingPayment');
  const img = q<HTMLImageElement>(el, 'img');
  img.src = spell?.src ?? '';
  img.dataset.key = key;
  img.dataset.zoom = spell?.zoom ?? '';
  hoverable(el, img);
  el.addEventListener('pointerdown', e => longPress(e, () => inspectCard(img)));
  return el;
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
  el.addEventListener('pointerenter', e => {
    if (!hovers(e)) return;
    const pile = el.parentElement as HTMLElement;
    // Lifted at once, rather than on the next frame, so the pile answers the pointer as it moves along it
    ui.hoveredStackItem = Number(el.dataset.key);
    layout(pile, pile.childElementCount);
    hoverCard(img);
  });
  el.addEventListener('pointerdown', e => longPress(e, () => inspectCard(img)));
  // Desktop's stack menu: auto-yield, always accept or decline your optional trigger, yield to the stack
  el.addEventListener('contextmenu', e => {
    e.preventDefault();
    const key = Number(el.dataset.key);
    changeUi(u => { u.stackMenuAt = { key, x: e.clientX, y: e.clientY }; });
    actions?.askStackMenu(key);
  });
  el.addEventListener('pointerleave', e => {
    if (!hovers(e)) return;
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
  // A phone lists the stack, each item saying its number; the cascade of cards is a desktop's
  if (isPortrait()) {
    pile.style.height = '';
    items.forEach((el, i) => {
      el.style.top = '';
      el.dataset.n = String(i + 1);
    });
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

// The server answers a right-click with what applies to that item, and the menu opens once that answer is in
function renderMenu(model: Model): void {
  const at = ui.stackMenuAt;
  const answer = model.stackMenu;
  const wanted = at && answer && answer.key === at.key ? JSON.stringify([at, answer]) : '';
  if (wanted === menuDrawn) {
    return;
  }
  menuDrawn = wanted;
  leave(document.querySelector('#stack-menu:not(.closing)'));
  if (!at || !answer || !wanted) {
    return;
  }
  const menu = document.createElement('div');
  menu.id = 'stack-menu';
  menu.className = 'card-menu game-menu';
  menu.setAttribute('role', 'menu');
  menu.style.left = `${at.x}px`;
  menu.style.top = `${at.y}px`;
  const items: StackItemView[] = derefAll(model, game(model)?.Stack);
  const source = deref(model, items.find(i => i.$key === at.key)?.SourceCard);
  const name = source && stateOf(model, source).Name;
  if (name) {
    const title = document.createElement('p');
    title.className = 'card-menu-title';
    title.textContent = name;
    menu.append(title);
  }
  // A choice the game remembers carries a tick, shown or not, so every label starts at the same place as a pass's
  const item = (label: string, action: YieldAction, checked?: boolean) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'card-menu-item';
    b.setAttribute('role', checked === undefined ? 'menuitem' : 'menuitemcheckbox');
    if (checked !== undefined) b.setAttribute('aria-checked', String(checked));
    b.innerHTML = `<svg class="menu-icon${checked === undefined ? '' : ' tick'}" viewBox="0 0 24 24" aria-hidden="true">${checked === undefined ? ICONS.yields : ICONS.check}</svg>`;
    b.append(label);
    b.onclick = () => {
      actions?.stackYield(answer.key, action);
      changeUi(u => { u.stackMenuAt = null; });
    };
    menu.append(b);
  };
  if (answer.autoYield !== undefined) item(t('lblWebStackAutoYield'), 'autoYield', answer.autoYield);
  if (answer.trigger !== undefined) {
    item(t('lblWebStackAlwaysAccept'), 'alwaysYes', answer.trigger === 'ACCEPT');
    item(t('lblWebStackAlwaysDecline'), 'alwaysNo', answer.trigger === 'DECLINE');
  }
  if (answer.autoYield !== undefined || answer.trigger !== undefined) {
    const sep = document.createElement('div');
    sep.className = 'card-menu-sep';
    sep.setAttribute('role', 'separator');
    menu.append(sep);
  }
  item(t('lblWebStackYieldUntilResolves'), 'yieldToStack');
  item(t('lblWebStackYieldUntilEmpty'), 'yieldToEntireStack');
  document.body.append(menu);
}
