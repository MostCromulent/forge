import { describe, expect, it } from 'vitest';
import { countsInDeck, roomFor } from '../../main/ts/catalogue';
import type { CatalogueRow, EditorCard, EditorState } from '../../main/ts/protocol';

const card = (name: string, count: number): EditorCard => ({ name, count, image: '', cost: '', mv: 1, colors: 'G', printings: 1, split: [] });
const row = (name: string): CatalogueRow => ({ name, image: '', cost: '', mv: 1, colors: 'G', type: 'Creature', heading: 'Creatures', inDeck: 0 });

const pool = (main: EditorCard[], side: EditorCard[]): EditorState => ({
  name: 'Pool', check: 'Sealed', format: 'Sealed', unrestricted: false, target: 'storage', commanders: [], commanderWanted: false,
  identity: '', main: [{ heading: 'Creatures', cards: main }], sideboard: side, lands: [],
  stats: { main: 0, sideboard: 0, lands: 0, averageMana: 0, curve: [], types: [] }, problemCount: 0, canUndo: false, onSeat: false,
  limited: true, landSets: [],
});

describe('room for another copy', () => {
  // Fails if a pool's tile offers a copy the pool does not hold, as the four-copy limit would
  it('is what the pool has left in limited mode', () => {
    const state = pool([card('Llanowar Elves', 1)], [card('Llanowar Elves', 1)]);
    expect(roomFor(state, row('Llanowar Elves'), 1)).toBe(1);
    expect(roomFor(pool([card('Llanowar Elves', 2)], []), row('Llanowar Elves'), 2)).toBe(0);
  });

  // Fails if the pool itself is counted as cards in the deck, which would make every pool card look used
  it('does not count the pool as part of the deck', () => {
    const state = pool([card('Llanowar Elves', 1)], [card('Llanowar Elves', 3)]);
    expect(countsInDeck(state).get('Llanowar Elves')).toBe(1);
  });

  // Fails if limited mode leaks into a constructed deck's copy limit
  it('is the copy limit outside limited mode', () => {
    const state = { ...pool([], []), limited: false, format: 'Constructed', check: 'Constructed' };
    expect(roomFor(state, row('Llanowar Elves'), 1)).toBe(3);
  });
});
