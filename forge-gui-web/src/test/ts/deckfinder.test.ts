import { describe, expect, it } from 'vitest';
import { matchingDecks, type DeckFilter } from '../../main/ts/deckfinder';
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

  it('sorts by size largest first, and puts legal decks first, each falling back to the name', () => {
    expect(names(matchingDecks(decks, { ...all, sort: 'size' }))).toEqual(['Elves', 'Boros Blitz', 'Zombies', 'Random deck']);
    expect(names(matchingDecks(decks, { ...all, sort: 'legal' }))).toEqual(['Elves', 'Random deck', 'Zombies', 'Boros Blitz']);
  });
});
