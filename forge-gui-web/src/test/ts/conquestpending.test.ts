import { expect, test } from 'vitest';
import { pendingAmounts } from '../../main/ts/conquestpending';
import type { ConquestStep } from '../../main/ts/protocol';

const step = (kind: string, amount = 0, shards: number[] = []): ConquestStep => ({
  kind, amount, number: 0, total: 0, chaos: false,
  cards: shards.length ? shards.map(s => ({ name: '', image: '', rarity: 'Common', shards: s })) : undefined,
});
const steps = [step('CONQUER_EMBLEMS', 1), step('WHEEL'), step('BOOSTER', 0, [0, 100, 300]), step('BOOSTER', 0, [0, 100]), step('DUPLICATE_SHARDS', 500)];

// Fails if a booster's duplicate shards are left out, or the closing total is counted on top of them
test('duplicates are owed once, from the packs', () => {
  expect(pendingAmounts(steps, 0)).toEqual({ shards: 500, emblems: 1 });
});

// Fails if steps already shown are still held back from the bar
test('only steps still to come are owed', () => {
  expect(pendingAmounts(steps, 1)).toEqual({ shards: 500, emblems: 0 });
  expect(pendingAmounts(steps, 3)).toEqual({ shards: 100, emblems: 0 });
  expect(pendingAmounts(steps, 5)).toEqual({ shards: 0, emblems: 0 });
});

// Fails if the wheel's shards or bonus emblems are not owed until their step
test('the wheel pays in its own step', () => {
  expect(pendingAmounts([step('WHEEL'), step('SHARDS', 1000)], 0)).toEqual({ shards: 1000, emblems: 0 });
  expect(pendingAmounts([step('WHEEL'), step('EMBLEMS', 5)], 1)).toEqual({ shards: 0, emblems: 5 });
});
