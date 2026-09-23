import type { ServerMessage } from './protocol';

// An opponent's turn arrives faster than it can be read, so messages are held back and released on a timer.
// Only what you watch is paced: the moment the game wants an answer from you, the queue is emptied at once,
// so nothing you click is ever stale.

const STEP_MS = 350;
// Past this the game is further ahead than pacing can usefully close, so it catches up instead
const BACKLOG = 8;
// Held back while you only watch; everything else is a message you act on or read alongside the board
const WATCHED = new Set<ServerMessage['t']>(['state', 'log', 'sound']);

let queue: ServerMessage[] = [];
let timer = 0;
let deliver: (msg: ServerMessage) => void = () => {};
let acting = false;

export function initPace(fn: (msg: ServerMessage) => void): void {
  deliver = fn;
}

export function pace(msg: ServerMessage): void {
  if (msg.t === 'prompt') {
    acting = !!msg.priority || !!msg.paying;
  }
  const full = (msg.t === 'state' || msg.t === 'log') && msg.full;
  if (acting || !WATCHED.has(msg.t) || full || queue.length >= BACKLOG) {
    flush();
    deliver(msg);
    return;
  }
  queue.push(msg);
  if (!timer) {
    timer = setTimeout(step, STEP_MS);
  }
}

/** Empties the queue without waiting: a new match starts from nothing the old one left behind. */
export function resetPace(): void {
  clearTimeout(timer);
  timer = 0;
  queue = [];
  acting = false;
}

function step(): void {
  timer = 0;
  const msg = queue.shift();
  if (msg) {
    deliver(msg);
  }
  if (queue.length) {
    timer = setTimeout(step, STEP_MS);
  }
}

function flush(): void {
  clearTimeout(timer);
  timer = 0;
  const pending = queue;
  queue = [];
  for (const msg of pending) {
    deliver(msg);
  }
}
