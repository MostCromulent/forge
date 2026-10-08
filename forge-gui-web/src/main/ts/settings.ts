// Settings marked server:true are Forge preferences shared with the desktop client, and the rest live in this browser

import type { KeyBindings } from './keys';
import type { ServerSettings } from './protocol';
import { storeJson, storedJson } from './storage';
import { t, type TextKey } from './text';

const LOCAL_KEY = 'forge.settings';
/** The server keeps a guest's settings only as long as the session, so the browser remembers them and gives them back on connecting. */
const GUEST_KEY = 'forge.guestSettings';


/** The playmats a player can lay under the board; the first is the bare table, with no picture. */
export const PLAYMATS: { id: string; name: TextKey; image: string | null; thumb: string | null }[] = [
  { id: 'table', name: 'lblNone', image: null, thumb: null },
  { id: 'mistbound', name: 'lblWebPlaymatMistbound', image: '/playmats/mistbound-ruins.jpg', thumb: '/playmats/mistbound-ruins-thumb.jpg' },
  { id: 'nebula', name: 'lblWebPlaymatNebula', image: '/playmats/stormy-nebula.jpg', thumb: '/playmats/stormy-nebula-thumb.jpg' },
  { id: 'bayou', name: 'lblWebPlaymatBayou', image: '/playmats/twilight-bayou.jpg', thumb: '/playmats/twilight-bayou-thumb.jpg' },
  { id: 'steppe', name: 'lblWebPlaymatSteppe', image: '/playmats/dawn-steppe.jpg', thumb: '/playmats/dawn-steppe-thumb.jpg' },
  { id: 'molten', name: 'lblWebPlaymatMolten', image: '/playmats/molten-forge.jpg', thumb: '/playmats/molten-forge-thumb.jpg' },
];
export type SettingValue = string | number | boolean;

interface SettingBase {
  section: string;
  key: string;
  label: string;
  hint?: string;
  server?: boolean;
  /** Also set from the volume control beside the options button. */
  volume?: boolean;
  /** Only the host has it: a guest's browser leaves it out of the options. */
  hostOnly?: boolean;
  /** Also set in a dialog opened from the game menu. */
  menu?: 'stops' | 'decisions' | 'playmat';
}

/** A choice shown as a picture: its value, name, a line under the name, the drawing's SVG, and when it cannot be chosen. */
export interface Tile {
  value: string;
  label: string;
  hint: string;
  picture: string;
  unavailable?: () => boolean;
}

export type SettingDef = SettingBase & (
  | { type: 'toggle'; def: boolean }
  | { type: 'choice'; options: [string, string][]; def: string }
  | { type: 'tiles'; options: Tile[]; def: string }
  | { type: 'slider'; min: number; max: number; def: number; step?: number; unit?: 'seconds' }
  | { type: 'css'; def: string }
  | { type: 'key'; action: keyof KeyBindings; def: string }
);

const theirs = (x: number, y: number, width: number) => `<rect class="theirs" x="${x}" y="${y}" width="${width}" height="32" rx="3"/>`;
const YOURS = '<rect class="yours" x="4" y="44" width="96" height="32" rx="3"/>';
/** A table of five or more shows one opponent at a time whatever is chosen, so the other layouts cannot be picked during one. */
const tabsOnly = () => Number(document.querySelector<HTMLElement>('#match:not([hidden])')?.dataset.opponents ?? 0) > 3;

export const SETTINGS: SettingDef[] = [
  { section: t('lblWebGameMenuStopsTitle'), key: 'interruptAttackers', label: t('lblWebOptionsInterruptAttackers'), type: 'toggle', server: true, menu: 'stops', def: true },
  { section: t('lblWebGameMenuStopsTitle'), key: 'interruptOpponentSpell', label: t('lblWebOptionsInterruptOpponentSpell'), type: 'toggle', server: true, menu: 'stops', def: true },
  { section: t('lblWebGameMenuStopsTitle'), key: 'interruptTargeting', label: t('lblWebOptionsInterruptTargeting'), type: 'toggle', server: true, menu: 'stops', def: false },
  { section: t('lblWebGameMenuStopsTitle'), key: 'interruptTriggers', label: t('lblWebOptionsInterruptTriggers'), type: 'toggle', server: true, menu: 'stops', def: false },
  { section: t('lblWebGameMenuStopsTitle'), key: 'interruptMassRemoval', label: t('lblWebOptionsInterruptMassRemoval'), type: 'toggle', server: true, menu: 'stops', def: false },
  {
    section: t('lblWebGameMenuDecisionsTitle'), key: 'autoYieldMode', label: t('lblWebOptionsAutoYieldMode'), type: 'choice', server: true, menu: 'decisions',
    hint: t('lblWebOptionsAutoYieldModeHint'),
    options: [['ability', t('lblWebOptionsPerAbility')], ['card', t('lblWebOptionsPerCard')]], def: 'ability',
  },
  {
    section: t('lblWebOptionsSectionGameplay'), key: 'pace', label: t('lblWebOptionsPace'), hint: t('lblWebOptionsPaceHint'),
    type: 'slider', min: 0, max: 3000, step: 250, unit: 'seconds', def: 1000,
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
    options: [['LOW', t('lblWebOptionsLow')], ['MEDIUM', t('lblMedium')], ['HIGH', t('lblWebOptionsHigh')]], def: 'MEDIUM',
  },
  {
    section: t('lblDisplay'), key: 'boardLayout', label: t('lblWebOptionsBoardLayout'), type: 'tiles', def: 'columns',
    options: [
      { value: 'columns', label: t('lblColumns'), hint: t('lblWebOptionsColumnsHint'), unavailable: tabsOnly,
        picture: `${theirs(4, 4, 30)}${theirs(37, 4, 30)}${theirs(70, 4, 30)}${YOURS}` },
      { value: 'quadrants', label: t('lblWebOptionsQuadrants'), hint: t('lblWebOptionsQuadrantsHint'), unavailable: tabsOnly,
        picture: `${theirs(4, 4, 46)}${theirs(54, 4, 46)}<rect class="yours" x="4" y="44" width="46" height="32" rx="3"/>${theirs(54, 44, 46)}` },
      { value: 'tabs', label: t('lblWebOptionsTabs'), hint: t('lblWebOptionsTabsHint'),
        picture: `<rect class="theirs" x="4" y="12" width="96" height="24" rx="3"/><rect class="tab on" x="4" y="4" width="18" height="9" rx="2"/><rect class="tab" x="25" y="5" width="16" height="6" rx="2"/><rect class="tab" x="44" y="5" width="16" height="6" rx="2"/>${YOURS}` },
    ],
  },
  {
    section: t('lblDisplay'), key: 'handSort', label: t('lblWebOptionsHandSort'), type: 'choice',
    options: [['mana', t('lblWebDraftGroupManaValue')], ['color', t('lblColor')], ['draw', t('lblWebOptionsDrawn')]], def: 'mana',
  },
  { section: t('lblDisplay'), key: 'handSize', label: t('lblWebOptionsHandSize'), type: 'slider', min: 70, max: 130, def: 100 },
  { section: t('lblDisplay'), key: 'tabNewCards', label: t('lblWebOptionsTabNewCards'), hint: t('lblWebOptionsTabNewCardsHint'), type: 'toggle', def: true },
  { section: t('lblDisplay'), key: 'swapPrompt', label: t('lblWebOptionsSwapPrompt'), hint: t('lblWebOptionsSwapPromptHint'), type: 'toggle', def: false },
  {
    section: t('lblDisplay'), key: 'previewSize', label: t('lblWebOptionsPreviewSize'), hint: t('lblWebOptionsPreviewSizeHint'), type: 'choice',
    options: [['small', t('lblWebOptionsSmall')], ['medium', t('lblMedium')], ['large', t('lblWebOptionsLarge')]], def: 'medium',
  },
  {
    section: t('lblDisplay'), key: 'playmat', label: t('lblWebPlaymat'), type: 'choice', menu: 'playmat',
    options: PLAYMATS.map(m => [m.id, t(m.name)]), def: 'nebula',
  },
  {
    section: t('lblDisplay'), key: 'playmatBrightness', label: t('lblWebPlaymatBrightness'), type: 'choice', menu: 'playmat',
    options: [['dark', t('lblWebPlaymatDark')], ['dim', t('lblWebPlaymatDim')], ['light', t('lblWebPlaymatLight')], ['bright', t('lblWebPlaymatBright')]], def: 'dim',
  },
  {
    section: t('lblDisplay'), key: 'motion', label: t('lblWebOptionsMotion'), hint: t('lblWebOptionsMotionHint'), type: 'choice',
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
    section: t('lblAdvanced'), key: 'devMode', label: t('lblWebDevMode'), type: 'toggle', server: true, hostOnly: true, def: false,
    hint: t('lblWebOptionsDevModeHint'),
  },
  {
    section: t('lblAdvanced'), key: 'customCss', label: t('lblWebOptionsCustomCss'), hint: t('lblWebOptionsCustomCssHint'), type: 'css', def: '',
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

/** Gives the server the settings this guest's browser remembers, sent before any game opens so the game is seeded with them. */
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

// Pushes the hand and preview sizes, the highlight colour and the custom CSS into CSS; the rest is read where it is used
function apply(): void {
  const root = document.documentElement;
  // Desktop keeps the highlight colour as a preference of its own, with no control in this dialog
  root.style.setProperty('--playable', server.highlightColor ?? '#66ccff');
  const hand = Number(setting('handSize')) / 100;
  root.style.setProperty('--hand-w', `${Math.round(88 * hand)}px`);
  root.style.setProperty('--hand-h', `${Math.round(123 * hand)}px`);
  root.dataset.preview = String(setting('previewSize'));
  const mat = PLAYMATS.find(m => m.id === setting('playmat'));
  root.style.setProperty('--playmat', mat?.image ? `url("${mat.image}")` : 'none');
  root.dataset.playmat = mat?.image ? String(setting('playmatBrightness')) : '';
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
