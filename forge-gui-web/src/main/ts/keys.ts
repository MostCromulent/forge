// Every key the page answers, decided in one place from what is open. Layers are checked from the top of the screen
// down and the first that wants a key takes it, so a key answers exactly one thing: Escape closing a menu can never
// also pass priority, which is what happened when each part of the page listened for keys on its own.

import { oldestRequest, stackPick, type Model } from './model';
import type { UiState } from './ui';

export type KeyCommand =
  | 'closeOptions' | 'closeStackMenu' | 'closeStops' | 'closePicker' | 'declineHostChoice'
  | 'ok' | 'cancel' | 'endTurn' | 'undo' | 'nextFace' | 'startMatch';

export interface KeyPress {
  key: string;
  /** Typed into a field: only Escape, to close what the field sits in, is the page's. */
  typing: boolean;
  /** With Ctrl, Alt or Meta held, which belong to the browser. */
  modified: boolean;
}

export function keyCommand(press: KeyPress, model: Model, ui: UiState): KeyCommand | null {
  if (press.modified) {
    return null;
  }
  const key = press.key.length === 1 ? press.key.toLowerCase() : press.key;
  const escape = key === 'Escape';
  // A question the host is waiting on sits over every page, and Escape declines it
  if (model.hostChoice) {
    return escape ? 'declineHostChoice' : null;
  }
  if (!model.inMatch) {
    if (model.inLobby && ui.picker) {
      return escape ? 'closePicker' : null;
    }
    const table = model.lobby;
    return !press.typing && model.inLobby && key === 'Enter' && table?.host && table.canStart ? 'startMatch' : null;
  }
  if (ui.optionsOpen) {
    return escape ? 'closeOptions' : null;
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
  // Turning the card under the pointer answers nothing, so it works over a question as well
  if (key === 'f') {
    return 'nextFace';
  }
  // A question in a dialog is answered there, and the prompt under it keeps its buttons to itself
  if ((oldestRequest(model) && !stackPick(model)) || model.spectating) {
    return null;
  }
  const prompt = model.prompt;
  if ((key === ' ' || key === 'Enter') && prompt?.ok?.enabled) return 'ok';
  if (escape && prompt?.cancel?.enabled) return 'cancel';
  if (key === 'e') return 'endTurn';
  if (key === 'z') return 'undo';
  return null;
}
