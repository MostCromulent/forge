import { expect, test } from 'vitest';
import { shopTotal } from '../../main/ts/rogue/shop';

const ITEMS = [{ id: 0, price: 3 }, { id: 1, price: 2 }, { id: 2, price: 4 }, { id: 3, price: 3 }];

// Fails if an item dearer than the gold left can still be picked, one that fits exactly is dimmed, or a picked item is dimmed, which would keep the player from putting it back
test('an item that no longer fits is dimmed, and a picked one never is', () => {
  const total = shopTotal(ITEMS, [0, 1], 8);
  expect(total.cost).toBe(5);
  expect(total.left).toBe(3);
  expect([...total.dimmed]).toEqual([2]);
  expect([...shopTotal(ITEMS, [2, 0], 7).dimmed]).toEqual([1, 3]);
});
