// The editor's deck, read in ways the server does not send it: grouped by mana value or colour, written out as a list,
// and dealt as a sample hand.

import type { EditorCard, EditorGroup, EditorState } from './protocol';

export type GroupBy = 'type' | 'mv' | 'colour';

const COLOUR_NAMES: Record<string, string> = { W: 'White', U: 'Blue', B: 'Black', R: 'Red', G: 'Green' };
const COLOUR_ORDER = ['White', 'Blue', 'Black', 'Red', 'Green', 'Multicolour', 'Colourless'];

/** The main deck under other headings. Lands keep a heading of their own whichever way the rest are grouped. */
export function regroup(state: EditorState, by: GroupBy): EditorGroup[] {
  if (by === 'type') return state.main;
  const lands = state.main.find(g => g.heading === 'Lands');
  const spells = state.main.filter(g => g !== lands).flatMap(g => g.cards);
  const groups = new Map<string, EditorCard[]>();
  for (const card of spells) {
    const heading = by === 'mv' ? (card.mv >= 7 ? '7+' : String(card.mv)) : colourHeading(card.colors);
    groups.set(heading, [...(groups.get(heading) ?? []), card]);
  }
  const order = by === 'mv'
    ? [...groups.keys()].sort((a, b) => parseInt(a, 10) - parseInt(b, 10))
    : COLOUR_ORDER.filter(h => groups.has(h));
  const out = order.map(heading => ({ heading, cards: groups.get(heading) ?? [] }));
  return lands ? [...out, lands] : out;
}

function colourHeading(colors: string): string {
  if (colors.length > 1) return 'Multicolour';
  return COLOUR_NAMES[colors] ?? 'Colourless';
}

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
