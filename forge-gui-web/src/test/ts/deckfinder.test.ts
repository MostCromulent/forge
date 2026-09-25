import { describe, expect, it } from 'vitest';
import { FINDER_DEFAULTS, matchingDecks, sourceCounts, type DeckFilter } from '../../main/ts/deckfinder';
import type { DeckSummary } from '../../main/ts/protocol';

const deck = (name: string, more: Partial<DeckSummary> = {}): DeckSummary =>
  ({ key: name, name, source: 'precons', colors: '', main: 60, legalIn: ['Standard'], ...more });
const all: DeckFilter = { query: '', source: 'all', colours: new Set(), cardFormat: 'any', legalOnly: false, sort: 'name' };
const names = (list: DeckSummary[]) => list.map(d => d.name);

describe('narrowing the deck list', () => {
  const decks = [
    deck('Zombies', { colors: 'B' }),
    deck('Elves', { colors: 'G', source: 'constructed', main: 62 }),
    deck('Boros Blitz', { colors: 'RW', problem: 'Not enough cards', legalIn: [] }),
    deck('Random deck', { generated: true, main: undefined, legalIn: undefined }),
  ];

  it('shows every deck by name when nothing narrows it', () => {
    expect(names(matchingDecks(decks, all))).toEqual(['Boros Blitz', 'Elves', 'Random deck', 'Zombies']);
  });

  it('matches part of a name, a source, and any of the colours ticked', () => {
    expect(names(matchingDecks(decks, { ...all, query: 'bli' }))).toEqual(['Boros Blitz']);
    expect(names(matchingDecks(decks, { ...all, source: 'constructed' }))).toEqual(['Elves']);
    expect(names(matchingDecks(decks, { ...all, colours: new Set(['G', 'W']) }))).toEqual(['Boros Blitz', 'Elves']);
  });

  // A generator builds its deck when the game starts, so there is nothing yet to rule out
  it('keeps a generated deck when narrowing to legal decks or a format', () => {
    expect(names(matchingDecks(decks, { ...all, legalOnly: true }))).toEqual(['Elves', 'Random deck', 'Zombies']);
    expect(names(matchingDecks(decks, { ...all, cardFormat: 'Standard' }))).toEqual(['Elves', 'Random deck', 'Zombies']);
  });

  it('treats every net category as one source, and each category on its own', () => {
    const net = [deck('Burn', { source: 'net Modern' }), deck('Affinity', { source: 'net Legacy' }), deck('Zombies')];
    expect(names(matchingDecks(net, { ...all, source: 'net' }))).toEqual(['Affinity', 'Burn']);
    expect(names(matchingDecks(net, { ...all, source: 'net Modern' }))).toEqual(['Burn']);
  });

  it('sorts by size largest first, and puts legal decks first, each falling back to the name', () => {
    expect(names(matchingDecks(decks, { ...all, sort: 'size' }))).toEqual(['Elves', 'Boros Blitz', 'Zombies', 'Random deck']);
    expect(names(matchingDecks(decks, { ...all, sort: 'legal' }))).toEqual(['Elves', 'Random deck', 'Zombies', 'Boros Blitz']);
  });

  // Fails if the chosen sort still orders the list while a name is being searched
  it('orders by best match while a name is searched, whatever the sort', () => {
    expect(names(matchingDecks(decks, { ...all, sort: 'legal', query: 'E' }))).toEqual(['Elves', 'Zombies', 'Random deck']);
  });
});

describe('opening the finder', () => {
  // Fails if the finder opens on decks the lobby would refuse
  it('hides illegal decks until asked', () => {
    expect(FINDER_DEFAULTS.legalOnly).toBe(true);
    const shown = matchingDecks([deck('Legal'), deck('Illegal', { problem: 'Not legal in Pauper: 1 card. Atog.' })],
      { ...FINDER_DEFAULTS, colours: new Set() });
    expect(names(shown)).toEqual(['Legal']);
  });
});

describe("the finder's sources", () => {
  // Fails if decks on this device or loaded from links are folded into another source, where nobody would look for them
  it("counts a guest's own decks and linked decks as sources of their own", () => {
    const counts = sourceCounts([deck('Mine', { source: 'device' }), deck('Link', { source: 'linked' }),
      deck('Precon', { source: 'precons', readOnly: true }), deck('Net', { source: 'net Modern' })]);
    expect(counts.get('all')).toBe(4);
    expect(counts.get('device')).toBe(1);
    expect(counts.get('linked')).toBe(1);
    expect(counts.get('precons')).toBe(1);
    expect(counts.get('net')).toBe(1);
  });
});
