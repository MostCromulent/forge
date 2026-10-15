// The editor's deck, read in ways the server does not send it: regrouped, written out as a list, and dealt as a sample hand

import type { EditorCard, EditorGroup, EditorState } from '../protocol';
import { COLOUR_NAMES } from '../symbols';
import { t, type TextKey } from '../text';

export type GroupBy = 'type' | 'mv' | 'colour';

// Headings by colour, in this order; the letters stand for themselves, M for more than one colour and C for none
const COLOUR_ORDER = ['W', 'U', 'B', 'R', 'G', 'M', 'C'];
const COLOUR_HEADINGS: Record<string, TextKey> = { ...COLOUR_NAMES, M: 'lblMulticolor' };

/** The card types cards are grouped and filtered by, in the order a card of two types is placed: its type-line word, the server's group id, and the heading's key. */
export const CARD_TYPES: [type: string, group: string, heading: TextKey][] = [['Creature', 'Creatures', 'lblCreatures'],
  ['Planeswalker', 'Planeswalkers', 'lblPlaneswalkers'], ['Instant', 'Instants', 'lblInstants'], ['Sorcery', 'Sorceries', 'lblSorceries'],
  ['Artifact', 'Artifacts', 'lblArtifacts'], ['Enchantment', 'Enchantments', 'lblEnchantments'], ['Battle', 'Battles', 'lblBattles'],
  ['Land', 'Lands', 'lblLands']];
const TYPE_HEADINGS: Record<string, TextKey> = Object.fromEntries(CARD_TYPES.map(([, group, heading]) => [group, heading]));

/** A group's heading as shown: the server names the card types in English, as ids. */
export const headingText = (heading: string): string => (TYPE_HEADINGS[heading] ? t(TYPE_HEADINGS[heading]) : heading);

/** The main deck under other headings. Lands keep a heading of their own whichever way the rest are grouped. */
export function regroup(state: EditorState, by: GroupBy): EditorGroup[] {
  if (by === 'type') return state.main;
  const lands = state.main.find(g => g.heading === 'Lands');
  const spells = state.main.filter(g => g !== lands).flatMap(g => g.cards);
  const groups = new Map<string, EditorCard[]>();
  for (const card of spells) {
    const heading = by === 'mv' ? mvGroup(card.mv) : colourLetter(card.colors);
    groups.set(heading, [...(groups.get(heading) ?? []), card]);
  }
  const order = by === 'mv'
    ? [...groups.keys()].sort((a, b) => parseInt(a, 10) - parseInt(b, 10))
    : COLOUR_ORDER.filter(h => groups.has(h));
  const out = order.map(key => ({ heading: by === 'mv' ? key : t(COLOUR_HEADINGS[key]), cards: groups.get(key) ?? [] }));
  return lands ? [...out, lands] : out;
}

/** The heading of a mana value's group: the value itself, with everything from seven up together. */
export const mvGroup = (mv: number): string => (mv >= 7 ? '7+' : String(mv));

function colourLetter(colors: string): string {
  if (colors.length > 1) return 'M';
  return colors.length === 1 && 'WUBRG'.includes(colors) ? colors : 'C';
}

/** The heading of a card's colour group: its colour, Multicolor or Colorless. */
export const colourHeading = (colors: string): string => t(COLOUR_HEADINGS[colourLetter(colors)]);

/** The deck as a list other sites and desktop Forge read: a heading per section, then a count and a name per line. */
export function deckText(state: EditorState): string {
  const sections: [string, EditorCard[]][] = [
    ['Commander', state.commanders],
    ['Deck', state.main.flatMap(g => g.cards)],
    ['Sideboard', state.sideboard],
  ];
  return sections.filter(([, cards]) => cards.length)
    .map(([title, cards]) => `${title}\n${cards.map(c => `${c.count} ${c.name}`).join('\n')}\n`)
    .join('\n');
}

/** A hand from a shuffle of the main deck, each copy a card of its own. */
export function drawHand(state: EditorState, size: number, random: () => number = Math.random): EditorCard[] {
  const library = state.main.flatMap(g => g.cards).flatMap(c => Array.from({ length: c.count }, () => c));
  for (let i = library.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [library[i], library[j]] = [library[j], library[i]];
  }
  return library.slice(0, size);
}
