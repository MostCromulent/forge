// Every setting the player can change: in the options dialog, or in the volume control for those marked with it.
// Settings marked server:true are Forge preferences shared with the desktop client; the rest live in this browser.

import type { ServerSettings } from './protocol';

const LOCAL_KEY = 'forge.settings';
const DEFAULTS_KEY = 'forge.defaults';
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
}

export type SettingDef = SettingBase & (
  | { type: 'toggle'; def: boolean }
  | { type: 'choice'; options: [string, string][]; def: string }
  | { type: 'slider'; min: number; max: number; def: number; step?: number; unit?: 'seconds' }
  | { type: 'css'; def: string }
);

export const SETTINGS: SettingDef[] = [
  {
    section: 'Priority', key: 'autoPassNoActions', label: 'Auto-pass when I have nothing to play',
    hint: 'The same setting as the auto-pass button.', type: 'toggle', server: true, def: false,
  },
  {
    section: 'Priority', key: 'interruptAttackers', label: 'Stop auto-passing when attackers are declared',
    type: 'toggle', server: true, def: true,
  },
  {
    section: 'Priority', key: 'interruptOpponentSpell', label: 'Stop auto-passing when an opponent casts a spell',
    type: 'toggle', server: true, def: true,
  },
  {
    section: 'Priority', key: 'interruptTargeting', label: 'Stop auto-passing when something targets me',
    type: 'toggle', server: true, def: false,
  },
  {
    section: 'Priority', key: 'interruptTriggers', label: 'Stop auto-passing on triggered abilities',
    type: 'toggle', server: true, def: false,
  },
  {
    section: 'Priority', key: 'interruptMassRemoval', label: 'Stop auto-passing on mass removal',
    type: 'toggle', server: true, def: false,
  },
  {
    section: 'Priority', key: 'autoYieldMode', label: 'Remember auto-yields', type: 'choice', server: true,
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
  { section: 'Game log', key: 'logImages', label: 'Card thumbnails in the log', type: 'toggle', def: true },
  {
    section: 'Cards', key: 'highlightPlayable', label: 'Highlight cards I can play', type: 'toggle', server: true, def: true,
  },
  {
    section: 'Cards', key: 'autoTapPreview', label: 'Highlight the lands Auto would tap', type: 'toggle', server: true, def: false,
  },
  {
    section: 'Cards', key: 'handSort', label: 'Sort hand', type: 'choice',
    options: [['mana', 'By mana value'], ['draw', 'As drawn']], def: 'mana',
  },
  { section: 'Cards', key: 'cardSize', label: 'Card size', type: 'slider', min: 70, max: 130, def: 100 },
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
  applyWebDefaults();
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

// Forge ships with playable-card highlighting off; this UI wants it on. A browser turns it on once, and after
// that the setting belongs to the player.
function applyWebDefaults(): void {
  try {
    if (localStorage.getItem(DEFAULTS_KEY)) {
      return;
    }
    localStorage.setItem(DEFAULTS_KEY, '1');
  } catch {
    return;
  }
  if (!setting('highlightPlayable')) {
    set('highlightPlayable', true);
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

// Pushes card and hand size, the highlight colour and the custom CSS into CSS; the rest is read where it is used
function apply(): void {
  const root = document.documentElement;
  // Desktop keeps the highlight colour as a preference of its own, with no control in this dialog
  root.style.setProperty('--playable', server.highlightColor ?? '#66ccff');
  const card = Number(setting('cardSize')) / 100;
  root.style.setProperty('--card-w', `${Math.round(88 * card)}px`);
  root.style.setProperty('--card-h', `${Math.round(123 * card)}px`);
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
