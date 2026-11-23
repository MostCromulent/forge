import { describe, expect, it } from 'vitest';
import { keyCommand, rebind, type KeyBindings } from '../../main/ts/keys';
import { createModel } from '../../main/ts/model';
import type { Prompt, Request } from '../../main/ts/protocol';
import type { UiState } from '../../main/ts/ui';
import '../../main/ts/rogue/register';

const KEYS: KeyBindings = { ok: ' ', nextFace: 'f', cardText: 'c' };
const ESCAPE = { key: 'Escape', code: 27, ctrl: false, shift: false, alt: false, meta: false, typing: false };

function playing(over: { request?: Partial<Request>; prompt?: Partial<Prompt> }) {
  const model = createModel();
  model.screen = 'playing';
  if (over.request) model.requests.set(1, { t: 'request', id: 1, options: [], ...over.request } as Request);
  if (over.prompt) model.prompt = over.prompt as Prompt;
  return model;
}

const ui = { openZones: new Set(), requestFolded: null } as unknown as UiState;

describe('keyCommand', () => {
  it('folds a request laid out on the board on Escape, and never declines it', () => {
    expect(keyCommand(ESCAPE, playing({ request: { kind: 'order' } }), ui, KEYS, {})).toBe('foldRequest');
  });

  it("cancels the prompt on Escape, but never fires the alpha strike button that sits in Cancel's place", () => {
    expect(keyCommand(ESCAPE, playing({ prompt: { cancel: { label: '', enabled: true } } }), ui, KEYS, {})).toBe('cancel');
    expect(keyCommand(ESCAPE, playing({ prompt: { cancel: { label: '', enabled: true, kind: 'alphaStrike' } } }), ui, KEYS, {})).toBeNull();
  });
});

describe('a Rogue Commander scene', () => {
  const press = (key: string, typing = false) => ({ ...ESCAPE, key, code: key.charCodeAt(0), typing });
  const boon = () => {
    const model = createModel();
    model.screen = 'campaign';
    model.rogueAnnounced = [];
    model.rogueQuestion = {
      t: 'rogueQuestion', id: 4,
      choice: { kind: 'boon', title: '', paragraphs: [], rerolls: 0, skip: false, count: 0, options: [
        { label: 'a', text: [], enabled: true }, { label: 'b', text: [], enabled: false, reason: 'Not enough Gold.' }] },
    };
    return model;
  };
  const rogueUi = { ...ui, rogueFolded: null } as UiState;

  // Fails if a number picks a locked choice, or one past the last
  it('a number key picks only an enabled choice', () => {
    expect(keyCommand(press('1'), boon(), rogueUi, KEYS, {})).toBe('pickChoice1');
    expect(keyCommand(press('2'), boon(), rogueUi, KEYS, {})).toBeNull();
    expect(keyCommand(press('3'), boon(), rogueUi, KEYS, {})).toBeNull();
  });

  // Fails if a key typed into a field, or pressed under an announcement or with the scene folded, answers the scene
  it('keys are ignored while typing or while a dialog is open', () => {
    expect(keyCommand(press('1', true), boon(), rogueUi, KEYS, {})).toBeNull();
    const announced = boon();
    announced.rogueAnnounced = [{ t: 'rogueAnnounce', id: 1, kind: 'unlock', title: '', text: '' }];
    expect(keyCommand(press('1'), announced, rogueUi, KEYS, {})).toBeNull();
    expect(keyCommand(press('1'), boon(), { ...rogueUi, rogueFolded: 4 } as UiState, KEYS, {})).toBeNull();
  });
});

describe('rebind', () => {
  it('swaps the keys of two actions so no key does two things, and refuses a reserved key', () => {
    expect(rebind(KEYS, 'ok', 'F')).toEqual({ ok: 'f', nextFace: ' ', cardText: 'c' });
    expect(rebind(KEYS, 'ok', 'Enter')).toEqual(KEYS);
  });
});
