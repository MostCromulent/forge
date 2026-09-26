import { describe, expect, it } from 'vitest';
import { matchSentence, randomPool } from '../../main/ts/lobby';
import type { DeckSummary, LobbyTable } from '../../main/ts/protocol';

const deck = (name: string, more: Partial<DeckSummary> = {}): DeckSummary =>
  ({ key: name, name, source: 'precons', colors: '', ...more });

describe("a computer seat's random deck", () => {
  // Fails if a deck the lobby would refuse, such as one outside the card pool, can be dealt to a computer seat
  it('comes only from decks with no problem, generators included', () => {
    const pool = randomPool([
      deck('Atog Pile', { problem: 'Not legal in Pauper: 1 card. Atog.' }),
      deck('Bears'),
      deck('Random two colours', { generated: true, source: 'generated' }),
    ]);
    expect(pool.map(d => d.name).sort()).toEqual(['Bears', 'Random two colours']);
  });
});

describe('the sentence under the lobby header', () => {
  const table = (more: Partial<LobbyTable> = {}): LobbyTable => ({
    host: true, mySeat: 0, shareable: false, format: 'Constructed', maxSeats: 4, seats: [], problems: [], canStart: false,
    cardPools: [], casualVariants: [], variantsOn: [],
    formats: [{ id: 'Constructed', name: 'Constructed', group: 'Constructed', desc: 'Each player brings a deck of 60 or more cards.', facts: [], play: '' }],
    ...more,
  });

  // Fails if the sentence names Constructed but drops the format that limits its cards
  it("names Constructed with its format, then says what Constructed is", () => {
    expect(matchSentence(table({ cardPool: 'Pauper' })))
      .toEqual({ title: 'Constructed · Pauper', text: 'Each player brings a deck of 60 or more cards.' });
    expect(matchSentence(table()).title).toBe('Constructed · any cards');
  });

  // Fails if the caret menu already shows "Any cards" under another format, so choosing it sends nothing
});
