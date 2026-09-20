// Cards move rather than jump: one drawn flies from the library into the hand, one cast waits beside the stack
// while its cost is paid, and one put back flies home. Only your hand and the stack are measured each render,
// so a wide board costs nothing.

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
const FLIGHT_MS = 240;
const DEAL_MS = 320;
const STAGGER_MS = 55;
const SETTLE_MS = 900;
const POP_MS = 220;

export function animateCardMoves(model) {
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
    const back = waiting.get(key)?.rect ?? onBoard.get(key) ?? onStack.get(key);
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
  return !!document.querySelector(`.card[data-key="${key}"]`) || stackItems().some(el => keyOf(el) === key);
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
  ghost.animate([
    { transform: 'none' },
    {
      transform: `translate(${spot.left - was.rect.left}px, ${spot.top - was.rect.top}px)`
        + ` scale(${spot.width / was.rect.width})`,
    },
  ], { duration: FLIGHT_MS, easing: 'cubic-bezier(.2,.7,.3,1)', fill: 'forwards' });
  waiting.set(key, { rect: spot, ghost, since: Date.now() });
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
