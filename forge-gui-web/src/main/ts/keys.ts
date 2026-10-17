// The page's keys are decided here, top layer first, so a key answers exactly one thing. Escape is the exception: a Preact dialog or menu closes on it by itself

import { cardMenu, cardPrompt, oldestRequest, stackPick, type Model } from './model';
import type { UiState } from './ui';
import { t } from './text';

export type KeyCommand =
  | 'closeOptions' | 'closeConcede' | 'closeGameMenu' | 'closeVolume' | 'closeStackMenu' | 'closeStops' | 'closeZones' | 'closePicker' | 'declineHostChoice'
  | 'ok' | 'cancel' | 'nextFace' | 'cardText' | 'startMatch'
  | 'closeCardMenu' | `pickCardMenu${Digit}` | 'closeReveal' | 'confirmRequest' | 'foldRequest' | 'unfoldRequest' | 'editorUndo' | 'closeImporter' | 'closeBrowse'
  | ShortcutCommand;

/** What each of Forge's shortcut preferences does on the web. Alpha strike is the prompt's own button, so it is answered with the prompt's keys. */
const SHORTCUT_COMMANDS = {
  SHORTCUT_SHOWSTACK: 'showStack', SHORTCUT_SHOWCONSOLE: 'showLog', SHORTCUT_SHOWDEV: 'showDev', SHORTCUT_UNDO: 'undo',
  SHORTCUT_CONCEDE: 'concede', SHORTCUT_ENDTURN: 'endTurn', SHORTCUT_AUTOYIELD_ALWAYS_YES: 'alwaysYes', SHORTCUT_AUTOYIELD_ALWAYS_NO: 'alwaysNo',
  SHORTCUT_YIELD_OPTIONS: 'yieldOptions', SHORTCUT_YIELD_AUTO_PASS: 'toggleAutoPass', SHORTCUT_MACRO_RECORD: 'macroRecord',
  SHORTCUT_MACRO_REPEAT_ACTIONS: 'macroRepeat', SHORTCUT_SHOWHOTKEYS: 'showShortcuts',
} as const;

type ShortcutCommand = typeof SHORTCUT_COMMANDS[keyof typeof SHORTCUT_COMMANDS];

/** The shortcut preferences the web has an action for. */
export const SHORTCUT_PREFS = new Set([...Object.keys(SHORTCUT_COMMANDS), 'SHORTCUT_ALPHASTRIKE']);

type Digit = '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9';

/** The keys a player can choose, each stored as keyCommand reads it: a letter in lower case, or ' ' for Space. */
export interface KeyBindings {
  ok: string;
  nextFace: string;
  cardText: string;
}

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

/**
 * A desktop shortcut, read from its preference as desktop reads it: Java key codes separated by spaces, where 16 is
 * Shift and 17 is Ctrl and the last other code is the key. Desktop ignores Alt. Null when no key is set.
 */
interface Shortcut {
  code: number;
  ctrl: boolean;
  shift: boolean;
}

export function parseShortcut(codes: string): Shortcut | null {
  const list = codes.split(' ').filter(Boolean).map(Number);
  const code = list.filter(c => c !== 16 && c !== 17).pop();
  return code ? { code, ctrl: list.includes(17), shift: list.includes(16) } : null;
}

/** Whether two shortcuts are the same keys, whatever order their codes were stored in. */
export function sameShortcut(a: string, b: string): boolean {
  const x = parseShortcut(a);
  const y = parseShortcut(b);
  return !!x && !!y && x.code === y.code && x.ctrl === y.ctrl && x.shift === y.shift;
}

/** The desktop codes a page key stands for: a letter, a digit or Space. Other keys have none. */
export function codesOfKey(key: string): string {
  if (key === ' ') return '32';
  return /^[a-z0-9]$/.test(key) ? String(key.toUpperCase().charCodeAt(0)) : '';
}

/** A shortcut as desktop names it, each key's name joined with "+". */
export function shortcutName(codes: string): string {
  return codes.split(' ').filter(Boolean).map(Number).map(javaKeyText).join('+');
}

const JAVA_KEY_NAMES: Record<number, string> = {
  8: 'Backspace', 9: 'Tab', 10: 'Enter', 16: 'Shift', 17: 'Ctrl', 18: 'Alt', 27: 'Escape', 32: 'Space',
  37: 'Left', 38: 'Up', 39: 'Right', 40: 'Down', 127: 'Delete',
};

/** A Java key code's name. Letters, digits and function keys share their codes with the browser's keyCode. */
function javaKeyText(code: number): string {
  if ((code >= 48 && code <= 57) || (code >= 65 && code <= 90)) return String.fromCharCode(code);
  if (code >= 112 && code <= 123) return `F${code - 111}`;
  return JAVA_KEY_NAMES[code] ?? `#${code}`;
}

/** A key pressed now, in the preferences' codes, for a shortcut being chosen. Alt and Meta are left out, as Forge ignores Alt. */
export function shortcutCodes(e: { keyCode: number; ctrlKey: boolean; shiftKey: boolean }): string {
  return [...(e.ctrlKey ? [17] : []), ...(e.shiftKey ? [16] : []), e.keyCode].join(' ');
}

interface KeyPress {
  key: string;
  /** The browser's keyCode, which is the Java key code for the keys desktop shortcuts use. */
  code: number;
  ctrl: boolean;
  shift: boolean;
  alt: boolean;
  meta: boolean;
  /** Typed into a field: only Escape, to close what the field sits in, is the page's. */
  typing: boolean;
}

/** Whether a press is a shortcut exactly, its modifiers included, as Swing matches a key stroke. */
function pressed(press: KeyPress, shortcut: Shortcut | null): boolean {
  return !!shortcut && press.code === shortcut.code && press.ctrl === shortcut.ctrl && press.shift === shortcut.shift && !press.alt && !press.meta;
}

/** keys are the page's own; shortcuts are Forge's shortcut preferences, which a match answers as desktop does. */
export function keyCommand(press: KeyPress, model: Model, ui: UiState, keys: KeyBindings, shortcuts: Record<string, string>): KeyCommand | null {
  const shortcut = model.screen === 'playing' ? Object.keys(shortcuts).find(pref => pressed(press, parseShortcut(shortcuts[pref]))) : undefined;
  const modified = press.ctrl || press.alt || press.meta;
  if (modified && !shortcut) {
    // The one other held key the page takes: undo in the deck editor, which the browser would otherwise spend on nothing
    const editing = !!model.editor && model.screen !== 'playing' && !ui.importer;
    return editing && !press.typing && press.key.toLowerCase() === 'z' ? 'editorUndo' : null;
  }
  // A held key is no player's chosen key, so it matches none of them on the way down
  const key = modified ? '' : press.key.length === 1 ? press.key.toLowerCase() : press.key;
  const escape = key === 'Escape';
  const ok = (!!keys.ok && key === keys.ok) || key === 'Enter';
  // A question the host is waiting on sits over every page, and Escape declines it
  if (model.hostChoice) {
    return escape ? 'declineHostChoice' : null;
  }
  // The concede question takes every key, and Escape keeps the game
  if (ui.concedeAsk) {
    return escape ? 'closeConcede' : null;
  }
  // The options open on any page, and the yield dialog they open sits over them
  if (ui.gameMenu === 'stops' || (model.screen === 'playing' && ui.gameMenu)) {
    return escape ? 'closeGameMenu' : null;
  }
  if (ui.optionsOpen) {
    return escape ? 'closeOptions' : null;
  }
  if (model.screen !== 'playing') {
    if (ui.importer) {
      return escape ? 'closeImporter' : null;
    }
    if (ui.browse && !model.editor) {
      return escape ? 'closeBrowse' : null;
    }
    if (model.screen === 'setup' && ui.picker) {
      return escape ? 'closePicker' : null;
    }
    const table = model.lobby;
    return !press.typing && model.screen === 'setup' && key === 'Enter' && table?.host && table.canStart
      // A deck above the table's bracket is started past only by Play, which asks first
      && !table.overBracket.length ? 'startMatch' : null;
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
  // A zone window the player opened is put away first, so the same Escape never also takes back their last action
  if (escape && ui.openZones.size) {
    return 'closeZones';
  }
  if (press.typing) {
    return null;
  }
  // Turning the card under the pointer, or reading it, answers nothing, so it works over a question as well
  if (keys.nextFace && key === keys.nextFace) {
    return 'nextFace';
  }
  if (keys.cardText && key === keys.cardText) {
    return 'cardText';
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
  // A question about cards is answered on the board: OK confirms what is shown, and Escape folds it away to read the board, never declining it
  if (request && cardPrompt(request)) {
    if (ui.requestFolded === request.id) return ok ? 'unfoldRequest' : null;
    return ok ? 'confirmRequest' : escape ? 'foldRequest' : null;
  }
  // A question in a dialog is answered there, and the prompt under it keeps its buttons to itself
  const pick = stackPick(model);
  if ((request && !pick) || model.spectating) {
    return null;
  }
  // A spell picked on the stack is declined from the prompt's Cancel, when it may be
  if (pick) {
    return escape && pick.min === 0 ? 'cancel' : null;
  }
  const prompt = model.prompt;
  if (shortcut === 'SHORTCUT_ALPHASTRIKE') return prompt?.cancel?.enabled && prompt.cancel.label === t('lblAlphaStrike') ? 'cancel' : null;
  if (ok && prompt?.ok?.enabled) return 'ok';
  // Escape takes something back, so it never sends every creature in to attack
  if (escape && prompt?.cancel?.enabled && prompt.cancel.label !== t('lblAlphaStrike')) return 'cancel';
  return shortcut && shortcut in SHORTCUT_COMMANDS ? SHORTCUT_COMMANDS[shortcut as keyof typeof SHORTCUT_COMMANDS] : null;
}
