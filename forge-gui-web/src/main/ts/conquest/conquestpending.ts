// The balances the server sends already hold the whole reward, so the bar shows them less whatever has yet to be revealed

import type { RewardStep } from '../protocol';

/** What every step at index from or later pays, by the icon of the balance it pays into. A pack pays through its cards, one by one. */
export function pendingAmounts(steps: RewardStep[], from: number): Record<string, number> {
  const owed: Record<string, number> = {};
  const add = (icon: string | undefined, n: number) => { if (icon && n) owed[icon] = (owed[icon] ?? 0) + n; };
  for (const step of steps.slice(from)) {
    // A step with no icon pays nothing of its own: Conquest's closing total repeats what the packs before it hold
    if (step.cards) for (const card of step.cards) add(step.icon, card.shards);
    else add(step.icon, step.amount);
  }
  return owed;
}
