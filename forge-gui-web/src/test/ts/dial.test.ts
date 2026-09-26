import { describe, expect, it } from 'vitest';
import { nextFrom } from '../../main/ts/dial';

describe('the pack dial', () => {
  // Fails if "next pack from" names the wrong neighbour for the pass direction
  it('names the seat your next pack comes from', () => {
    expect(nextFrom([0, 1, 1, 1, 1, 1, 3, 1], 1)).toBe(7);
    expect(nextFrom([0, 1, 1, 1, 1, 1, 3, 1], -1)).toBe(1);
    expect(nextFrom([0, 0, 0, 2, 0, 0, 0, 0], 1)).toBe(3);
    expect(nextFrom([0, 0, 0, 0], 1)).toBeNull();
  });
});
