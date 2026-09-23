import type { GameEvent, ServerMessage } from './protocol';

// The computer plays faster than anyone can follow, so what you watch is released at a pace a person can read: each
// update holds the next one back for as long as what happened in it takes to see, a block longer than a land played.
// Messages about the game keep the order they were sent in, so a question is never asked about a board you have not
// been shown yet; while one waits, what comes before it plays out faster, so you are not kept long from answering.
// While you hold priority nothing is held, so what you do yourself shows at once.

/** An update with nothing in it worth watching: a counter ticking, the step moving on. */
const QUIET_MS = 150;
const HOLD_MS: Record<GameEvent['kind'], number> = {
  attackersDeclared: 1300,
  blockersDeclared: 1300,
  cardMoved: 450,
  cardDamaged: 600,
  playerDamaged: 600,
  shuffled: 300,
};
/** A spell or ability going on the stack is the moment to see what the opponent is doing. */
const CAST_MS = 900;
/** How far behind the game the board may fall: nothing is shown later than this after it arrived. */
const MOST_BEHIND_MS = 6000;
/** How much faster the queue plays out while a question waits at the end of it. */
const HURRY = 0.3;
/** What belongs to the game's order. The rest (chat, notices, hover details) has nothing to wait for. */
const IN_ORDER = new Set<ServerMessage['t']>(['state', 'log', 'sound', 'flash', 'prompt', 'playable', 'zones', 'controls',
  'request', 'gameOver']);

let queue: { msg: ServerMessage; at: number }[] = [];
let timer = 0;
/** The update on screen now: when it was shown and how long it holds the next back at full pace. */
let holding = { since: 0, base: 0 };
let deliver: (msg: ServerMessage) => void = () => {};
let acting = false;

export function initPace(fn: (msg: ServerMessage) => void): void {
  deliver = fn;
}

export function pace(msg: ServerMessage): void {
  if (!IN_ORDER.has(msg.t)) {
    deliver(msg);
    return;
  }
  // A whole new table replaces everything still waiting to be shown
  if ((msg.t === 'state' || msg.t === 'log') && msg.full) {
    flush();
  }
  queue.push({ msg, at: Date.now() });
  if (timer) {
    // What joins the queue changes how fast it should play out, so the wait already running is measured again
    schedule();
  } else {
    release();
  }
}

/** Empties the queue without waiting: a new match starts from nothing the old one left behind. */
export function resetPace(): void {
  clearTimeout(timer);
  timer = 0;
  queue = [];
  acting = false;
}

/** How long an update is held on screen before the next one, at full pace. */
export function holdFor(msg: ServerMessage): number {
  if (msg.t !== 'state' || msg.full) {
    return 0;
  }
  let hold = QUIET_MS;
  for (const event of msg.events) {
    const cast = event.kind === 'cardMoved' && event.to?.zone === 'Stack';
    hold = Math.max(hold, cast ? CAST_MS : HOLD_MS[event.kind]);
  }
  return hold;
}

function answerWanted(msg: ServerMessage): boolean {
  return msg.t === 'request' || (msg.t === 'prompt' && (msg.priority || msg.paying));
}



/** Shows what is next in line, and whatever follows it that has nothing to hold it back. */
function release(): void {
  timer = 0;
  while (queue.length) {
    const { msg } = queue.shift()!;
    if (msg.t === 'prompt') {
      acting = msg.priority || msg.paying;
    }
    deliver(msg);
    const base = acting ? 0 : holdFor(msg);
    // What has waited as long as the board may fall behind is shown now, whatever is on screen
    const overdue = queue.length > 0 && queue[0].at + MOST_BEHIND_MS <= Date.now();
    if (base > 0 && !overdue) {
      holding = { since: Date.now(), base };
      schedule();
      return;
    }
  }
}

function schedule(): void {
  clearTimeout(timer);
  const speed = queue.some(({ msg }) => answerWanted(msg)) ? HURRY : 1;
  let until = holding.since + holding.base * speed;
  if (queue.length) {
    until = Math.min(until, queue[0].at + MOST_BEHIND_MS);
  }
  timer = setTimeout(release, Math.max(0, until - Date.now()));
}

function flush(): void {
  clearTimeout(timer);
  timer = 0;
  const pending = queue;
  queue = [];
  for (const { msg } of pending) {
    deliver(msg);
  }
}
