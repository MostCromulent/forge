// The editor's deck, read in ways the server does not send it: grouped by mana value or colour, written out as a list,
// and dealt as a sample hand.

import type { EditorCard, EditorGroup, EditorState } from './protocol';
import { t, type TextKey } from './text';

export type GroupBy = 'type' | 'mv' | 'colour';

// Headings by colour, in this order; the letters stand for themselves, M for more than one colour and C for none
const COLOUR_ORDER = ['W', 'U', 'B', 'R', 'G', 'M', 'C'];
const COLOUR_HEADINGS: Record<string, TextKey> = {
  W: 'lblWhite', U: 'lblBlue', B: 'lblBlack', R: 'lblRed', G: 'lblGreen', M: 'lblWebDeckListMulticolour', C: 'lblWebEditorColourless',
};

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
  const out = order.map(key => ({ heading: by === 'mv' ? key : t(COLOUR_HEADINGS[key]), cards: groups.get(key) ?? [] }));
  return lands ? [...out, lands] : out;
}

function colourHeading(colors: string): string {
  if (colors.length > 1) return 'M';
  return colors.length === 1 && 'WUBRG'.includes(colors) ? colors : 'C';
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
