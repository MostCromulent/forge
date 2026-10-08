// The printing a player likes best for each card, kept in this browser: the catalogue shows it, and a card new to a deck is added in it

import type { Actions } from '../actions';
import type { DeckSection, EditorState } from '../protocol';
import { storeJson, storedJson } from '../storage';
import { changeUi } from '../ui';

const KEY = 'forge.printings';
let byName: Record<string, string> | null = null;

const all = (): Record<string, string> => (byName ??= storedJson<Record<string, string>>(KEY, {}));

/** Whether this deck takes any printing of any card, which a pool's deck or a campaign's does not. */
export const choosesPrintings = (state: EditorState): boolean => !state.limited && !state.collection;

/** The image key of the printing preferred for a card, or undefined when none was chosen. */
export const preferredPrinting = (name: string): string | undefined => all()[name];

/** Keeps a card's preferred printing, or forgets it when given null, and draws the page again since the catalogue shows it. */
export function preferPrinting(name: string, key: string | null): void {
  const next = { ...all() };
  if (key === null) delete next[name];
  else next[name] = key;
  byName = next;
  storeJson(KEY, next);
  changeUi(() => {});
}

/** Adds a card to the deck. One the deck does not hold yet arrives in its preferred printing; one it holds keeps the deck's printing. */
export function addCard(actions: Actions, state: EditorState, name: string, to: DeckSection, count = 1): void {
  const held = [...state.main.flatMap(g => g.cards), ...state.sideboard, ...state.commanders].some(card => card.name === name);
  const key = choosesPrintings(state) && !held && count === 1 ? preferredPrinting(name) : undefined;
  actions.edit(key ? { op: 'add', name, to, count, printings: [{ name: key, count: 1 }] } : { op: 'add', name, to, count });
}
