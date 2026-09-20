// Cards move rather than jump: one drawn flies from the library into the hand, one cast waits beside the stack
// while its cost is paid, and one put back flies home. Only your hand and the stack are measured each render,
// so a wide board costs nothing.

import { hoverable } from './detail.js';

const inHand = new Map();
const onStack = new Map();
const onBoard = new Map();
const gaveGhost = new Map();
const fromHint = new Map();
const intoHint = new Map();

/** The cards of a pile that has just been laid out all start where the pile stood. */
export function spreadFrom(keys, rect) {
  for (const key of keys) {
    fromHint.set(key, rect);
  }
}

/** The cards of a pile being put back together end where its top card stands. */
export function mergeInto(keys, rect) {
  for (const key of keys) {
    intoHint.set(key, rect);
  }
}
const waiting = new Map();
/** Where a spell sat while its cost was paid, remembered after the ghost goes so a cancelled cast flies
 *  home from that spot rather than from the library, which is where an unknown card comes from. */
const waited = new Map();
const FLIGHT_MS = 240;
const DEAL_MS = 320;
const STAGGER_MS = 55;
const SETTLE_MS = 900;
const WAITED_MEMORY_MS = 8000;
const POP_MS = 220;

export function animateCardMoves(model) {
  notePiles();
  const arrived = new Set();
  // A card cast from hand stops on the stack before it reaches the table, so every hop is followed
  for (const el of [...document.querySelectorAll('.row .card[data-key], .slot .card[data-key]'), ...stackItems()]) {
    const key = keyOf(el);
    // Only a card that has just arrived from somewhere else flies; one the row merely shuffled along stays put
    const was = waiting.get(key)?.rect ?? inHand.get(key)?.rect ?? fromHint.get(key)
      ?? (onBoard.has(key) ? null : onStack.get(key));
    if (was) {
      arrived.add(key);
      land(key);
      fly(el, was, FLIGHT_MS, 0);
    } else if (!onBoard.has(key) && !onStack.has(key) && !el.classList.contains('stack-item')) {
      // A token, or anything else that arrives from nowhere, grows into place rather than blinking on
      arrived.add(key);
      pop(el);
    }
  }
  const hand = [...document.querySelectorAll('#hand .card[data-key]')];
  const library = document.querySelector('#me .zone-tile[data-zone="Library"]')?.getBoundingClientRect();
  let dealt = 0;
  for (const el of hand) {
    const key = el.dataset.key;
    // A cancelled cost comes back from where it waited, a bounced permanent from where it stood, the rest are drawn
    const back = waiting.get(key)?.rect ?? onBoard.get(key) ?? onStack.get(key) ?? recentWait(key);
    if (back) {
      land(key);
      fly(el, back, FLIGHT_MS, 0);
    } else if (library && !inHand.has(key)) {
      fly(el, library, DEAL_MS, dealt++ * STAGGER_MS);
    }
  }
  // A whole hand leaving at once is a mulligan; a single card is a spell on its way out, so it waits and sees
  const gone = [...inHand].filter(([key]) => !arrived.has(key) && !onScreen(key) && !waiting.has(key));
  for (const [key, was] of gone) {
    // A card that has turned up on a zone tile went there: discarded, milled or exiled, not cast
    const tile = tileFor(key);
    if (tile) {
      sendTo(was, tile, 0.25);
    } else if (gone.length === 1) {
      hold(key, was);
    } else if (library) {
      sendTo(was, library, 0.2);
    }
  }
  // A card still missing once the payment prompt has gone was never cast, so it stops waiting
  const paying = !!model?.prompt?.paying;
  for (const [key, held] of waiting) {
    if (!paying && Date.now() - held.since > SETTLE_MS) {
      land(key);
    }
  }
  for (const [key, w] of waited) {
    if (Date.now() - w.at > WAITED_MEMORY_MS) {
      waited.delete(key);
    }
  }
  leaveTheBoard();
  fromHint.clear();
  intoHint.clear();
  note(hand);
}

// A permanent that has left the table goes to the graveyard, or simply fades where it stood
function leaveTheBoard() {
  for (const [key, was] of onBoard) {
    if (onScreen(key) || waiting.has(key)) {
      continue;
    }
    const ghost = gaveGhost.get(key);
    if (!ghost) {
      continue;
    }
    const merge = intoHint.get(key);
    const target = merge ?? tileFor(key) ?? graveyardFor(was);
    sendTo({ rect: was, ghost }, target ?? was, merge ? 1 : 0);
  }
}

function graveyardFor(rect) {
  const mine = rect.top > innerHeight / 2 ? '#me' : '#opponent';
  const tile = document.querySelector(`${mine} .zone-tile[data-zone="Graveyard"]`);
  return tile ? tile.getBoundingClientRect() : null;
}

const stackItems = () => [...document.querySelectorAll('#stack .stack-item')];
// A stack item is keyed by the item, not the card, so the card's own key comes off its picture
const keyOf = el => (el.classList.contains('stack-item') ? el.querySelector('img')?.dataset.key : el.dataset.key);

// The top card of a graveyard or exile pile is drawn on its tile, which is where a card sent there lands
function tileFor(key) {
  const img = document.querySelector(`.zone-tile img[data-key="${key}"]`);
  return img ? img.getBoundingClientRect() : null;
}

function onScreen(key) {
  return covered.has(key) || !!document.querySelector(`.card[data-key="${key}"]`)
    || stackItems().some(el => keyOf(el) === key);
}

/** The keys a pile is holding behind its top card. They have no element, and they have not gone anywhere. */
let covered = new Set();

function notePiles() {
  covered = new Set();
  for (const { keys, top } of pileSlots()) {
    for (const key of keys) {
      if (key !== top.dataset.key) {
        covered.add(key);
      }
    }
  }
}

function pileSlots() {
  const out = [];
  for (const slot of document.querySelectorAll('.slot[data-members]')) {
    const top = slot.querySelector(':scope > .card:last-child');
    if (top) {
      out.push({ keys: slot.dataset.members.split(','), top });
    }
  }
  return out;
}

function note(hand) {
  inHand.clear();
  for (const el of hand) {
    inHand.set(el.dataset.key, { rect: el.getBoundingClientRect(), ghost: el.cloneNode(true) });
  }
  onStack.clear();
  for (const el of stackItems()) {
    const key = keyOf(el);
    if (key) {
      onStack.set(key, el.getBoundingClientRect());
    }
  }
  onBoard.clear();
  gaveGhost.clear();
  for (const el of document.querySelectorAll('.row .card[data-key], .slot .card[data-key]')) {
    onBoard.set(el.dataset.key, el.getBoundingClientRect());
    gaveGhost.set(el.dataset.key, el.cloneNode(true));
  }
  // A copy folded into a pile stands where the pile's top card stands, so it leaves from there if it leaves
  for (const { keys, top } of pileSlots()) {
    const rect = onBoard.get(top.dataset.key);
    const ghost = gaveGhost.get(top.dataset.key);
    for (const key of keys) {
      if (key !== top.dataset.key && rect) {
        onBoard.set(key, rect);
        gaveGhost.set(key, ghost.cloneNode(true));
      }
    }
  }
}

// A spell waits beside the stack, where it is about to go, rather than over the cards on the table
function waitingSpot(rect) {
  const stack = document.getElementById('stack');
  const panel = stack && !stack.hidden ? stack.getBoundingClientRect() : null;
  const board = document.getElementById('me')?.getBoundingClientRect();
  const width = rect.width * 0.8;
  const height = rect.height * 0.8;
  const right = panel ? panel.left : (board?.right ?? innerWidth) - 8;
  const top = panel ? panel.top : (board?.top ?? 80);
  return new DOMRect(right - width - 12, top, width, height);
}

function hold(key, was) {
  const spot = waitingSpot(was.rect);
  const ghost = place(was.ghost, was.rect);
  ghost.classList.add('paying');
  // A card waiting to be paid for is still a card you want to read, so it answers the pointer
  ghost.style.pointerEvents = 'auto';
  hoverable(ghost, ghost.querySelector('img') ?? ghost);
  ghost.animate([
    { transform: 'none' },
    {
      transform: `translate(${spot.left - was.rect.left}px, ${spot.top - was.rect.top}px)`
        + ` scale(${spot.width / was.rect.width})`,
    },
  ], { duration: FLIGHT_MS, easing: 'cubic-bezier(.2,.7,.3,1)', fill: 'forwards' });
  waiting.set(key, { rect: spot, ghost, since: Date.now() });
  waited.set(key, { rect: spot, at: Date.now() });
}

/** The spot a spell waited in, if it did so lately. Answered once, because it only flies home once. */
function recentWait(key) {
  const w = waited.get(key);
  waited.delete(key);
  return w && Date.now() - w.at <= WAITED_MEMORY_MS ? w.rect : null;
}

function land(key) {
  waiting.get(key)?.ghost.remove();
  waiting.delete(key);
}

function place(ghost, rect) {
  ghost.style.cssText = `position: fixed; left: ${rect.left}px; top: ${rect.top}px; width: ${rect.width}px;`
    + `height: ${rect.height}px; margin: 0; z-index: 40; pointer-events: none;`;
  document.body.append(ghost);
  return ghost;
}

function pop(el) {
  el.animate([
    { transform: 'scale(.7)', opacity: 0 },
    { transform: 'none', opacity: 1 },
  ], { duration: POP_MS, easing: 'cubic-bezier(.2,.9,.3,1.2)' });
}

function fly(el, from, duration, delay) {
  const to = el.getBoundingClientRect();
  if (!to.width || !from.width) {
    return;
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
function sendTo(was, target, endOpacity) {
  const ghost = place(was.ghost, was.rect);
  ghost.animate([
    { transform: 'none', opacity: 1 },
    {
      transform: `translate(${target.left - was.rect.left}px, ${target.top - was.rect.top}px) scale(${target.width / was.rect.width})`,
      opacity: endOpacity,
    },
  ], { duration: FLIGHT_MS, easing: 'cubic-bezier(.4,0,.8,.4)' }).finished.then(() => ghost.remove(), () => ghost.remove());
}
