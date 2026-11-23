import { afterEach, expect, test, vi } from 'vitest';
import { roll, rolling } from '../../main/ts/rogue/carousel';

afterEach(() => { vi.useRealTimers(); });

// Fails if Random stops on another commander than the one it chose, or stops after fewer than eight steps when the ring is small
test('random ends on the chosen commander after at least eight steps', () => {
  for (const [from, to, n] of [[1, 3, 8], [0, 7, 8], [2, 1, 3], [4, 0, 15]]) {
    const { steps, waits } = roll(from, to, n);
    expect(steps).toBeGreaterThanOrEqual(8);
    expect((from + steps) % n).toBe(to);
    expect(waits).toHaveLength(steps - 1);
  }
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
