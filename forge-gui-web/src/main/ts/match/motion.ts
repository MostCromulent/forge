// Decides how each cardMoved event looks, from where a card stood before the board was drawn to where it stands now

import { hoverable } from './detail';
import type { CardMoved, GameEvent, Place } from '../protocol';
import type { CardView, PlayerView } from '../protocol';
import { cssUrl, playerSleeveUrl } from '../looks';
import { echoSound } from '../audio';
import { TAPPED_SCALE, unmake } from './leaving';
import type { Model } from '../model';
import { make } from '../dom';

/** Where a card stood, and a copy of how it looked there, for a trip after its own element has gone. */
interface Snapshot {
  rect: DOMRect;
  ghost: HTMLElement | null;
  /** Its centre as laid out, before any transform: where a row put it, not where a tilt or a step forward shows it. */
  laid?: { x: number; y: number };
  /** Its size as laid out, before any transform, so a copy of a tapped card is turned as the card was. */
  size?: { w: number; h: number };
  /** How far a slide or flight still under way has it from where it will rest. */
  drift?: { x: number; y: number };
  /** Whether it was tapped, kept because the ghost is the live element and goes on updating after this was taken. */
  tapped?: boolean;
}

/** A spell waiting for its cost, and the trip it is making to where it waits. */
interface Waiting {
  rect: DOMRect;
  ghost: HTMLElement | null;
  since: number;
  /** Where the trip to the waiting place set off from, for a spell paid for before it has arrived. */
  from?: DOMRect;
}

/** Every card drawn just before this frame (hand, board, stack, open zones, the tops of zone tiles), by its key. */
const lastSeen = new Map<string, Snapshot>();
/** Spells that have gone onto the stack and are waiting for their cost, by card key. */
const waiting = new Map<string, Waiting>();
const fromHint = new Map<string, DOMRect>();
const intoHint = new Map<string, DOMRect>();

/** Where a card will rest once its flight ends, since a flight's transform changes what the element measures. */
const resting = new Map<string, DOMRect>();

/** Whether a card is part way through a move. An endless effect, such as a flyer's hover, is not one. */
function moving(el: HTMLElement): boolean {
  return el.getAnimations().some(a => a.effect?.getTiming().iterations !== Infinity);
}

function restingRect(el: HTMLElement): DOMRect {
  const key = el.dataset.key as string;
  const held = resting.get(key);
  if (held && moving(el)) {
    return held;
  }
  resting.delete(key);
  return el.getBoundingClientRect();
}

/** Every card's trip from one place to another takes this long: a play, a draw, a discard, a paid spell's landing. */
const FLIGHT_MS = 500;
/** A card put onto the battlefield from the hand (a land, a permanent that resolves at once) travels this fast. */
const PLAY_MS = 260;
/** How long a move within the battlefield takes, such as a land tapped from one pile onto another. */
const SHIFT_MS = 340;
/** Cards moving together (a deal, a mulligan, a discard) set off this far apart, so each can be followed. */
const STAGGER_MS = 110;
/** A hand swapped for another (a mulligan) is two runs of cards, so each card's trip and the gap between them are shorter. */
const SWAP_MS = 350;
const SWAP_STAGGER_MS = 70;
/** How long a spell may wait once no cost is being paid for it; past this its stack item is not coming. */
const SETTLE_MS = 900;
const POP_MS = 220;
const EASE = 'cubic-bezier(.2,.7,.3,1)';
/** Names a card's own trip among its animations, so a second trip replaces the first rather than stacking on it. */
const FLIGHT = 'flight';

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

/** A new table: what the last one showed says nothing about where this one's cards, which reuse its keys, come from. */
let fresh = false;

export function resetMotion(): void {
  for (const held of waiting.values()) {
    held.ghost?.remove();
  }
  waiting.clear();
  resting.clear();
  fromHint.clear();
  intoHint.clear();
  fresh = true;
}

/** Remembers where every card stands just before the board is redrawn, because the board can move between frames on its own. */
export function noteBoard(): void {
  if (fresh) {
    fresh = false;
    lastSeen.clear();
    ownPlace.clear();
    covered = new Set();
    lastShape = '';
    lastHand = '';
    return;
  }
  notePiles();
  note();
}

/** Animates what happened since the last frame. Runs after the board is drawn, so both ends can be measured. */
export function animateCardMoves(model: Model, events: readonly GameEvent[]): void {
  const dealt: { el: HTMLElement; start: DOMRect }[] = [];
  const leavingHand: { was: Snapshot; target: DOMRect | null; tile?: boolean; key?: string; onto?: HTMLElement | null }[] = [];
  const trips = journeys(events);
  // Hand icons and zone tiles already swelling this frame, so several cards arriving at once swell each once
  const swelled = new Set<HTMLElement>();
  let fromLibrary = 0;
  /** How long until the last permanent being unmade where it stood has gone, so the row does not close over it. */
  let unmaking = 0;
  for (const [key, move] of trips) {
    const seen = lastSeen.get(key);
    const start = waiting.get(key)?.rect ?? seen?.rect ?? placeRect(move.from);
    // A spell on the stack with no item yet is having its cost paid, in the slot the stack keeps for it
    const slot = move.to?.zone === 'Stack' ? awaitingSlot(key) : null;
    if (slot) {
      const to = restingRect(slot);
      waiting.set(key, { rect: to, ghost: null, since: Date.now(), from: start ?? undefined });
      if (start) {
        fly(slot, start, FLIGHT_MS, 0);
      }
      continue;
    }
    const el = elementFor(key);
    if (move.to?.zone === 'Stack' && !el) {
      if (start) {
        hold(key, start, seen ?? null);
      }
      continue;
    }
    // A card that went somewhere and came straight back (a mulligan's shuffle and redraw) makes both trips
    if (el && move.via && seen?.ghost) {
      const away = tileImageRect(key) ?? placeRect(move.via);
      if (move.from?.zone === 'Hand') leavingHand.push({ was: seen, target: away });
      else if (away) sendTo(seen, away, 0.25);
      const back = away ?? start;
      if (back) {
        if (move.via.zone === 'Library' && move.to?.zone === 'Hand') dealt.push({ el, start: back });
        else fly(el, back, FLIGHT_MS, 0);
      }
      continue;
    }
    if (el) {
      const drawn = move.from?.zone === 'Library' && move.to?.zone === 'Hand';
      // A card put straight down from the hand, such as a land, is a short trip and makes it quicker
      const ms = move.from?.zone === 'Hand' && move.to?.zone === 'Battlefield' ? PLAY_MS : FLIGHT_MS;
      land(key);
      // Flying a pile's top element would carry the whole pile in, so a copy of the joining card makes the trip instead
      const pile = el.closest<HTMLElement>('.slot[data-members]');
      if (start && pile && (pile.dataset.members ?? '').split(',').length > 1) {
        holdPile(el, ms);
        sendTo({ ...(seen ?? { ghost: el, size: { w: el.offsetWidth, h: el.offsetHeight } }), rect: start }, restingRect(el), 1, 0, el, ms);
        holdCount(el, ms);
        continue;
      }
      if (start) {
        if (drawn) dealt.push({ el, start });
        else fly(el, start, ms, 0);
      } else if (pile && (pile.dataset.members ?? '').split(',').length > 1) {
        // A token joining a pile of its kind: a copy grows onto the pile, which stays as it is
        holdPile(el, POP_MS);
        const copy = place(el, restingRect(el), { w: el.offsetWidth, h: el.offsetHeight });
        pop(copy);
        copy.getAnimations()[0]?.finished.then(() => copy.remove(), () => copy.remove());
        holdCount(el, POP_MS);
      } else {
        // A token, or anything else that comes into being, grows into place rather than blinking on
        pop(el);
      }
      continue;
    }
    // Somewhere not drawn card by card (a library, a graveyard's pile, an opponent's hand): a copy makes the trip
    const ghost = waiting.get(key)?.ghost ?? seen?.ghost ?? null;
    const was = waiting.get(key)?.ghost ? { rect: waiting.get(key)!.rect, ghost, size: seen?.size } : seen;
    // Another player's hand is only an icon, so a card drawn into it makes no trip and the icon swells once instead
    const intoFan = move.from?.zone === 'Library' && move.to?.zone === 'Hand' ? handFanOf(move.to) : null;
    if (!ghost && intoFan) {
      if (!swelled.has(intoFan)) {
        swelled.add(intoFan);
        intoFan.animate([{ scale: '1' }, { scale: '1.28', offset: 0.35 }, { scale: '1' }], { duration: 480, easing: EASE });
      }
      land(key);
      continue;
    }
    // The tile the card came to rest on swells as it lands, or at once when nothing is seen to travel there
    const onto = landedTile(model, key, move.to);
    let arrives = 0;
    if (!ghost && start && move.from?.zone === 'Library') {
      // The library shows no card to copy, so a back in the owner's sleeve makes the trip, staggered when several go together
      const target = tileImageRect(key) ?? placeRect(move.to);
      if (target) {
        arrives = FLIGHT_MS + fromLibrary * STAGGER_MS;
        if (tileImageRect(key)) holdTile(key, fromLibrary * STAGGER_MS);
        sendTo({ rect: start, ghost: cardBack(model, key) }, target, 1, fromLibrary++ * STAGGER_MS);
      }
    }
    if (ghost && start && was) {
      const tile = tileImageRect(key);
      const target = tile ?? placeRect(move.to);
      if (move.from?.zone === 'Hand') {
        leavingHand.push({ was: { ...was, rect: start }, target, tile: !!tile, key, onto });
        land(key);
        continue;
      } else {
        // A permanent destroyed or exiled is unmade where it stood; the tile's picture of it waits until it has gone
        const leaving = move.from?.zone === 'Battlefield' && (move.to?.zone === 'Graveyard' || move.to?.zone === 'Exile')
          ? unmake(ghost, start, was.size, !!was.tapped, move.to.zone) : 0;
        // A card landing on a zone tile ends on the tile's picture, which is held back until it arrives
        if (tile) holdTile(key, leaving ? leaving - FLIGHT_MS : 0);
        unmaking = Math.max(unmaking, leaving);
        if (!leaving) sendTo({ ...was, rect: start }, target ?? start, tile ? 1 : target ? 0.25 : 0);
        arrives = leaving || FLIGHT_MS;
      }
    }
    swell(onto, arrives, swelled);
    land(key);
  }
  // A hand that leaves as another is dealt (a mulligan) has gone before the first new card sets off, and both go quicker
  const swap = dealt.length > 0 && leavingHand.length > 0;
  const ms = swap ? SWAP_MS : FLIGHT_MS;
  const apart = swap ? SWAP_STAGGER_MS : STAGGER_MS;
  const afterLeaving = swap ? (leavingHand.length - 1) * apart + ms : 0;
  // Cards drawn together land from left to right, wherever the hand's sort puts each one
  dealt.sort((a, b) => restingRect(a.el).left - restingRect(b.el).left).forEach(({ el, start }, i) =>
    fly(el, start, ms, afterLeaving + i * apart));
  // The host's draw sound is heard as the first card sets off; each card after it makes its own as it follows
  echoSound('draw', dealt.slice(swap ? 0 : 1).map((_, i) => afterLeaving + (swap ? i : i + 1) * apart));
  // Cards leaving the hand together (a mulligan, a discard) go one after another from the right, as a deal arrives
  leavingHand.sort((a, b) => b.was.rect.left - a.was.rect.left).forEach(({ was, target, tile, key, onto }, i) => {
    swell(onto, i * apart + ms, swelled);
    if (tile) holdTile(key, i * apart);
    sendTo(was, target ?? was.rect, tile ? 1 : target ? 0.25 : 0, i * apart, undefined, ms);
  });
  settleWaiting(!!model.prompt?.paying);
  const travelled = new Set(trips.keys());
  arriveElsewhere(travelled);
  shiftBoard(travelled, unmaking);
  shiftHand(travelled);
  shiftZones(travelled);
  layOutPiles();
}

/** Slides cards that changed place on the battlefield with no event from the game, once any card being unmade there has gone. */
function shiftBoard(travelled: Set<string>, wait: number): void {
  // Only a change in what the rows hold slides cards, or a resize would make every card on the table drift
  if (boardShape() === lastShape) {
    return;
  }
  // A card folded into a pile slides onto the pile's top card, and the pile is held as it was before anything is measured
  const joining: { was: Snapshot; top: HTMLElement }[] = [];
  const newcomers = new Set<string>();
  for (const { keys, top } of pileSlots()) {
    for (const key of keys) {
      const was = lastSeen.get(key);
      if (was && ownPlace.has(key) && key !== top.dataset.key && !travelled.has(key) && !intoHint.has(key)) {
        joining.push({ was, top });
      }
    }
    // When the pile's top is the card that just joined, the pile stays put and a copy of that card flies in
    const topKey = top.dataset.key as string;
    const topWas = lastSeen.get(topKey);
    // Compared with a card that was already in the pile, not one arriving this frame, which set off from elsewhere
    const stayed = keys.filter(k => k !== topKey && !travelled.has(k)).map(k => lastSeen.get(k)).find(s => s);
    if (topWas && stayed && !travelled.has(topKey) && !intoHint.has(topKey) && apart(topWas.rect, stayed.rect)) {
      joining.push({ was: topWas, top });
      newcomers.add(topKey);
      // Its own turn (a tap) would play at the pile while the copy is still on its way, so it is settled at once
      for (const a of top.getAnimations()) {
        if (a instanceof CSSTransition) a.finish();
      }
    }
  }
  for (const { top } of joining) holdPile(top, SHIFT_MS);
  slide(document.querySelectorAll<HTMLElement>(BOARD_CARDS), new Set([...travelled, ...newcomers]), SHIFT_MS, wait);
  for (const { was, top } of joining) sendTo(was, top.getBoundingClientRect(), 1, 0, top, SHIFT_MS);
}

/** Whether two places are more than a few pixels apart, centre to centre. */
function apart(a: DOMRect, b: DOMRect): boolean {
  return Math.hypot(a.left + a.width / 2 - b.left - b.width / 2, a.top + a.height / 2 - b.top - b.height / 2) > 4;
}

/** Cards left in an open zone, as one is picked from a search, slide into the gap rather than jumping. */
function shiftZones(travelled: Set<string>): void {
  if (zonesShape() === lastZones) {
    return;
  }
  slide(document.querySelectorAll<HTMLElement>(ZONE_CARDS), travelled);
}

/** Cards staying in the hand slide to their new places when the hand's order changes around them. */
function shiftHand(travelled: Set<string>): void {
  if (handShape() === lastHand) {
    return;
  }
  slide(document.querySelectorAll<HTMLElement>(HAND_CARDS), travelled);
}

function slide(cards: Iterable<HTMLElement>, travelled: Set<string>, duration = FLIGHT_MS, delay = 0): void {
  for (const el of cards) {
    const key = el.dataset.key as string;
    const was = lastSeen.get(key);
    if (!was?.laid || travelled.has(key) || fromHint.has(key)) {
      continue;
    }
    // Compared as laid out, since a transform still under way (an attacker stepping forward) is not a move
    const laid = laidCentre(el);
    // A card redrawn as a new element starts from where the old one was showing mid-slide, not where it would have rested
    const carried = was.ghost !== el ? was.drift : undefined;
    const dx = was.laid.x + (carried?.x ?? 0) - laid.x;
    const dy = was.laid.y + (carried?.y ?? 0) - laid.y;
    if (Math.abs(dx) + Math.abs(dy) > 2) {
      // Measured mid-slide the card is still near where it came from, which a flight would set off from
      resting.set(key, restingRect(el));
      // On translate, because animating transform would override the transition that turns a card as it taps
      el.animate([{ translate: `${dx}px ${dy}px` }, { translate: '0px 0px' }],
        { duration, delay, easing: EASE, composite: 'add', fill: 'backwards' });
    }
    if (was.ghost && was.ghost !== el && el.closest('.battlefield')) {
      morph(el, was.ghost, was.size);
    }
  }
}

/** How long a redrawn card takes to turn or resize from how its old element looked; a little longer than a tap's turn. */
const MORPH_MS = 220;

/** Starts a card redrawn as a new element from the old element's turn and size, since it has no earlier look to transition from. */
function morph(el: HTMLElement, old: HTMLElement, oldSize: { w: number; h: number } | undefined): void {
  const wasTapped = old.classList.contains('tapped');
  const isTapped = el.classList.contains('tapped');
  const turn = (wasTapped ? 90 : 0) - (isTapped ? 90 : 0);
  // A tapped card is also drawn at nine tenths its size
  const size = (oldSize?.w && el.offsetWidth ? oldSize.w / el.offsetWidth : 1) * (wasTapped ? .9 : 1) / (isTapped ? .9 : 1);
  if (!turn && Math.abs(size - 1) < 0.02) return;
  el.animate([{ rotate: `${turn}deg`, scale: String(size) }, { rotate: '0deg', scale: '1' }], { duration: MORPH_MS, easing: EASE });
}

/** A card playable from another zone joins the hand strip with no move event, so it flies out of that zone's tile. */
function arriveElsewhere(travelled: Set<string>): void {
  if (!lastSeen.size) {
    return;
  }
  for (const el of document.querySelectorAll<HTMLElement>('#hand .card.elsewhere[data-key]')) {
    const key = el.dataset.key as string;
    if (travelled.has(key) || lastSeen.get(key)?.ghost?.closest('#hand')) {
      continue;
    }
    const tile = lastSeen.get(key)?.rect
      ?? document.querySelector('#me .zone-tile[data-zone="' + el.dataset.from + '"]')?.getBoundingClientRect();
    if (tile?.width) {
      fly(el, tile, FLIGHT_MS, 0);
    }
  }
}

/** Where the page's layout puts an element's centre; offsets, unlike a bounding box, leave out every transform. */
function laidCentre(el: HTMLElement): { x: number; y: number } {
  let x = el.offsetWidth / 2;
  let y = el.offsetHeight / 2;
  for (let at: HTMLElement | null = el; at; at = at.offsetParent as HTMLElement | null) {
    x += at.offsetLeft - (at.offsetParent?.scrollLeft ?? 0);
    y += at.offsetTop - (at.offsetParent?.scrollTop ?? 0);
  }
  return { x, y };
}

/** A card's journey this frame: where it set off, where it ended, and where it turned back if it came home. */
export type Journey = CardMoved & { via?: Place };

/** Each card's first origin and last destination this frame, with the zone it turned back in if it went out and came home. */
export function journeys(events: readonly GameEvent[]): Map<string, Journey> {
  const out = new Map<string, Journey>();
  for (const e of events) {
    if (e.kind !== 'cardMoved') {
      continue;
    }
    const key = String(e.card.ref);
    const earlier = out.get(key);
    if (!earlier) {
      out.set(key, e);
      continue;
    }
    const home = earlier.from?.zone === e.to?.zone && earlier.from?.player?.ref === e.to?.player?.ref;
    out.set(key, { ...e, from: earlier.from, via: home ? earlier.to ?? earlier.via : undefined });
  }
  return out;
}

// Forge undoes a cancelled cast without an event, so a waiting card drawn in a zone again flies home from beside the stack
function settleWaiting(paying: boolean): void {
  for (const [key, held] of waiting) {
    const arrived = stackItemFor(key) ?? cardElement(key);
    if (arrived) {
      const from = whereNow(held);
      land(key);
      fly(arrived, from, FLIGHT_MS, 0);
    } else if (!awaitingSlot(key) && !paying && Date.now() - held.since > SETTLE_MS) {
      land(key);
    }
  }
}

/** Where a waiting spell is drawn now, which is short of its waiting place while it is still on its way there. */
function whereNow(held: Waiting): DOMRect {
  if (held.ghost?.isConnected) {
    return held.ghost.getBoundingClientRect();
  }
  const t = Math.min(1, (Date.now() - held.since) / FLIGHT_MS);
  if (!held.from || t >= 1) {
    return held.rect;
  }
  // The flight eases out, so it is most of the way there early on
  const p = 1 - (1 - t) ** 3;
  const mix = (a: number, b: number) => a + (b - a) * p;
  return new DOMRect(mix(held.from.left, held.rect.left), mix(held.from.top, held.rect.top),
    mix(held.from.width, held.rect.width), mix(held.from.height, held.rect.height));
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
const HAND_CARDS = '#hand .card[data-key]';
const ZONE_CARDS = '#zones .card[data-key]';
const stackItems = () => [...document.querySelectorAll<HTMLElement>('#stack .stack-item:not(.awaiting)')];
const awaitingSlot = (key: string) =>
  document.querySelector<HTMLElement>(`#stack .stack-item.awaiting img[data-key="${key}"]`)?.parentElement ?? null;

export function cardElement(key: string): HTMLElement | null {
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

export function pileTopFor(key: string): HTMLElement | null {
  for (const { keys, top } of pileSlots()) {
    if (keys.includes(key)) {
      return top;
    }
  }
  return null;
}

/** A face-down card in its owner's sleeve, to stand in for a card no one can see. */
function cardBack(model: Model, key: string): HTMLElement {
  const card = model.objects.get(Number(key)) as CardView | undefined;
  const owner = card?.Owner ? model.objects.get(card.Owner.ref) as PlayerView | undefined : undefined;
  const back = make('div', 'card back');
  back.style.setProperty('--sleeve', cssUrl(playerSleeveUrl(owner)));
  return back;
}

/** Keeps a pile as deep and as wide as it was until the card joining it arrives, then lets it open out (board.css). */
function holdPile(top: HTMLElement, duration: number): void {
  const slot = top.closest<HTMLElement>('.slot');
  if (!slot) return;
  const now = Math.min(3, Math.max(0, (slot.dataset.members ?? '').split(',').length - 1));
  const was = Math.max(0, now - 1);
  if (now === was) return;
  top.dataset.heldDepth = String(was);
  slot.style.setProperty('--held-behind', String(was));
  slot.classList.add('holding');
  setTimeout(() => {
    delete top.dataset.heldDepth;
    slot.classList.replace('holding', 'opening');
    top.classList.add('grown');
    setTimeout(() => {
      slot.classList.remove('opening');
      top.classList.remove('grown');
    }, 300);
  }, duration);
}

/** Keeps a pile's count at what it was until the copy joining it arrives, then shows the new one. */
function holdCount(el: HTMLElement, duration: number): void {
  el.querySelector<HTMLElement>('.count')?.animate([{ opacity: 0 }, { opacity: 0, offset: 0.9 }, { opacity: 1 }], { duration, easing: 'linear' });
}

/** Keeps a zone tile's picture of a card hidden until the copy flying to it arrives, rather than showing it at once. */
function holdTile(key: string | undefined, delay: number): void {
  const img = key ? document.querySelector<HTMLElement>(`.zone-tile img[data-key="${key}"]`) : null;
  img?.animate([{ opacity: 0 }, { opacity: 0, offset: 0.92 }, { opacity: 1 }], { duration: FLIGHT_MS + delay, easing: 'linear' });
}

/** The zone tile a card has come to rest on. A card that went anywhere else has none, nor has a token, which is gone by now. */
function landedTile(model: Model, key: string, to: Place | undefined): HTMLElement | null {
  const card = model.objects.get(Number(key)) as CardView | undefined;
  if (!to?.player || card?.Zone !== to.zone) {
    return null;
  }
  return document.querySelector<HTMLElement>(`.seat[data-player="${to.player.ref}"] .zone-tile[data-zone="${to.zone}"]`);
}

/** Swells a zone tile once as a card lands on it, however many land together. */
function swell(tile: HTMLElement | null | undefined, delay: number, swelled: Set<HTMLElement>): void {
  if (!tile || swelled.has(tile)) {
    return;
  }
  swelled.add(tile);
  tile.animate([{ scale: '1' }, { scale: '1.12', filter: 'brightness(1.5)', offset: 0.35 }, { scale: '1' }], { duration: 480, delay, easing: EASE });
}

// A face-down top card is not drawn on its tile, and its hidden picture measures nothing
function tileImageRect(key: string): DOMRect | null {
  const img = document.querySelector(`.zone-tile img[data-key="${key}"]`);
  const rect = img?.getBoundingClientRect();
  return rect?.width ? rect : null;
}

/** The icon standing for another player's hand, if that is what the hand is drawn as. */
function handFanOf(place: Place): HTMLElement | null {
  const seat = place.player ? document.querySelector<HTMLElement>(`.seat[data-player="${place.player.ref}"]`) : null;
  const fan = seat && seat.id !== 'me' ? seat.querySelector<HTMLElement>('.hand-fan') : null;
  return fan && !fan.hidden ? fan : null;
}

/** Roughly where a zone is drawn, for a card with no element of its own at one end of its trip. */
function placeRect(place: Place | undefined): DOMRect | null {
  if (!place) {
    return null;
  }
  const seat = place.player ? document.querySelector<HTMLElement>(`.seat[data-player="${place.player.ref}"]`) : null;
  const rectOf = (el: Element | null | undefined) => {
    const rect = el?.getBoundingClientRect();
    return rect?.width ? rect : null;
  };
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
      // A commander has its tile among the zones; the rest of the command zone sits by the portrait
      return rectOf(seat?.querySelector('.zone-tile[data-zone="Command"]')) ?? rectOf(seat?.querySelector('.emblems'));
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

/** The battlefields' slots in order, each with the cards it holds, which changes only when a row's contents do. */
function boardShape(): string {
  // A zone switching between one line and two moves its cards as much as a card coming or going
  const lines = [...document.querySelectorAll<HTMLElement>('.battlefield .group')].map(g => g.dataset.lines ?? '').join('');
  return lines + '#' + [...document.querySelectorAll<HTMLElement>('#me .battlefield .slot, #opponent .battlefield .slot')]
    .map(slot => `${slot.dataset.members ?? ''}:${[...slot.querySelectorAll<HTMLElement>('.card')].map(c => c.dataset.key).join('+')}`)
    .join('|');
}

const handShape = () => [...document.querySelectorAll<HTMLElement>(HAND_CARDS)].map(c => c.dataset.key).join(',');

let lastShape = '';
let lastHand = '';
let lastZones = '';
const zonesShape = () => [...document.querySelectorAll<HTMLElement>(ZONE_CARDS)].map(c => c.dataset.key).join(',');

/** Where a hovered hand card rests, worked out from its resting transform (hand.css) because setting it would cut short a rise. */
function unhovered(el: HTMLElement): DOMRect {
  const style = getComputedStyle(el);
  const [ox, oy] = style.transformOrigin.split(' ').map(parseFloat);
  const w = el.offsetWidth, h = el.offsetHeight;
  // The box the card's corners reach under a transform, measured from its transform origin
  const reach = (m: DOMMatrix) => {
    const corners = [[0, 0], [w, 0], [0, h], [w, h]].map(([x, y]) => m.transformPoint(new DOMPoint(x - ox, y - oy)));
    const xs = corners.map(p => p.x), ys = corners.map(p => p.y);
    return { left: Math.min(...xs), top: Math.min(...ys), right: Math.max(...xs), bottom: Math.max(...ys) };
  };
  const now = reach(new DOMMatrix(style.transform === 'none' ? undefined : style.transform));
  const rect = el.getBoundingClientRect();
  const drop = parseFloat(el.style.getPropertyValue('--drop')) || 0;
  const tilt = parseFloat(el.style.getPropertyValue('--tilt')) || 0;
  const rest = reach(new DOMMatrix().translate(0, drop).rotate(tilt));
  const x = rect.left - now.left, y = rect.top - now.top;
  return new DOMRect(x + rest.left, y + rest.top, rest.right - rest.left, rest.bottom - rest.top);
}

/** Remembers where every card stands now, for the moves the next frame brings. */
function note(): void {
  lastShape = boardShape();
  lastHand = handShape();
  lastZones = zonesShape();
  lastSeen.clear();
  ownPlace.clear();
  for (const el of document.querySelectorAll<HTMLElement>(CARDS)) {
    // The element itself: one that leaves the page is dropped, never reused, so it keeps this frame's look for a ghost
    const rest = el.matches('#hand .card:hover') ? unhovered(el) : restingRect(el);
    const now = moving(el) ? el.getBoundingClientRect() : rest;
    lastSeen.set(el.dataset.key as string, {
      rect: rest, ghost: el, laid: laidCentre(el), size: { w: el.offsetWidth, h: el.offsetHeight }, tapped: el.classList.contains('tapped'),
      drift: { x: now.left + now.width / 2 - (rest.left + rest.width / 2), y: now.top + now.height / 2 - (rest.top + rest.height / 2) },
    });
    ownPlace.add(el.dataset.key as string);
  }
  for (const el of stackItems()) {
    const key = el.querySelector('img')?.dataset.key;
    if (key && !lastSeen.has(key)) {
      lastSeen.set(key, { rect: el.getBoundingClientRect(), ghost: null });
    }
  }
  // The top of a zone tile, such as a commander waiting in its tile, sets off from its picture there
  for (const img of document.querySelectorAll<HTMLElement>('.zone-tile img[data-key]:not([hidden])')) {
    const key = img.dataset.key as string;
    const rect = img.getBoundingClientRect();
    if (key && rect.width && !lastSeen.has(key)) {
      lastSeen.set(key, { rect, ghost: null });
    }
  }
  // A copy folded into a pile stands where the pile's top card stands, so it leaves from there if it leaves
  for (const { keys, top } of pileSlots()) {
    const shown = lastSeen.get(top.dataset.key as string);
    for (const key of keys) {
      if (shown && covered.has(key)) {
        lastSeen.set(key, { ...shown });
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

function hold(key: string, from: DOMRect, seen: Snapshot | null): void {
  const spot = waitingSpot(from);
  if (!seen?.ghost) {
    // Nothing to show waiting (an opponent's card), but its item still flies in from where it came
    waiting.set(key, { rect: from, ghost: null, since: Date.now() });
    return;
  }
  const shown = place(seen.ghost, from, seen.size);
  shown.classList.add('paying');
  // A card waiting to be paid for is still a card you want to read, so it answers the pointer
  shown.style.pointerEvents = 'auto';
  hoverable(shown, shown.querySelector('img') ?? shown);
  shown.animate([
    { translate: '0px 0px', scale: '1' },
    { translate: shiftTo(shown, spot.width / from.width, spot), scale: String(spot.width / from.width) },
  ], { duration: FLIGHT_MS, easing: EASE, fill: 'forwards' });
  waiting.set(key, { rect: spot, ghost: shown, since: Date.now() });
}

function land(key: string): void {
  waiting.get(key)?.ghost?.remove();
  waiting.delete(key);
}

/** A copy of a card at its laid-out size, centred where the card was seen, so a tapped card's copy is turned and not squeezed. */
function place(from: HTMLElement, rect: DOMRect, size?: { w: number; h: number }): HTMLElement {
  // Copied only now, when a ghost is actually shown, rather than for every card on every frame
  const ghost = from.cloneNode(true) as HTMLElement;
  const w = size?.w || rect.width;
  const h = size?.h || rect.height;
  const left = rect.left + rect.width / 2 - w / 2;
  const top = rect.top + rect.height / 2 - h / 2;
  ghost.style.cssText += `position: fixed; left: ${left}px; top: ${top}px; width: ${w}px; height: ${h}px;`
    + 'margin: 0; z-index: 40; pointer-events: none;';
  document.body.append(ghost);
  return ghost;
}

/** The shift that centres a scaled element on a place, measured with the scale applied since cards grow about different points. */
function shiftTo(el: HTMLElement, scale: number, to: DOMRect): string {
  el.style.scale = String(scale);
  const now = el.getBoundingClientRect();
  el.style.scale = '';
  return `${to.left + to.width / 2 - (now.left + now.width / 2)}px ${to.top + to.height / 2 - (now.top + now.height / 2)}px`;
}

// The card's own transform (a tap's turn, a tilt in hand) is left alone: the trip moves and scales it on top
function pop(el: HTMLElement): void {
  el.animate([
    { scale: '.7', opacity: 0 },
    { scale: '1', opacity: 1 },
  ], { duration: POP_MS, easing: 'cubic-bezier(.2,.9,.3,1.2)' });
}

function fly(el: HTMLElement, from: DOMRect, duration: number, delay: number): void {
  for (const a of el.getAnimations()) {
    if (a.id === FLIGHT) a.cancel();
  }
  const to = restingRect(el);
  if (!to.width || !from.width) {
    return;
  }
  if (el.dataset.key) {
    resting.set(el.dataset.key, to);
  }
  const scale = from.width / to.width;
  const start = { translate: shiftTo(el, scale, from), scale: String(scale) };
  // A card waiting its turn in a deal is not shown until it sets off; it appears where it starts as it leaves
  const flight = el.animate([
    ...(delay ? [{ ...start, opacity: 0 }, { ...start, opacity: 1, offset: 0.001 }] : [{ ...start, opacity: 1 }]),
    { translate: '0px 0px', scale: '1', opacity: 1 },
  ], { duration, delay, easing: EASE, fill: 'backwards' });
  flight.id = FLIGHT;
  // A ring marks a card where it rests, so a card in the air wears none until it lands (board.css)
  el.classList.add('landing');
  const landed = () => { if (!el.getAnimations().some(a => a.id === FLIGHT)) el.classList.remove('landing'); };
  flight.finished.then(landed, landed);
}

// The card is already gone from the model, so a copy of it makes the trip
function sendTo(was: Snapshot, target: DOMRect, endOpacity: number, delay = 0, onto?: HTMLElement, duration = FLIGHT_MS): void {
  if (!was.ghost) {
    return;
  }
  const ghost = place(was.ghost, was.rect, was.size);
  // One card makes the trip, looking as it did when it set off, even when it was the top of a pile
  ghost.classList.remove('pile');
  if (was.tapped !== undefined) ghost.classList.toggle('tapped', was.tapped);
  // A copy joining a pile turned the other way turns as it goes, so its scale is measured along the card
  const tapped = (el: HTMLElement) => el.classList.contains('tapped');
  const turn = onto ? Number(tapped(onto)) - Number(tapped(ghost)) : 0;
  const along = (el: HTMLElement) => el.offsetWidth * (tapped(el) ? TAPPED_SCALE : 1);
  const scale = turn && onto ? along(onto) / along(ghost) : target.width / ghost.getBoundingClientRect().width;
  ghost.animate([
    { translate: '0px 0px', scale: '1', rotate: '0deg', opacity: 1 },
    { translate: shiftTo(ghost, scale, target), scale: String(scale), rotate: `${turn * 90}deg`, opacity: endOpacity },
  ], { duration, delay, easing: 'cubic-bezier(.4,0,.8,.4)', fill: 'backwards' }).finished.then(() => ghost.remove(), () => ghost.remove());
}
