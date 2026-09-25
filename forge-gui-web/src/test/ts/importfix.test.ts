import { describe, expect, it } from 'vitest';
import { leaveOut, makeCommander, useName } from '../../main/ts/importfix';
import { startingCheck } from '../../main/ts/importer';
import { createModel } from '../../main/ts/model';

describe('fixes that edit the pasted list', () => {
  // Fails if the fix loses the count, the set code or the collector number around the name
  it('replaces a misspelt name, keeping what is around it', () => {
    expect(useName('2 Nihil Spelbomb [MBS] 12', 0, 'Nihil Spellbomb')).toBe('2 Nihil Spellbomb [MBS] 12');
    expect(useName('a\n1 Grave Pakt (STH) 1\nc', 1, 'Grave Pact')).toBe('a\n1 Grave Pact (STH) 1\nc');
  });

  // Fails if leaving a card out deletes the line, which would shift every line number the problems refer to
  it('turns a line into a comment', () => {
    expect(leaveOut('a\nb\nc', 1)).toBe('a\n// b\nc');
  });

  // Fails if the chosen commander is not moved under a Commander heading at the top
  it('moves a commander under a new heading', () => {
    expect(makeCommander('Deck\n1 Meren of Clan Nel Toth\n1 Sol Ring', 'Meren of Clan Nel Toth'))
      .toBe('Commander\n1 Meren of Clan Nel Toth\nDeck\n1 Sol Ring');
  });

  // Fails if a second Commander heading is added when the list has one
  it('uses a Commander heading the list already has', () => {
    expect(makeCommander('Commander\n1 Tymna the Weaver\nDeck\n1 Thrasios, Triton Hero\n1 Sol Ring', 'Thrasios, Triton Hero'))
      .toBe('Commander\n1 Tymna the Weaver\n1 Thrasios, Triton Hero\nDeck\n1 Sol Ring');
  });
});

describe('where the importer\'s check starts', () => {
  // Fails if an import from the start page ignores the format the finder is listing, which decides where the deck is saved
  it('starts from the start page finder\'s format', () => {
    expect(startingCheck(createModel(), 'start', 'Commander')).toBe('Commander|');
    expect(startingCheck(createModel(), 'start')).toBe('Constructed|');
  });
});
