import { expect, test } from 'vitest';
import { pendingAmounts } from '../../main/ts/conquestpending';
import type { RewardStep } from '../../main/ts/protocol';

const SHARD = 'IMG_AETHER_SHARD', EMBLEM = 'IMG_PW_BADGE_COMMON';
/** The balance each kind of step pays into, as the server names it. */
const ICONS: Record<string, string> = { SHARDS: SHARD, BOOSTER: SHARD, EMBLEMS: EMBLEM, CONQUER_EMBLEMS: EMBLEM };

const step = (kind: string, amount = 0, shards: number[] = []): RewardStep => ({
  kind, amount, number: 0, total: 0, chaos: false, icon: ICONS[kind],
  cards: shards.length ? shards.map(s => ({ name: '', image: '', rarity: 'Common', shards: s })) : undefined,
});
const steps = [step('CONQUER_EMBLEMS', 1), step('WHEEL'), step('BOOSTER', 0, [0, 100, 300]), step('BOOSTER', 0, [0, 100]), step('DUPLICATE_SHARDS', 500)];

// Fails if a booster's duplicate shards are left out, or the closing total is counted on top of them
test('duplicates are owed once, from the packs', () => {
  expect(pendingAmounts(steps, 0)).toEqual({ [SHARD]: 500, [EMBLEM]: 1 });
});

// Fails if steps already shown are still held back from the bar
test('only steps still to come are owed', () => {
  expect(pendingAmounts(steps, 1)).toEqual({ [SHARD]: 500 });
  expect(pendingAmounts(steps, 3)).toEqual({ [SHARD]: 100 });
  expect(pendingAmounts(steps, 5)).toEqual({});
});

// Fails if the wheel's shards or bonus emblems are not owed until their step
test('the wheel pays in its own step', () => {
  expect(pendingAmounts([step('WHEEL'), step('SHARDS', 1000)], 0)).toEqual({ [SHARD]: 1000 });
  expect(pendingAmounts([step('WHEEL'), step('EMBLEMS', 5)], 1)).toEqual({ [EMBLEM]: 5 });
});
