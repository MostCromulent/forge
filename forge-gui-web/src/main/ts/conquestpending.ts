// What a reward's steps still owe the bar. The balances the server sends already hold the whole reward, so the bar
// shows them less whatever has yet to be revealed.

import type { ConquestStep } from './protocol';

/** The shards and emblems of every step at index from or later. A pack's duplicates are owed card by card. */
export function pendingAmounts(steps: ConquestStep[], from: number): { shards: number; emblems: number } {
  let shards = 0, emblems = 0;
  for (const step of steps.slice(from)) {
    // DUPLICATE_SHARDS repeats what the packs before it already hold
    if (step.kind === 'SHARDS') shards += step.amount;
    else if (step.kind === 'CONQUER_EMBLEMS' || step.kind === 'EMBLEMS') emblems += step.amount;
    else if (step.kind === 'BOOSTER') for (const card of step.cards ?? []) shards += card.shards;
  }
  return { shards, emblems };
}
