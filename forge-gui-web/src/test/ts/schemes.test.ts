import { describe, expect, it } from 'vitest';
import { newSchemes } from '../../main/ts/schemes';

describe('which schemes are revealed', () => {
  // Fails if a reload reveals every ongoing scheme again, or a scheme newly set in motion is missed
  it('only those new since the last look, and none on the first', () => {
    expect(newSchemes(null, [4])).toEqual([]);
    expect(newSchemes(new Set([4]), [4, 9])).toEqual([9]);
    expect(newSchemes(new Set([4, 9]), [9])).toEqual([]);
  });
});
