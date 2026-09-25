import { describe, expect, it } from 'vitest';
import { deckText, drawHand, regroup } from '../../main/ts/decklist';
import type { EditorCard, EditorState } from '../../main/ts/protocol';

const card = (name: string, count: number, mv: number, colors: string): EditorCard =>
  ({ name, count, image: '', cost: '', mv, colors, printings: 1, split: [] });

const state = (more: Partial<EditorState> = {}): EditorState => ({
  name: 'Meren deck', check: 'Commander', format: 'Commander', unrestricted: false, target: 'storage',
  commanders: [card('Meren of Clan Nel Toth', 1, 4, 'BG')], commanderWanted: false, identity: 'BG',
  main: [
    { heading: 'Creatures', cards: [card('Viscera Seer', 1, 1, 'B'), card('Grave Titan', 1, 6, 'B')] },
    { heading: 'Lands', cards: [card('Swamp', 11, 0, 'C'), card('Forest', 9, 0, 'C')] },
  ],
  sideboard: [card('Duress', 1, 1, 'B')], lands: [],
  stats: { main: 22, sideboard: 1, lands: 20, averageMana: 3.5, curve: [], types: [] },
  problemCount: 0, canUndo: false, onSeat: false, ...more,
});

describe('the deck list', () => {
  // Fails if grouping by mana value keeps the type headings, or loses a card
  it('regroups the main deck by mana value, lands apart', () => {
    const groups = regroup(state(), 'mv');
    expect(groups.map(g => g.heading)).toEqual(['1', '6', 'Lands']);
    expect(groups.flatMap(g => g.cards).length).toBe(4);
  });

  // Fails if grouping by colour mixes colourless and multicoloured cards
  it('regroups by colour, multicoloured together', () => {
    const s = state({ main: [{ heading: 'Creatures', cards: [card('Putrefy', 1, 3, 'BG'), card('Duress', 1, 1, 'B')] }] });
    expect(regroup(s, 'colour').map(g => g.heading)).toEqual(['Black', 'Multicolour']);
  });

  // Fails if the text another site or desktop Forge reads back loses the commander or the sideboard
  it('writes the deck as a list with its sections', () => {
    expect(deckText(state())).toBe('Commander\n1 Meren of Clan Nel Toth\n\nDeck\n1 Viscera Seer\n1 Grave Titan\n11 Swamp\n9 Forest\n\nSideboard\n1 Duress\n');
  });

  // Fails if the hand draws more than seven, or a card the deck does not hold that many of
  it('draws seven from the main deck, counting copies', () => {
    let seed = 1;
    const hand = drawHand(state(), 7, () => (seed = (seed * 16807) % 2147483647) / 2147483647);
    expect(hand.length).toBe(7);
    expect(hand.filter(c => c.name === 'Viscera Seer').length).toBeLessThanOrEqual(1);
  });
});
