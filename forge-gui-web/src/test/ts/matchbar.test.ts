import { describe, expect, it } from 'vitest';
import { deckMark, fewestSeats, seatsLeaving } from '../../main/ts/matchbar';
import type { Format, LobbyTable, Seat } from '../../main/ts/protocol';

const seat = (type: string, more: Partial<Seat> = {}): Seat =>
  ({ type, mine: false, mayEdit: false, ready: false, avatar: 0, sleeve: 0, deckSize: 0, colors: '', sleeveOffset: 0, benched: false, ...more } as Seat);
const table = (seats: Seat[]): LobbyTable => ({
  host: true, mySeat: 0, shareable: true, format: 'Constructed', maxSeats: 4, gamesPerMatch: 3, seats, problems: [], canStart: false,
  cardPools: [], casualVariants: [], variantsOn: [], formats: [],
});
const format = (facts: string[]): Format => ({ id: 'x', name: 'x', group: 'g', desc: '', facts, play: '' });

describe('lowering the player count', () => {
  // Fails if the preview dims a seat the server would keep: it takes open seats first, then computers, from the end
  it('takes open seats, then computers, from the end', () => {
    const lobby = table([seat('REMOTE', { mine: true }), seat('AI'), seat('OPEN'), seat('AI')]);
    expect([...seatsLeaving(lobby, 3)]).toEqual([2]);
    expect([...seatsLeaving(lobby, 2)].sort()).toEqual([2, 3]);
  });

  // Fails if a count below the people seated can be chosen, which the server would refuse
  it('never goes below the people seated, or two', () => {
    expect(fewestSeats(table([seat('REMOTE', { mine: true }), seat('REMOTE'), seat('REMOTE'), seat('AI')]))).toBe(3);
    expect(fewestSeats(table([seat('REMOTE', { mine: true }), seat('AI')]))).toBe(2);
  });
});

describe("a game's mark in the Mode menu", () => {
  // Fails if the mark loses the plus that says a deck may be bigger, or shows a number for a game that deals its decks
  it('reads the deck size from the first fact', () => {
    expect(deckMark(format(['60+ cards', 'Life 20']))).toBe('60+');
    expect(deckMark(format(['100 cards, one of each']))).toBe('100');
    expect(deckMark(format(['No deck to build: 60 basic lands']))).toBe('–');
  });
});
