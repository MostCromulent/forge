import { afterEach, expect, test, vi } from 'vitest';
import { place, roll, rolling } from '../../main/ts/rogue/carousel';

afterEach(() => { vi.useRealTimers(); });

// Fails if a card beyond the middle of the ring is placed the long way round, which would carry it across the front
test('the ring takes the short way round', () => {
  expect(place(7, 0, 8)).toEqual({ o: -1, abs: 1, sign: -1 });
  expect(place(1, 7, 8)).toEqual({ o: 2, abs: 2, sign: 1 });
  expect(place(2, 2, 5)).toEqual({ o: 0, abs: 0, sign: 0 });
  expect(place(0, 3, 5)).toEqual({ o: 2, abs: 2, sign: 1 });
});

// Fails if Random stops on another commander than the one it chose, or stops after fewer than eight steps when the ring is small
test('random ends on the chosen commander after at least eight steps', () => {
  for (const [from, to, n] of [[1, 3, 8], [0, 7, 8], [2, 1, 3], [4, 0, 15]]) {
    const { steps, waits } = roll(from, to, n);
    expect(steps).toBeGreaterThanOrEqual(8);
    expect((from + steps) % n).toBe(to);
    expect(waits).toHaveLength(steps - 1);
  }
  const { waits } = roll(1, 3, 8);
  expect(waits.slice(0, 3)).toEqual([70, 70, 70]);
  expect(waits[waits.length - 1]).toBeCloseTo(70 * 1.32 ** 6);
});

// Fails if a second click lets the roll go on stepping, or ends it anywhere but on the chosen commander
test('a second click ends the roll at once', () => {
  vi.useFakeTimers();
  const shown: number[] = [];
  let ended = 0;
  const stop = rolling(1, 3, 8, i => shown.push(i), () => ended++);
  vi.advanceTimersByTime(150);
  expect(shown).toEqual([2, 3, 4]);
  stop();
  expect(shown.at(-1)).toBe(3);
  expect(ended).toBe(1);
  const before = shown.length;
  vi.advanceTimersByTime(10_000);
  expect(shown).toHaveLength(before);
  expect(ended).toBe(1);
});
