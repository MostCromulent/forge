import { describe, expect, it } from 'vitest';
import { severalCap } from '../../main/ts/limited';

describe('several opponents at once', () => {
  // Fails if a four-player match is overfilled, or a small pod offers more opponents than it has decks
  it('is capped by the match and by the decks there are', () => {
    expect(severalCap(7)).toBe(3);
    expect(severalCap(2)).toBe(2);
    expect(severalCap(1)).toBe(1);
  });
});
