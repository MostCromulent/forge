import { describe, expect, it } from 'vitest';
import { cardTypes, graveyardTitle } from '../../main/ts/board';

describe('the card types in a graveyard', () => {
  // Fails if a subtype is counted as a type, or a card with two types counts only one, as delirium would not
  it('counts each core type once, from the words before the dash', () => {
    expect(cardTypes(['Basic Land - Forest', 'Artifact Creature - Golem', 'Instant', 'Creature - Bear'])).toEqual(['Artifact', 'Creature', 'Instant', 'Land']);
    expect(cardTypes(['Legendary Enchantment Creature — God', 'Kindred Sorcery - Elf'])).toEqual(['Creature', 'Enchantment', 'Kindred', 'Sorcery']);
    expect(cardTypes([])).toEqual([]);
  });
  it('says how many cards and types the tooltip names', () => {
    expect(graveyardTitle(0, [])).toBe('Graveyard: 0 cards');
    expect(graveyardTitle(5, ['Creature', 'Land'])).toBe('Graveyard: 5 cards, 2 card types (Creature, Land)');
    expect(graveyardTitle(1, ['Instant'])).toBe('Graveyard: 1 card, 1 card type (Instant)');
  });
});
