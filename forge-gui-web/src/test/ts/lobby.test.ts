import { describe, expect, it } from 'vitest';
import { randomPool } from '../../main/ts/lobby';
import type { DeckSummary } from '../../main/ts/protocol';

const deck = (name: string, more: Partial<DeckSummary> = {}): DeckSummary =>
  ({ key: name, name, source: 'precons', colors: '', ...more });

describe("a computer seat's random deck", () => {
  // Fails if a deck the lobby would refuse, such as one outside the Legality, can be dealt to a computer seat
  it('comes only from decks with no problem, generators included', () => {
    const pool = randomPool([
      deck('Atog Pile', { problem: 'Not legal in Pauper: 1 card. Atog.' }),
      deck('Bears'),
      deck('Random two colours', { generated: true, source: 'generated' }),
    ]);
    expect(pool.map(d => d.name).sort()).toEqual(['Bears', 'Random two colours']);
  });
});
