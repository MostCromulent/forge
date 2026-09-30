// Every setting the player can change: in the options dialog, or in the volume control for those marked with it.
// Settings marked server:true are Forge preferences shared with the desktop client; the rest live in this browser.

import type { KeyBindings } from './keys';
import type { ServerSettings } from './protocol';
import { storeJson, storedJson } from './storage';
import { t } from './text';

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
  { section: t('lblWebOptionsSectionInterrupts'), key: 'interruptAttackers', label: t('lblWebOptionsInterruptAttackers'), type: 'toggle', server: true, menu: 'stops', def: true },
  { section: t('lblWebOptionsSectionInterrupts'), key: 'interruptOpponentSpell', label: t('lblWebOptionsInterruptOpponentSpell'), type: 'toggle', server: true, menu: 'stops', def: true },
  { section: t('lblWebOptionsSectionInterrupts'), key: 'interruptTargeting', label: t('lblWebOptionsInterruptTargeting'), type: 'toggle', server: true, menu: 'stops', def: false },
  { section: t('lblWebOptionsSectionInterrupts'), key: 'interruptTriggers', label: t('lblWebOptionsInterruptTriggers'), type: 'toggle', server: true, menu: 'stops', def: false },
  { section: t('lblWebOptionsSectionInterrupts'), key: 'interruptMassRemoval', label: t('lblWebOptionsInterruptMassRemoval'), type: 'toggle', server: true, menu: 'stops', def: false },
  {
    section: t('lblPriority'), key: 'autoYieldMode', label: t('lblWebOptionsAutoYieldMode'), type: 'choice', server: true, menu: 'decisions',
    hint: t('lblWebOptionsAutoYieldModeHint'),
    options: [['ability', t('lblWebOptionsPerAbility')], ['card', t('lblWebOptionsPerCard')]], def: 'ability',
  },
  {
    section: t('lblWebOptionsSectionGameplay'), key: 'autoPassDelay', label: t('lblWebOptionsAutoPassDelay'),
    hint: t('lblWebOptionsAutoPassDelayHint'),
    type: 'slider', min: 0, max: 3000, step: 250, unit: 'seconds', def: 1500,
  },
  {
    section: t('lblWebOptionsSectionGameplay'), key: 'autoTapPreview', label: t('lblWebOptionsAutoTapPreview'), type: 'toggle', server: true, def: false,
  },
  {
    section: t('lblWebOptionsSectionGameplay'), key: 'arrows', label: t('lblWebOptionsArrows'), type: 'choice', server: true,
    options: [['0', t('lblOff')], ['1', t('lblWebOptionsHover')], ['2', t('lblAlways')]], def: '2',
  },
  {
    section: t('lblWebOptionsSectionGameplay'), key: 'logDetail', label: t('lblWebOptionsLogDetail'), type: 'choice', server: true,
    options: [['LOW', t('lblWebOptionsLow')], ['MEDIUM', t('lblWebOptionsMedium')], ['HIGH', t('lblWebOptionsHigh')]], def: 'MEDIUM',
  },
  {
    section: t('lblWebOptionsSectionDisplay'), key: 'boardLayout', label: t('lblWebOptionsBoardLayout'), hint: t('lblWebOptionsBoardLayoutHint'), type: 'choice',
    options: [['columns', t('lblWebOptionsColumns')], ['quadrants', t('lblWebOptionsQuadrants')]], def: 'columns',
  },
  {
    section: t('lblWebOptionsSectionDisplay'), key: 'handSort', label: t('lblWebOptionsHandSort'), type: 'choice',
    options: [['mana', t('lblWebOptionsManaValue')], ['color', t('lblWebOptionsColour')], ['draw', t('lblWebOptionsDrawn')]], def: 'mana',
  },
  { section: t('lblWebOptionsSectionDisplay'), key: 'handSize', label: t('lblWebOptionsHandSize'), type: 'slider', min: 70, max: 130, def: 100 },
  {
    section: t('lblWebOptionsSectionDisplay'), key: 'motion', label: t('lblWebOptionsMotion'), hint: t('lblWebOptionsMotionHint'), type: 'choice',
    options: [['full', t('lblWebOptionsFull')], ['system', t('lblWebOptionsSystem')], ['reduced', t('lblWebOptionsReduced')]], def: 'full',
  },
  {
    section: t('lblWebOptionsSectionSound'), key: 'soundVolume', label: t('lblWebOptionsSoundVolume'), type: 'slider', server: true, volume: true, min: 0, max: 100, def: 100,
  },
  {
    section: t('lblWebOptionsSectionSound'), key: 'musicVolume', label: t('lblWebOptionsMusicVolume'), type: 'slider', server: true, volume: true, min: 0, max: 100, def: 100,
  },
  { section: t('lblWebOptionsSectionKeys'), key: 'keyOk', label: t('lblOK'), action: 'ok', type: 'key', def: ' ' },
  { section: t('lblWebOptionsSectionKeys'), key: 'keyEndTurn', label: t('lblWebOptionsKeyEndTurn'), action: 'endTurn', type: 'key', def: 'e' },
  { section: t('lblWebOptionsSectionKeys'), key: 'keyAutoPass', label: t('lblWebOptionsKeyAutoPass'), hint: t('lblWebOptionsKeyAutoPassHint'), action: 'autoPass', type: 'key', def: 'p' },
  { section: t('lblWebOptionsSectionKeys'), key: 'keyUndo', label: t('lblUndo'), action: 'undo', type: 'key', def: 'z' },
  { section: t('lblWebOptionsSectionKeys'), key: 'keyNextFace', label: t('lblWebOptionsKeyNextFace'), hint: t('lblWebOptionsUnderPointer'), action: 'nextFace', type: 'key', def: 'f' },
  { section: t('lblWebOptionsSectionKeys'), key: 'keyCardText', label: t('lblWebOptionsKeyCardText'), hint: t('lblWebOptionsUnderPointer'), action: 'cardText', type: 'key', def: 't' },
  {
    section: t('lblWebOptionsSectionAdvanced'), key: 'devMode', label: t('lblWebDevMode'), type: 'toggle', server: true, hostOnly: true, def: false,
    hint: t('lblWebOptionsDevModeHint'),
  },
  {
    section: t('lblWebOptionsSectionAdvanced'), key: 'customCss', label: t('lblWebOptionsCustomCss'), hint: t('lblWebOptionsCustomCssHint'), type: 'css', def: '',
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
