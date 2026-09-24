// Cards move rather than jump. The game says what moved (a cardMoved event from one zone to another); this file
// decides how that looks, from where each card stood when the board was last drawn to where it stands now. A spell
// being cast goes onto the stack before its cost is paid, so it waits beside the stack until its item appears there,
// or flies home if the cast is cancelled, which is the one move the game makes without an event.

import { hoverable } from './detail';
import type { CardMoved, GameEvent, Place } from './protocol';
import type { Model } from './model';

/** Where a card stood, and a copy of how it looked there, for a trip after its own element has gone. */
interface Snapshot {
  rect: DOMRect;
  ghost: HTMLElement | null;
}

/** Every card drawn last frame (hand, board, stack, open zones), by its key. */
const lastSeen = new Map<string, Snapshot>();
/** Spells that have gone onto the stack and are waiting for their cost, by card key. */
const waiting = new Map<string, Snapshot & { since: number }>();
const fromHint = new Map<string, DOMRect>();
const intoHint = new Map<string, DOMRect>();

/**
 * Where a card will stand once the flight it is part way through is over. A flight is a transform, and a
 * transform moves what the element measures, so a frame drawn while one runs would read the card as displaced
 * and slide it again — which is what any redraw during a flight, a hover among them, used to do.
 */
const resting = new Map<string, DOMRect>();

function restingRect(el: HTMLElement): DOMRect {
  const key = el.dataset.key as string;
  const held = resting.get(key);
  if (held && el.getAnimations().length) {
    return held;
  }
  resting.delete(key);
  return el.getBoundingClientRect();
}

const FLIGHT_MS = 340;
const DEAL_MS = 400;
const STAGGER_MS = 55;
/** How long a spell may wait once no cost is being paid for it; past this its stack item is not coming. */
const SETTLE_MS = 900;
const POP_MS = 220;

/** The cards of a pile that has just been laid out all start where the pile stood. */
export function spreadFrom(keys: string[], rect: DOMRect): void {
  for (const key of keys) {
    fromHint.set(key, rect);
  }
}

/** The cards of a pile being put back together end where its top card stands. */
export function mergeInto(keys: string[], rect: DOMRect): void {
  for (const key of keys) {
    intoHint.set(key, rect);
  }
}

/** Animates what happened since the last frame. Runs after the board is drawn, so both ends can be measured. */
export function animateCardMoves(model: Model, events: readonly GameEvent[]): void {
  notePiles();
  let dealt = 0;
  for (const [key, move] of journeys(events)) {
    const start = waiting.get(key)?.rect ?? lastSeen.get(key)?.rect ?? placeRect(move.from);
    const el = elementFor(key);
    // A spell on the stack with no item yet is having its cost paid
    if (move.to?.zone === 'Stack' && !el) {
      if (start) {
        hold(key, start, lastSeen.get(key)?.ghost ?? null);
      }
      continue;
    }
    if (el) {
      const drawn = move.from?.zone === 'Library' && move.to?.zone === 'Hand';
      land(key);
      if (start) {
        fly(el, start, drawn ? DEAL_MS : FLIGHT_MS, drawn ? dealt++ * STAGGER_MS : 0);
      } else {
        // A token, or anything else that comes into being, grows into place rather than blinking on
        pop(el);
      }
      continue;
    }
    // Somewhere not drawn card by card (a library, a graveyard's pile, an opponent's hand): a copy makes the trip
    const ghost = waiting.get(key)?.ghost ?? lastSeen.get(key)?.ghost ?? null;
    land(key);
    if (ghost && start) {
      const target = tileImageRect(key) ?? placeRect(move.to);
      sendTo({ rect: start, ghost }, target ?? start, target ? 0.25 : 0);
    }
  }
  settleWaiting(!!model.prompt?.paying);
  shiftBoard(new Set(journeys(events).keys()));
  layOutPiles();
  note();
}

/**
 * A card that stays on the battlefield but stands somewhere else now slides there: out of a pile it has left, into
 * a pile it has joined, or along the row as its neighbours come and go. The game says nothing of these, so they are
 * read off where each card stood last frame. The slide is added to whatever the card is doing (turning as it taps),
 * never in place of it.
 */
function shiftBoard(travelled: Set<string>): void {
  for (const el of document.querySelectorAll<HTMLElement>(BOARD_CARDS)) {
    const key = el.dataset.key as string;
    const was = lastSeen.get(key);
    if (!was || travelled.has(key) || fromHint.has(key)) {
      continue;
    }
    const now = restingRect(el);
    const dx = centre(was.rect).x - centre(now).x;
    const dy = centre(was.rect).y - centre(now).y;
    if (Math.abs(dx) + Math.abs(dy) > 2) {
      // Measured mid-slide the card is still near where it came from, which the next frame would slide it from again
      resting.set(key, now);
      el.animate([{ transform: `translate(${dx}px, ${dy}px)` }, { transform: 'translate(0, 0)' }],
        { duration: FLIGHT_MS, easing: 'cubic-bezier(.2,.7,.3,1)', composite: 'add' });
    }
  }
  // A card that had a place of its own and is now folded into a pile slides onto the pile's top card
  for (const { keys, top } of pileSlots()) {
    for (const key of keys) {
      const was = lastSeen.get(key);
      if (was && ownPlace.has(key) && key !== top.dataset.key && !travelled.has(key) && !intoHint.has(key)) {
        sendTo(was, top.getBoundingClientRect(), 1);
      }
    }
  }
}

const centre = (r: DOMRect) => ({ x: r.left + r.width / 2, y: r.top + r.height / 2 });

/** Each card's first origin and last destination this frame; a card that went out and back in one step moved once. */
export function journeys(events: readonly GameEvent[]): Map<string, CardMoved> {
  const out = new Map<string, CardMoved>();
  for (const e of events) {
    if (e.kind !== 'cardMoved') {
      continue;
    }
    const key = String(e.card.ref);
    const earlier = out.get(key);
    out.set(key, earlier ? { ...e, from: earlier.from } : e);
  }
  return out;
}

// A paid spell's item has appeared on the stack, so it flies there from where it waited. A cancelled cast is put
// back without the game saying so (Forge undoes it rather than moving it), so a waiting card that is drawn in a zone
// again flies home from beside the stack
function settleWaiting(paying: boolean): void {
  for (const [key, held] of waiting) {
    const arrived = stackItemFor(key) ?? cardElement(key);
    if (arrived) {
      land(key);
      fly(arrived, held.rect, FLIGHT_MS, 0);
    } else if (!paying && Date.now() - held.since > SETTLE_MS) {
      land(key);
    }
  }
}

// Opening or closing a pile is the player's doing, not the game's, so it is animated from the hints it left
function layOutPiles(): void {
  for (const [key, from] of fromHint) {
    const el = cardElement(key);
    if (el) {
      fly(el, from, FLIGHT_MS, 0);
    }
  }
  for (const [key, into] of intoHint) {
    const was = lastSeen.get(key);
    if (was?.ghost && !cardElement(key)) {
      sendTo(was, into, 1);
    }
  }
  fromHint.clear();
  intoHint.clear();
}

// ---- Finding things on the board ---------------------------------------------------------------------------------

const CARDS = '#me .card[data-key], #opponent .card[data-key], #hand .card[data-key], #zones .card[data-key]';
const BOARD_CARDS = '#me .battlefield .card[data-key], #opponent .battlefield .card[data-key]';
const stackItems = () => [...document.querySelectorAll<HTMLElement>('#stack .stack-item')];

function cardElement(key: string): HTMLElement | null {
  return document.querySelector<HTMLElement>(`#me .card[data-key="${key}"], #opponent .card[data-key="${key}"], `
    + `#hand .card[data-key="${key}"], #zones .card[data-key="${key}"]`);
}

// A stack item is keyed by the item, not the card, so the card's own key comes off its picture
function stackItemFor(key: string): HTMLElement | null {
  return stackItems().find(el => el.querySelector('img')?.dataset.key === key) ?? null;
}

/** Where a card is drawn now: its own element, its stack item, or the top of the pile it joined. */
function elementFor(key: string): HTMLElement | null {
  return cardElement(key) ?? stackItemFor(key) ?? pileTopFor(key);
}

function pileTopFor(key: string): HTMLElement | null {
  for (const { keys, top } of pileSlots()) {
    if (keys.includes(key)) {
      return top;
    }
  }
  return null;
}

// The top card of a graveyard or exile pile is drawn on its tile, which is where a card sent there lands
function tileImageRect(key: string): DOMRect | null {
  const img = document.querySelector(`.zone-tile img[data-key="${key}"]`);
  return img ? img.getBoundingClientRect() : null;
}

/** Roughly where a zone is drawn, for a card with no element of its own at one end of its trip. */
function placeRect(place: Place | undefined): DOMRect | null {
  if (!place) {
    return null;
  }
  const seat = place.player ? document.querySelector<HTMLElement>(`.seat[data-player="${place.player.ref}"]`) : null;
  const rectOf = (el: Element | null | undefined) => (el ? el.getBoundingClientRect() : null);
  switch (place.zone) {
    case 'Stack': {
      const stack = document.getElementById('stack');
      return stack && !stack.hidden ? stack.getBoundingClientRect() : null;
    }
    case 'Hand':
      // Your own hand is laid out along the bottom; everyone else's is the fan of backs under their name
      return seat?.id === 'me' ? rectOf(document.getElementById('hand')) : rectOf(seat?.querySelector('.hand-fan'));
    case 'Battlefield':
      return rectOf(seat?.querySelector('.battlefield'));
    case 'Command':
      return rectOf(seat?.querySelector('.emblems'));
    default:
      return rectOf(seat?.querySelector(`.zone-tile[data-zone="${place.zone}"]`));
  }
}

/** The keys a pile is holding behind its top card. They have no element, and they have not gone anywhere. */
let covered = new Set<string>();
/** The cards drawn last frame with an element of their own, rather than folded into a pile. */
const ownPlace = new Set<string>();

function notePiles(): void {
  covered = new Set();
  for (const { keys, top } of pileSlots()) {
    for (const key of keys) {
      if (key !== top.dataset.key) {
        covered.add(key);
      }
    }
  }
}

function pileSlots(): { keys: string[]; top: HTMLElement }[] {
  const out: { keys: string[]; top: HTMLElement }[] = [];
  for (const slot of document.querySelectorAll<HTMLElement>('.slot[data-members]')) {
    const top = slot.querySelector<HTMLElement>(':scope > .card:last-child');
    if (top) {
      out.push({ keys: (slot.dataset.members ?? '').split(','), top });
    }
  }
  return out;
}

/** Remembers where every card stands now, for the moves the next frame brings. */
function note(): void {
  lastSeen.clear();
  ownPlace.clear();
  for (const el of document.querySelectorAll<HTMLElement>(CARDS)) {
    lastSeen.set(el.dataset.key as string, { rect: restingRect(el), ghost: el.cloneNode(true) as HTMLElement });
    ownPlace.add(el.dataset.key as string);
  }
  for (const el of stackItems()) {
    const key = el.querySelector('img')?.dataset.key;
    if (key && !lastSeen.has(key)) {
      lastSeen.set(key, { rect: el.getBoundingClientRect(), ghost: null });
    }
  }
  // A copy folded into a pile stands where the pile's top card stands, so it leaves from there if it leaves
  for (const { keys, top } of pileSlots()) {
    const shown = lastSeen.get(top.dataset.key as string);
    for (const key of keys) {
      if (shown && covered.has(key)) {
        lastSeen.set(key, { rect: shown.rect, ghost: shown.ghost?.cloneNode(true) as HTMLElement | null });
      }
    }
  }
}

// ---- Moving things -----------------------------------------------------------------------------------------------

// A spell waits beside the stack, where it is about to go, rather than over the cards on the table
function waitingSpot(rect: DOMRect): DOMRect {
  const stack = document.getElementById('stack');
  const panel = stack && !stack.hidden ? stack.getBoundingClientRect() : null;
  const board = document.getElementById('me')?.getBoundingClientRect();
  const width = rect.width * 0.8;
  const height = rect.height * 0.8;
  const right = panel ? panel.left : (board?.right ?? innerWidth) - 8;
  const top = panel ? panel.top : (board?.top ?? 80);
  return new DOMRect(right - width - 12, top, width, height);
}

function hold(key: string, from: DOMRect, ghost: HTMLElement | null): void {
  const spot = waitingSpot(from);
  if (!ghost) {
    // Nothing to show waiting (an opponent's card), but its item still flies in from where it came
    waiting.set(key, { rect: from, ghost: null, since: Date.now() });
    return;
  }
  const shown = place(ghost, from);
  shown.classList.add('paying');
  // A card waiting to be paid for is still a card you want to read, so it answers the pointer
  shown.style.pointerEvents = 'auto';
  hoverable(shown, shown.querySelector('img') ?? shown);
  shown.animate([
    { transform: 'none' },
    {
      transform: `translate(${spot.left - from.left}px, ${spot.top - from.top}px) scale(${spot.width / from.width})`,
    },
  ], { duration: FLIGHT_MS, easing: 'cubic-bezier(.2,.7,.3,1)', fill: 'forwards' });
  waiting.set(key, { rect: spot, ghost: shown, since: Date.now() });
}

function land(key: string): void {
  waiting.get(key)?.ghost?.remove();
  waiting.delete(key);
}

function place(ghost: HTMLElement, rect: DOMRect): HTMLElement {
  ghost.style.cssText = `position: fixed; left: ${rect.left}px; top: ${rect.top}px; width: ${rect.width}px;`
    + `height: ${rect.height}px; margin: 0; z-index: 40; pointer-events: none;`;
  document.body.append(ghost);
  return ghost;
}

function pop(el: HTMLElement): void {
  el.animate([
    { transform: 'scale(.7)', opacity: 0 },
    { transform: 'none', opacity: 1 },
  ], { duration: POP_MS, easing: 'cubic-bezier(.2,.9,.3,1.2)' });
}

function fly(el: HTMLElement, from: DOMRect, duration: number, delay: number): void {
  const to = restingRect(el);
  if (!to.width || !from.width) {
    return;
  }
  if (el.dataset.key) {
    resting.set(el.dataset.key, to);
  }
  el.animate([
    {
      transform: `translate(${from.left - to.left}px, ${from.top - to.top}px) scale(${from.width / to.width})`,
      opacity: delay ? 0.4 : 1,
    },
    { transform: 'none', opacity: 1 },
  ], { duration, delay, easing: 'cubic-bezier(.2,.7,.3,1)', fill: 'backwards' });
}

// The card is already gone from the model, so a copy of it makes the trip
function sendTo(was: Snapshot, target: DOMRect, endOpacity: number): void {
  if (!was.ghost) {
    return;
  }
  const ghost = place(was.ghost, was.rect);
  ghost.animate([
    { transform: 'none', opacity: 1 },
    {
      transform: `translate(${target.left - was.rect.left}px, ${target.top - was.rect.top}px) scale(${target.width / was.rect.width})`,
      opacity: endOpacity,
    },
  ], { duration: FLIGHT_MS, easing: 'cubic-bezier(.4,0,.8,.4)' }).finished.then(() => ghost.remove(), () => ghost.remove());
}
