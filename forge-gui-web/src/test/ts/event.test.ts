import { describe, expect, it } from 'vitest';
import { eventStage } from '../../main/ts/event';
import type { LimitedTable, LobbyTable, Seat } from '../../main/ts/protocol';

const seat = (type: string, more: Partial<Seat> = {}): Seat =>
  ({ type, mine: false, mayEdit: false, ready: false, avatar: 0, sleeve: 0, deckSize: 0, colors: '', sleeveOffset: 0, benched: false, ...more } as Seat);
const limited = (more: Partial<LimitedTable>): LimitedTable =>
  ({ kind: 'sealed', podSize: 0, timer: 0, eventDecksOnly: true, started: false, pastEvents: [], ...more } as LimitedTable);
const table = (seats: Seat[], lim: Partial<LimitedTable>): LobbyTable => ({
  host: true, mySeat: 0, shareable: true, format: 'Constructed', maxSeats: 8, gamesPerMatch: 3, seats, problems: [], canStart: false,
  illegalDecks: [], legalityEnforced: true, casualVariants: [], variantsOn: [], formats: [], limited: limited(lim),
});

describe('where a table\'s event has got to', () => {
  const decked = { deckName: 'Sealed', deckSize: 40 };
  // Fails if the rail says Play while someone who plays is still building, or waits on a seat sitting the match out
  it('reaches Play only once every seat that plays has a deck', () => {
    const out = { started: true, activeEventId: 'e1' };
    expect(eventStage(table([seat('REMOTE', { mine: true, ...decked }), seat('REMOTE')], out))).toBe(2);
    expect(eventStage(table([seat('REMOTE', { mine: true, ...decked }), seat('REMOTE', decked)], out))).toBe(3);
    expect(eventStage(table([seat('REMOTE', { mine: true, ...decked }), seat('REMOTE', decked), seat('REMOTE', { benched: true })], out))).toBe(3);
    expect(eventStage(table([seat('REMOTE', { mine: true, ...decked }), seat('OPEN')], out))).toBe(3);
  });
  it('is at Ready up until the event starts, and at the draft until the pools are out', () => {
    expect(eventStage(table([seat('REMOTE', { mine: true })], { product: 'Full' }))).toBe(0);
    expect(eventStage(table([seat('REMOTE', { mine: true })], { product: 'Full', started: true }))).toBe(1);
  });
});
