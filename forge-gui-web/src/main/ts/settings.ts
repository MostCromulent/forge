// Every setting the player can change: in the options dialog, or in the volume control for those marked with it.
// Settings marked server:true are Forge preferences shared with the desktop client; the rest live in this browser.

import type { KeyBindings } from './keys';
import type { ServerSettings } from './protocol';

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
  /** Set in a dialog opened from the game menu rather than in the options dialog. */
  menu?: 'stops' | 'decisions' | 'keys';
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
    options: [['ability', 'Per ability'], ['card', 'Per card']], def: 'ability',
  },
  {
    section: 'Priority', key: 'autoPassDelay', label: 'Auto-pass countdown',
    hint: 'How long the pass button fills before priority passes by itself, so you can stop it. Zero passes at once.',
    type: 'slider', min: 0, max: 3000, step: 250, unit: 'seconds', def: 1500,
  },
  {
    section: 'Game log', key: 'logDetail', label: 'Detail', type: 'choice', server: true,
    options: [['LOW', 'Low'], ['MEDIUM', 'Medium'], ['HIGH', 'High']], def: 'MEDIUM',
  },
  {
    section: 'Cards', key: 'autoTapPreview', label: 'Highlight the lands Auto would tap', type: 'toggle', server: true, def: false,
  },
  {
    section: 'Cards', key: 'boardLayout', label: 'Three or four players', type: 'choice',
    hint: 'Columns lines the opponents up across the top; quadrants gives every player a quarter of the table.',
    options: [['columns', 'Columns'], ['quadrants', 'Quadrants']], def: 'columns',
  },
  {
    section: 'Cards', key: 'handSort', label: 'Sort hand', type: 'choice',
    options: [['mana', 'By mana value'], ['draw', 'As drawn']], def: 'mana',
  },
  { section: 'Cards', key: 'handSize', label: 'Hand size', type: 'slider', min: 70, max: 130, def: 100 },
  {
    section: 'Arrows', key: 'arrows', label: 'Target and combat arrows', type: 'choice', server: true,
    options: [['0', 'Off'], ['1', 'On hover'], ['2', 'Always']], def: '2',
  },
  {
    section: 'Motion', key: 'motion', label: 'Animations', type: 'choice',
    hint: 'Reduced stills the shattering portraits, the drifting motes and other flourishes. Following the system reduces them when the computer is set to reduce motion.',
    options: [['full', 'All animations'], ['system', 'Follow the system'], ['reduced', 'Reduced']], def: 'full',
  },
  {
    section: 'Sound', key: 'soundVolume', label: 'Effects', type: 'slider', server: true, volume: true, min: 0, max: 100, def: 100,
  },
  {
    section: 'Sound', key: 'musicVolume', label: 'Music', type: 'slider', server: true, volume: true, min: 0, max: 100, def: 100,
  },
  { section: 'Keys', key: 'keyOk', label: 'OK', action: 'ok', type: 'key', menu: 'keys', def: ' ' },
  { section: 'Keys', key: 'keyEndTurn', label: 'End turn', action: 'endTurn', type: 'key', menu: 'keys', def: 'e' },
  { section: 'Keys', key: 'keyUndo', label: 'Undo', action: 'undo', type: 'key', menu: 'keys', def: 'z' },
  { section: 'Keys', key: 'keyNextFace', label: 'Turn the card under the pointer over', action: 'nextFace', type: 'key', menu: 'keys', def: 'f' },
  { section: 'Keys', key: 'keyCardText', label: 'Show the text of the card under the pointer', action: 'cardText', type: 'key', menu: 'keys', def: 't' },
  {
    section: 'Theme', key: 'customCss', label: 'Custom CSS',
    hint: 'Applied to every screen as you type, and kept in this browser.', type: 'css', def: '',
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

export function initSettings(save: (key: string, value: string) => void, schedule: () => void): void {
  saveOnServer = save;
  redraw = schedule;
  try {
    local = JSON.parse(localStorage.getItem(LOCAL_KEY) ?? '{}');
  } catch {
    local = {};
  }
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
  try {
    return JSON.parse(localStorage.getItem(GUEST_KEY) ?? '{}') ?? {};
  } catch {
    return {};
  }
}

export function set(key: string, value: SettingValue): void {
  const def = byKey.get(key);
  if (!def) throw new Error(`No setting ${key}`);
  if (def.server) {
    (server as Record<string, SettingValue>)[key] = value;
    saveOnServer(key, String(value));
    if (guest) {
      try {
        localStorage.setItem(GUEST_KEY, JSON.stringify({ ...guestSettings(), [key]: value }));
      } catch {
        // A browser with storage blocked keeps the setting for this session only
      }
    }
  } else {
    local[key] = value;
    try {
      localStorage.setItem(LOCAL_KEY, JSON.stringify(local));
    } catch {
      // A browser with storage blocked keeps the setting for this session only
    }
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
  try {
    localStorage.setItem(LOCAL_KEY, JSON.stringify(local));
  } catch {
    // A browser with storage blocked keeps the setting for this session only
  }
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
