// Every key the page answers, decided in one place from what is open. Layers are checked from the top of the screen
// down and the first that wants a key takes it, so a key answers exactly one thing: Escape closing a menu can never
// also pass priority, which is what happened when each part of the page listened for keys on its own.

import { cardMenu, oldestRequest, stackPick, type Model } from './model';
import type { UiState } from './ui';

export type KeyCommand =
  | 'closeOptions' | 'closeGameMenu' | 'closeVolume' | 'closeStackMenu' | 'closeStops' | 'closePicker' | 'declineHostChoice'
  | 'ok' | 'cancel' | 'passNow' | 'stopAutoPass' | 'endTurn' | 'undo' | 'nextFace' | 'cardText' | 'startMatch'
  | 'closeCardMenu' | `pickCardMenu${Digit}` | 'closeReveal' | 'editorUndo' | 'closeImporter' | 'closeBrowse';

type Digit = '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9';

/** The keys a player can choose, each stored as keyCommand reads it: a letter in lower case, or ' ' for Space. */
export interface KeyBindings {
  ok: string;
  endTurn: string;
  undo: string;
  nextFace: string;
  cardText: string;
}

export const DEFAULT_KEYS: KeyBindings = { ok: ' ', endTurn: 'e', undo: 'z', nextFace: 'f', cardText: 't' };

// Escape closes and cancels everywhere, Enter confirms and starts a match, and the numbers pick from a card's menu
const KEPT = new Set(['Escape', 'Enter', 'Tab', '1', '2', '3', '4', '5', '6', '7', '8', '9']);

/** Gives an action a new key. An action that had the key takes the old one, so no key answers two actions. */
export function rebind(keys: KeyBindings, action: keyof KeyBindings, key: string): KeyBindings {
  const chosen = key.length === 1 ? key.toLowerCase() : key;
  if (KEPT.has(chosen)) return keys;
  const next = { ...keys, [action]: chosen };
  for (const other of Object.keys(keys) as (keyof KeyBindings)[]) {
    if (other !== action && keys[other] === chosen) next[other] = keys[action];
  }
  return next;
}

/** A key as a player reads it. */
export function keyName(key: string): string {
  return key === ' ' ? 'Space' : key.length === 1 ? key.toUpperCase() : key;
}

export interface KeyPress {
  key: string;
  /** Typed into a field: only Escape, to close what the field sits in, is the page's. */
  typing: boolean;
  /** With Ctrl, Alt or Meta held, which belong to the browser. */
  modified: boolean;
}

export function keyCommand(press: KeyPress, model: Model, ui: UiState, passing = false, keys = DEFAULT_KEYS): KeyCommand | null {
  if (press.modified) {
    // The one held key the page takes: undo in the deck editor, which the browser would otherwise spend on nothing
    const editing = !!model.editor && !model.inMatch && !ui.importer;
    return editing && !press.typing && press.key.toLowerCase() === 'z' ? 'editorUndo' : null;
  }
  const key = press.key.length === 1 ? press.key.toLowerCase() : press.key;
  const escape = key === 'Escape';
  const ok = key === keys.ok || key === 'Enter';
  // A question the host is waiting on sits over every page, and Escape declines it
  if (model.hostChoice) {
    return escape ? 'declineHostChoice' : null;
  }
  if (!model.inMatch) {
    if (ui.importer) {
      return escape ? 'closeImporter' : null;
    }
    if (ui.browse && !model.editor) {
      return escape ? 'closeBrowse' : null;
    }
    if (model.inLobby && ui.picker) {
      return escape ? 'closePicker' : null;
    }
    const table = model.lobby;
    return !press.typing && model.inLobby && key === 'Enter' && table?.host && table.canStart ? 'startMatch' : null;
  }
  if (ui.optionsOpen) {
    return escape ? 'closeOptions' : null;
  }
  if (ui.gameMenu) {
    return escape ? 'closeGameMenu' : null;
  }
  if (ui.volumeOpen) {
    return escape ? 'closeVolume' : null;
  }
  if (ui.stackMenuAt) {
    return escape ? 'closeStackMenu' : null;
  }
  if (ui.stopsOpen) {
    return escape ? 'closeStops' : null;
  }
  if (press.typing) {
    return null;
  }
  // Turning the card under the pointer, or reading it, answers nothing, so it works over a question as well
  if (key === keys.nextFace) {
    return 'nextFace';
  }
  if (key === keys.cardText) {
    return 'cardText';
  }
  // A pass on its way: its button takes the keys the prompt's would
  if (passing) {
    if (ok) return 'passNow';
    return escape ? 'stopAutoPass' : null;
  }
  // A card's menu of abilities: Escape closes it, and its items are numbered as desktop's are
  const menu = cardMenu(model);
  if (menu) {
    if (escape) return 'closeCardMenu';
    return /^[1-9]$/.test(key) && Number(key) <= menu.options.length ? `pickCardMenu${key as Digit}` : null;
  }
  const request = oldestRequest(model);
  if (request?.kind === 'reveal' && (escape || ok)) {
    return 'closeReveal';
  }
  // A question in a dialog is answered there, and the prompt under it keeps its buttons to itself
  if ((oldestRequest(model) && !stackPick(model)) || model.spectating) {
    return null;
  }
  const prompt = model.prompt;
  if (ok && prompt?.ok?.enabled) return 'ok';
  if (escape && prompt?.cancel?.enabled) return 'cancel';
  if (key === keys.endTurn) return 'endTurn';
  if (key === keys.undo) return 'undo';
  return null;
}
