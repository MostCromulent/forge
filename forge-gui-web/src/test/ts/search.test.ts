import { describe, expect, it } from 'vitest';
import { rankByName } from '../../main/ts/search';

const NAMES = ['Golgari Grave-Troll', 'Graveborn Muse', 'Grave Titan', 'Sol Ring', 'Gravedigger', 'Grave Pact', 'Lim-Dûl the Necromancer'];
const ranked = (typed: string) => rankByName(NAMES, typed).map(i => NAMES[i]);

describe('searching a list by name', () => {
  // Fails if names containing the text are mixed in with names starting with it, or the shortest start is not first
  it('puts names starting with the text first, shortest first, then names containing it', () => {
    expect(ranked('grave')).toEqual(['Grave Pact', 'Grave Titan', 'Gravedigger', 'Graveborn Muse', 'Golgari Grave-Troll']);
  });

  // Fails if case, accents or punctuation in either the name or the typed text stop a match
  it('ignores case, accents and punctuation', () => {
    expect(ranked('LIMDUL')).toEqual(['Lim-Dûl the Necromancer']);
    expect(ranked('grave-t')).toEqual(['Golgari Grave-Troll']);
  });

  // Fails if an empty search reorders or drops anything
  it('keeps every name in its order when nothing is typed', () => {
    expect(ranked('')).toEqual(NAMES);
  });
});
