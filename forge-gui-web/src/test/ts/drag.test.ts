import { describe, expect, it } from 'vitest';
import { verdictFor } from '../../main/ts/drag';
import type { EditorCard, EditorState } from '../../main/ts/protocol';

const card = (name: string, count: number): EditorCard => ({ name, count, image: '', cost: '', mv: 1, colors: 'R', printings: 1, split: [] });

const deck = (format: string, main: EditorCard[], commanders: EditorCard[] = []): EditorState => ({
  name: 'Deck', check: format, format, unrestricted: false, target: 'storage', commanders, commanderWanted: false,
  identity: '', main: [{ heading: 'Instants', cards: main }], sideboard: [], lands: [],
  stats: { main: 0, sideboard: 0, lands: 0, averageMana: 0, curve: [], types: [] }, problemCount: 0, canUndo: false, onSeat: false,
  limited: false, landSets: [],
});

describe('what releasing a dragged card does', () => {
  // Fails if a fifth copy can be dropped into a Constructed deck
  it('refuses a copy past the limit', () => {
    const v = verdictFor({ name: 'Lightning Bolt', from: 'catalogue', count: 1 }, 'Main', deck('Constructed', [card('Lightning Bolt', 4)]), true);
    expect(v.accepts).toBe(false);
    expect(v.verb).toContain('4 of 4');
  });

  // Fails if dropping a deck card back on the catalogue does not remove it
  it('removes a deck card dropped on the catalogue', () => {
    const v = verdictFor({ name: 'Shock', from: 'Main', count: 1 }, 'catalogue', deck('Constructed', [card('Shock', 2)]), true);
    expect(v).toEqual({ zone: 'catalogue', accepts: true, verb: 'remove 1 from Main' });
  });

  // Fails if a card that can't lead is accepted into the command zone
  it('refuses a commander the caller says can\'t be one', () => {
    const v = verdictFor({ name: 'Shock', from: 'catalogue', count: 1 }, 'Commander', deck('Commander', []), false);
    expect(v.accepts).toBe(false);
  });

  // Fails if a catalogue card dropped on the sideboard is described as anything but an add there
  it('adds a catalogue card to the sideboard', () => {
    expect(verdictFor({ name: 'Duress', from: 'catalogue', count: 1 }, 'Sideboard', deck('Constructed', []), true).verb)
      .toBe('add 1 to Sideboard');
  });

  // Fails if moving within the deck is described as an add, which would ask the copy limit about a card already counted
  it('moves a main-deck card to the sideboard', () => {
    const v = verdictFor({ name: 'Shock', from: 'Main', count: 1 }, 'Sideboard', deck('Constructed', [card('Shock', 4)]), true);
    expect(v).toEqual({ zone: 'Sideboard', accepts: true, verb: 'move 1 to Sideboard' });
  });

  // Fails if a new commander over an old one is not called a replacement
  it('replaces a commander', () => {
    const v = verdictFor({ name: 'Savra', from: 'catalogue', count: 1 }, 'Commander', deck('Commander', [], [card('Meren', 1)]), true);
    expect(v.verb).toBe('replace the commander');
  });
});
