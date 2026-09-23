// This is what paces the game. When it is about to pass priority for you and something has happened you have not
// seen, the pass button fills for a moment first: the game waits on it, so what the computer just did stays on the
// board to be read, and you can stop the pass to act instead. The server decides when a pass is worth showing.

import { setting } from './settings';
import type { AutoPassRequest } from './protocol';

/** A pass on its way: which question it answers, how long it fills for, and since when. */
export interface Countdown {
  id: number;
  ms: number;
  since: number;
}

let timer = 0;
let answer: (id: number, go: boolean) => void = () => {};
let changed: () => void = () => {};
let current: Countdown | null = null;

export function initAutoPass(reply: (id: number, go: boolean) => void, redraw: () => void): void {
  answer = reply;
  changed = redraw;
}

export const countdown = (): Countdown | null => current;

/** Starts filling the button; never quicker than the pause the game itself would have taken. */
export function startCountdown(req: AutoPassRequest): void {
  clearTimeout(timer);
  const ms = Math.max(req.delay, Number(setting('autoPassDelay')));
  current = { id: req.id, ms, since: Date.now() };
  timer = setTimeout(() => finishCountdown(true), ms);
  changed();
}

/** Passes now, or stops the pass so the game asks for priority as usual. */
export function finishCountdown(go: boolean): void {
  if (!current) {
    return;
  }
  clearTimeout(timer);
  const id = current.id;
  current = null;
  answer(id, go);
  changed();
}

/** Forgets a countdown without answering it: the server has moved on, or asks again after a reconnect. */
export function dropCountdown(): void {
  clearTimeout(timer);
  current = null;
}
