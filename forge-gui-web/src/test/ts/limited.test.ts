import { describe, expect, it } from 'vitest';
import { colourName, severalCap, shortDay } from '../../main/ts/limited';

describe('several opponents at once', () => {
  // Fails if a four-player match is overfilled, or a small pod offers more opponents than it has decks
  it('is capped by the match and by the decks there are', () => {
    expect(severalCap(7)).toBe(3);
    expect(severalCap(2)).toBe(2);
    expect(severalCap(1)).toBe(1);
  });
});

describe('an opponent\'s colours in words', () => {
  // Fails if a two-colour deck is not named by its pair, or a deck with no colours reads as blank
  it('names up to three colours and counts beyond that', () => {
    expect(colourName('WG')).toBe('White–Green');
    expect(colourName('U')).toBe('Blue');
    expect(colourName('')).toBe('Colourless');
    expect(colourName('WUBR')).toBe('Four colours');
    expect(colourName('WUBRG')).toBe('All five colours');
  });
});

describe('a saved pool\'s day', () => {
  // Fails if the server's date is read as UTC midnight and shown as the day before
  it('reads the local day the server sends', () => {
    expect(shortDay('2026-09-28')).toBe(new Date(2026, 8, 28).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }));
  });
});
