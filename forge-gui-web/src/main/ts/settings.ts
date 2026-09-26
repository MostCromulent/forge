// Every setting the player can change: in the options dialog, or in the volume control for those marked with it.
// Settings marked server:true are Forge preferences shared with the desktop client; the rest live in this browser.

import type { KeyBindings } from './keys';
import type { ServerSettings } from './protocol';
import { storeJson, storedJson } from './storage';

const LOCAL_KEY = 'forge.settings';
/** A guest's settings that the server keeps. The server keeps them only as long as the session, so the browser
 *  remembers them and gives them back whenever it connects. */
const GUEST_KEY = 'forge.guestSettings';

export type SettingValue = string | number | boolean;

interface SettingBase {
  section: string;
  key: string;
  label: string;
  hint?: string;
  server?: boolean;
  /** Set from the volume control beside the options button rather than in the options dialog. */
  volume?: boolean;
  /** Only the host has it: a guest's browser leaves it out of the options. */
  hostOnly?: boolean;
  /** Set in a dialog opened from the game menu rather than in the options dialog. */
  menu?: 'stops' | 'decisions';
}

export type SettingDef = SettingBase & (
  | { type: 'toggle'; def: boolean }
  | { type: 'choice'; options: [string, string][]; def: string }
  | { type: 'slider'; min: number; max: number; def: number; step?: number; unit?: 'seconds' }
  | { type: 'css'; def: string }
  | { type: 'key'; action: keyof KeyBindings; def: string }
);

export const SETTINGS: SettingDef[] = [
  { section: 'Stops', key: 'interruptAttackers', label: 'Attackers are declared', type: 'toggle', server: true, menu: 'stops', def: true },
  { section: 'Stops', key: 'interruptOpponentSpell', label: 'An opponent casts a spell', type: 'toggle', server: true, menu: 'stops', def: true },
  { section: 'Stops', key: 'interruptTargeting', label: 'Something targets me', type: 'toggle', server: true, menu: 'stops', def: false },
  { section: 'Stops', key: 'interruptTriggers', label: 'An ability triggers', type: 'toggle', server: true, menu: 'stops', def: false },
  { section: 'Stops', key: 'interruptMassRemoval', label: 'A spell would destroy many permanents', type: 'toggle', server: true, menu: 'stops', def: false },
  {
    section: 'Priority', key: 'autoYieldMode', label: 'Remember them', type: 'choice', server: true, menu: 'decisions',
    hint: 'Per ability covers every card with the same ability; per card, only that card. Each mode keeps its own list.',
    options: [['ability', 'Per ability'], ['card', 'Per card']], def: 'ability',
  },
  {
    section: 'Gameplay', key: 'autoPassDelay', label: 'Auto-pass countdown',
    hint: 'Time to stop a pass before it happens. Zero passes at once.',
    type: 'slider', min: 0, max: 3000, step: 250, unit: 'seconds', def: 1500,
  },
  {
    section: 'Gameplay', key: 'autoTapPreview', label: 'Highlight lands Auto would tap', type: 'toggle', server: true, def: false,
  },
  {
    section: 'Gameplay', key: 'arrows', label: 'Target and combat arrows', type: 'choice', server: true,
    options: [['0', 'Off'], ['1', 'Hover'], ['2', 'Always']], def: '2',
  },
  {
    section: 'Gameplay', key: 'logDetail', label: 'Game log detail', type: 'choice', server: true,
    options: [['LOW', 'Low'], ['MEDIUM', 'Medium'], ['HIGH', 'High']], def: 'MEDIUM',
  },
  {
    section: 'Display', key: 'boardLayout', label: 'Table layout', hint: 'With three or four players.', type: 'choice',
    options: [['columns', 'Columns'], ['quadrants', 'Quadrants']], def: 'columns',
  },
  {
    section: 'Display', key: 'handSort', label: 'Hand order', type: 'choice',
    options: [['mana', 'Mana value'], ['draw', 'Drawn']], def: 'mana',
  },
  { section: 'Display', key: 'handSize', label: 'Hand size', type: 'slider', min: 70, max: 130, def: 100 },
  {
    section: 'Display', key: 'motion', label: 'Animations', hint: 'System follows the computer\'s reduce-motion setting.', type: 'choice',
    options: [['full', 'Full'], ['system', 'System'], ['reduced', 'Reduced']], def: 'full',
  },
  {
    section: 'Sound', key: 'soundVolume', label: 'Effects', type: 'slider', server: true, volume: true, min: 0, max: 100, def: 100,
  },
  {
    section: 'Sound', key: 'musicVolume', label: 'Music', type: 'slider', server: true, volume: true, min: 0, max: 100, def: 100,
  },
  { section: 'Keys', key: 'keyOk', label: 'OK', action: 'ok', type: 'key', def: ' ' },
  { section: 'Keys', key: 'keyEndTurn', label: 'End turn', action: 'endTurn', type: 'key', def: 'e' },
  { section: 'Keys', key: 'keyUndo', label: 'Undo', action: 'undo', type: 'key', def: 'z' },
  { section: 'Keys', key: 'keyNextFace', label: 'Turn a card over', hint: 'The card under the pointer.', action: 'nextFace', type: 'key', def: 'f' },
  { section: 'Keys', key: 'keyCardText', label: 'Show card text', hint: 'The card under the pointer.', action: 'cardText', type: 'key', def: 't' },
  {
    section: 'Advanced', key: 'devMode', label: 'Dev mode', type: 'toggle', server: true, hostOnly: true, def: false,
    hint: 'Cheats for testing, in the ⋯ menu during a game.',
  },
  {
    section: 'Advanced', key: 'customCss', label: 'Custom CSS', hint: 'Applies as you type. Kept in this browser.', type: 'css', def: '',
  },
];

const byKey = new Map(SETTINGS.map(s => [s.key, s]));
let local: Record<string, SettingValue> = {};
let server: Partial<ServerSettings> = {};
/** Saves a setting the server keeps. */
let saveOnServer: (key: string, value: string) => void = () => {};
let redraw: () => void = () => {};
/** The host's server settings are Forge's preferences, which outlive the server; a guest's do not. */
let guest = false;

export function setGuest(value: boolean): void {
  guest = value;
}

export function isGuest(): boolean {
  return guest;
}

export function initSettings(save: (key: string, value: string) => void, schedule: () => void): void {
  saveOnServer = save;
  redraw = schedule;
  local = storedJson(LOCAL_KEY, {});
  apply();
}

export function setting(key: string): SettingValue {
  const def = byKey.get(key);
  if (!def) throw new Error(`No setting ${key}`);
  const value = def.server ? (server as Record<string, SettingValue | undefined>)[key] : local[key];
  return value === undefined ? def.def : value;
}

// The server sends its preference values with the rest of the turn controls
export function onServerSettings(values: ServerSettings | undefined): void {
  server = values ?? {};
  apply();
}

/**
 * Gives the server the settings this guest's browser remembers. Sent as the browser connects, before any game
 * opens, so the game is seeded with them; each one sets a value, so it does not matter what the server had.
 */
export function restoreGuestSettings(): void {
  for (const [key, value] of Object.entries(guestSettings())) {
    if (byKey.get(key)?.server) {
      saveOnServer(key, String(value));
    }
  }
}

function guestSettings(): Record<string, SettingValue> {
  return storedJson(GUEST_KEY, {});
}

export function set(key: string, value: SettingValue): void {
  const def = byKey.get(key);
  if (!def) throw new Error(`No setting ${key}`);
  if (def.server) {
    (server as Record<string, SettingValue>)[key] = value;
    saveOnServer(key, String(value));
    if (guest) {
      storeJson(GUEST_KEY, { ...guestSettings(), [key]: value });
    }
  } else {
    local[key] = value;
    storeJson(LOCAL_KEY, local);
  }
  apply();
  redraw();
}

const KEY_SETTINGS = SETTINGS.filter((d): d is SettingDef & { type: 'key' } => d.type === 'key');

/** The keys this player has chosen. */
export function boundKeys(): KeyBindings {
  return Object.fromEntries(KEY_SETTINGS.map(d => [d.action, String(setting(d.key))])) as unknown as KeyBindings;
}

/** The keys a player starts with. */
export function defaultKeys(): KeyBindings {
  return Object.fromEntries(KEY_SETTINGS.map(d => [d.action, d.def])) as unknown as KeyBindings;
}

/** Saves every key binding at once, so a swap between two actions lands as one change. */
export function setKeys(keys: KeyBindings): void {
  for (const d of KEY_SETTINGS) {
    local[d.key] = keys[d.action];
  }
  storeJson(LOCAL_KEY, local);
  redraw();
}

// Pushes the hand size, the highlight colour and the custom CSS into CSS; the rest is read where it is used
function apply(): void {
  const root = document.documentElement;
  // Desktop keeps the highlight colour as a preference of its own, with no control in this dialog
  root.style.setProperty('--playable', server.highlightColor ?? '#66ccff');
  const hand = Number(setting('handSize')) / 100;
  root.style.setProperty('--hand-w', `${Math.round(88 * hand)}px`);
  root.style.setProperty('--hand-h', `${Math.round(123 * hand)}px`);
  const motion = setting('motion');
  const reduced = motion === 'reduced' || (motion === 'system' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches);
  root.dataset.motion = reduced ? 'reduced' : 'full';
  customStyle().textContent = String(setting('customCss') ?? '');
}

function customStyle(): HTMLStyleElement {
  let style = document.getElementById('custom-css') as HTMLStyleElement | null;
  if (!style) {
    style = document.createElement('style');
    style.id = 'custom-css';
    document.head.append(style);
  }
  return style;
}
