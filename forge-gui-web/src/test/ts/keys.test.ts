import { beforeEach, describe, expect, it } from 'vitest';
import { DEFAULT_KEYS, keyCommand, rebind, type KeyPress } from '../../main/ts/keys';
import { createModel, type Model } from '../../main/ts/model';
import type { UiState } from '../../main/ts/ui';
import type { LobbyTable, Prompt, Request } from '../../main/ts/protocol';

const press = (key: string, typing = false): KeyPress => ({ key, typing, modified: false });

function freshUi(): UiState {
  return {
    openPiles: new Set(), openZones: new Set(), zonesMinimised: false, zoneSearch: '', zoneSort: 'order',
    stackCollapsed: false, hoveredStackItem: null, stackMenuAt: null,
    stopsOpen: false, optionsOpen: false, gameMenu: null, viewing: null, volumeOpen: false, renaming: false, picker: null, browse: null, importer: null, spectate: false, hover: null, faceIndex: 0, cardText: false,
    sidePanels: { log: true }, draftHidden: false,
  };
}

const prompt = (ok: boolean, cancel: boolean): Prompt => ({
  t: 'prompt', message: '', priority: ok, ok: { label: 'OK', enabled: ok }, cancel: { label: 'Cancel', enabled: cancel },
  focusOk: false, paying: false, selectable: [], selectableMin: 0, selectablePlayers: [], highlighted: [],
});

describe('keys in a match', () => {
  let model: Model;
  let ui: UiState;
  beforeEach(() => {
    model = createModel();
    model.inMatch = true;
    model.prompt = prompt(true, true);
    ui = freshUi();
  });

  it('answers the prompt: Space and Enter for OK, Escape for Cancel, E and Z for their buttons', () => {
    expect(keyCommand(press(' '), model, ui)).toBe('ok');
    expect(keyCommand(press('Enter'), model, ui)).toBe('ok');
    expect(keyCommand(press('Escape'), model, ui)).toBe('cancel');
    expect(keyCommand(press('E'), model, ui)).toBe('endTurn');
    expect(keyCommand(press('z'), model, ui)).toBe('undo');
  });

  // Closing a menu once also passed priority, when every part of the page answered keys on its own
  it('gives Escape to what is open over the board, and nothing else', () => {
    ui.stackMenuAt = { key: 1, x: 0, y: 0 };
    expect(keyCommand(press('Escape'), model, ui)).toBe('closeStackMenu');
    expect(keyCommand(press(' '), model, ui)).toBeNull();
    ui.stackMenuAt = null;
    ui.stopsOpen = true;
    expect(keyCommand(press('Escape'), model, ui)).toBe('closeStops');
    ui.optionsOpen = true;
    expect(keyCommand(press('Escape'), model, ui)).toBe('closeOptions');
  });

  it('gives a card\'s ability menu Escape and its numbers, and the prompt nothing', () => {
    model.requests.set(7, {
      t: 'request', id: 7, kind: 'choices', message: 'Abzan Charm', min: 0, max: 1, selected: [], atX: 400, atY: 300,
      options: [{ label: 'Cast Abzan Charm' }, { label: 'Cycling' }], default: [],
    } as unknown as Request);
    expect(keyCommand(press('Escape'), model, ui)).toBe('closeCardMenu');
    expect(keyCommand(press('2'), model, ui)).toBe('pickCardMenu2');
    expect(keyCommand(press('3'), model, ui)).toBeNull();
    expect(keyCommand(press(' '), model, ui)).toBeNull();
  });

  it('closes the volume control on Escape, without passing priority', () => {
    ui.volumeOpen = true;
    expect(keyCommand(press('Escape'), model, ui)).toBe('closeVolume');
    expect(keyCommand(press(' '), model, ui)).toBeNull();
  });

  it('gives the pass button\'s keys to a pass on its way', () => {
    expect(keyCommand(press(' '), model, ui, true)).toBe('passNow');
    expect(keyCommand(press('Escape'), model, ui, true)).toBe('stopAutoPass');
    expect(keyCommand(press('e'), model, ui, true)).toBeNull();
  });

  it('leaves a disabled button alone', () => {
    model.prompt = prompt(false, false);
    expect(keyCommand(press(' '), model, ui)).toBeNull();
    expect(keyCommand(press('Escape'), model, ui)).toBeNull();
  });

  it('keeps the prompt\'s keys from reaching it while a question is open in a dialog', () => {
    model.requests.set(1, { t: 'request', id: 1, kind: 'text', message: '', numeric: false } as Request);
    expect(keyCommand(press(' '), model, ui)).toBeNull();
    expect(keyCommand(press('f'), model, ui)).toBe('nextFace');
    expect(keyCommand(press('t'), model, ui)).toBe('cardText');
  });

  it('lets a field keep what is typed into it, except the Escape that closes what it sits in', () => {
    expect(keyCommand(press('z', true), model, ui)).toBeNull();
    expect(keyCommand(press('Escape', true), model, ui)).toBeNull();
    ui.optionsOpen = true;
    expect(keyCommand(press('Escape', true), model, ui)).toBe('closeOptions');
  });

  it('leaves keys held with Ctrl, Alt or Meta to the browser', () => {
    expect(keyCommand({ key: 'z', typing: false, modified: true }, model, ui)).toBeNull();
  });
});

describe('keys in match setup', () => {
  let model: Model;
  let ui: UiState;
  beforeEach(() => {
    model = createModel();
    model.inLobby = true;
    model.lobby = { host: true, canStart: true } as LobbyTable;
    ui = freshUi();
  });

  it('starts the match on Enter when the host can', () => {
    expect(keyCommand(press('Enter'), model, ui)).toBe('startMatch');
    model.lobby = { host: true, canStart: false } as LobbyTable;
    expect(keyCommand(press('Enter'), model, ui)).toBeNull();
    model.lobby = { host: false, canStart: true } as LobbyTable;
    expect(keyCommand(press('Enter'), model, ui)).toBeNull();
  });

  it('closes a picker on Escape, even from its search box, and does not start the match under it', () => {
    ui.picker = { kind: 'deck', seat: 0 };
    expect(keyCommand(press('Escape', true), model, ui)).toBe('closePicker');
    expect(keyCommand(press('Enter'), model, ui)).toBeNull();
  });

  it('declines a question the host is waiting on, over anything else', () => {
    model.hostChoice = { t: 'hostChoice', id: 3, kind: 'choose', min: 0, max: 1, options: ['a'] };
    ui.picker = { kind: 'deck', seat: 0 };
    expect(keyCommand(press('Escape'), model, ui)).toBe('declineHostChoice');
  });
});

describe('keys in the deck editor', () => {
  const editorState = { name: 'Burn' } as unknown as NonNullable<Model['editor']>;

  // Fails if Ctrl+Z in the editor goes to the browser rather than undoing the last change, or undoes while typing
  it('gives Ctrl+Z to the editor\'s undo, but not while typing', () => {
    const model = createModel();
    model.playerName = 'Alice';
    model.editor = editorState;
    expect(keyCommand({ key: 'z', typing: false, modified: true }, model, freshUi())).toBe('editorUndo');
    expect(keyCommand({ key: 'z', typing: true, modified: true }, model, freshUi())).toBeNull();
  });

  // Fails if Escape leaves the importer open, or closes the browse finder from under it
  it('closes the importer on Escape before the finder beneath it', () => {
    const model = createModel();
    const ui = freshUi();
    ui.browse = { format: 'Constructed' };
    ui.importer = { from: 'start' };
    expect(keyCommand(press('Escape'), model, ui)).toBe('closeImporter');
    ui.importer = null;
    expect(keyCommand(press('Escape'), model, ui)).toBe('closeBrowse');
  });
});

describe('rebound keys', () => {
  // Fails if keyCommand keeps reading the default letters instead of the player's bindings
  it('answers the key the player chose, and not the default', () => {
    const model = createModel();
    model.inMatch = true;
    model.prompt = prompt(true, true);
    const keys = { ...DEFAULT_KEYS, endTurn: 'x', ok: 'k' };
    expect(keyCommand(press('x'), model, freshUi(), false, keys)).toBe('endTurn');
    expect(keyCommand(press('e'), model, freshUi(), false, keys)).toBeNull();
    expect(keyCommand(press('k'), model, freshUi(), false, keys)).toBe('ok');
    expect(keyCommand(press('Enter'), model, freshUi(), false, keys)).toBe('ok');
  });

  // Fails if choosing a key another action holds leaves both actions on one key
  it('swaps keys when the chosen one is taken', () => {
    expect(rebind(DEFAULT_KEYS, 'endTurn', 'z')).toEqual({ ...DEFAULT_KEYS, endTurn: 'z', undo: 'e' });
  });

  // Fails if a key the page keeps for itself can be taken, so Escape or a menu number stops working
  it('refuses the keys the page keeps', () => {
    expect(rebind(DEFAULT_KEYS, 'undo', 'Escape')).toBe(DEFAULT_KEYS);
    expect(rebind(DEFAULT_KEYS, 'undo', '3')).toBe(DEFAULT_KEYS);
  });
});
