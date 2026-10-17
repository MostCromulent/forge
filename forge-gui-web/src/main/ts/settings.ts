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
  { id: 'emerald', name: 'lblWebPlaymatEmerald', image: '/playmats/emerald-mistscape.jpg', thumb: '/playmats/emerald-mistscape-thumb.jpg' },
];
type SettingValue = string | number | boolean;

interface SettingBase {
  section: string;
  key: string;
  label: string;
  hint?: string;
  /** The game reads it too, so its preference is in WebSettings and its value in ServerSettings. */
  server?: boolean;
  /** Also set from the volume control beside the options button. */
  volume?: boolean;
  /** Only the host has it: a guest's browser leaves it out of the options. */
  hostOnly?: boolean;
  /** Also set in a dialog opened from the game menu. */
  menu?: 'stops' | 'decisions' | 'playmat';
  /** Set from another setting's control or from a dialog, so the options give it no row of its own. */
  inside?: boolean;
  /** Listed in the options only while this holds. */
  when?: () => boolean;
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
  | { type: 'playmat'; def: string }
  /** Not a setting: a button that opens a dialog of the game menu. */
  | { type: 'dialog'; dialog: 'stops' | 'decisions'; def: string }
  | { type: 'slider'; min: number; max: number; def: number; step?: number; unit?: 'seconds' }
  | { type: 'css'; def: string }
  | { type: 'key'; action: keyof KeyBindings; def: string }
);

const theirs = (x: number, y: number, width: number) => `<rect class="theirs" x="${x}" y="${y}" width="${width}" height="32" rx="3"/>`;
const YOURS = '<rect class="yours" x="4" y="44" width="96" height="32" rx="3"/>';
/** A table of five or more shows one opponent at a time whatever is chosen, so the other layouts cannot be picked during one. */
const tabsOnly = () => Number(document.querySelector<HTMLElement>('#match:not([hidden])')?.dataset.opponents ?? 0) > 3;

/** How often a yield suggestion comes back once it is declined, as desktop offers it. */
const SCOPES: [string, string][] = [['NEVER', t('lblNever')], ['ALWAYS', t('lblAlways')], ['STACK', t('lblDeclScopeStack')], ['TURN', t('lblDeclScopeTurn')]];

export const SETTINGS: SettingDef[] = [
  { section: t('lblInterruptSettings'), key: 'interruptAttackers', label: t('lblInterruptOnAttackers'), type: 'toggle', server: true, menu: 'stops', inside: true, def: true },
  { section: t('lblInterruptSettings'), key: 'interruptOpponentSpell', label: t('lblInterruptOnOpponentSpell'), type: 'toggle', server: true, menu: 'stops', inside: true, def: true },
  { section: t('lblInterruptSettings'), key: 'interruptTargeting', label: t('lblInterruptOnTargeting'), type: 'toggle', server: true, menu: 'stops', inside: true, def: false },
  { section: t('lblInterruptSettings'), key: 'interruptTriggers', label: t('lblInterruptOnTriggers'), type: 'toggle', server: true, menu: 'stops', inside: true, def: false },
  { section: t('lblInterruptSettings'), key: 'interruptMassRemoval', label: t('lblInterruptOnMassRemoval'), type: 'toggle', server: true, menu: 'stops', inside: true, def: false },
  { section: t('lblInterruptSettings'), key: 'interruptReveal', label: t('lblInterruptOnReveal'), type: 'toggle', server: true, menu: 'stops', inside: true, def: false },
  {
    section: t('lblAutomaticSuggestions'), key: 'suggestStackYield', label: t('lblSuggestStackYield'), type: 'choice', server: true, menu: 'stops', inside: true,
    options: SCOPES, def: 'NEVER',
  },
  {
    section: t('lblAutomaticSuggestions'), key: 'suggestNoActions', label: t('lblSuggestNoActions'), type: 'choice', server: true, menu: 'stops', inside: true,
    options: SCOPES, def: 'NEVER',
  },
  { section: t('lblAutomaticSuggestions'), key: 'suppressAfterYield', label: t('lblSuppressAfterYield'), type: 'toggle', server: true, menu: 'stops', inside: true, def: true },
  { section: t('lblAutomaticSuggestions'), key: 'suppressOwnTurn', label: t('lblSuppressOnOwnTurn'), type: 'toggle', server: true, menu: 'stops', inside: true, def: true },
  {
    section: t('lblDisplay'), key: 'playmat', label: t('lblWebPlaymat'), type: 'playmat', menu: 'playmat', def: 'nebula',
  },
  {
    section: t('lblDisplay'), key: 'playmatBrightness', label: t('lblWebPlaymatBrightness'), type: 'choice', menu: 'playmat', inside: true,
    options: [['dark', t('lblWebPlaymatDark')], ['dim', t('lblWebPlaymatDim')], ['light', t('lblWebPlaymatLight')], ['bright', t('lblWebPlaymatBright')]], def: 'dim',
  },
  {
    section: t('lblDisplay'), key: 'boardLayout', label: t('lblMultiplayerFieldLayout'), type: 'tiles', def: 'columns',
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
    section: t('lblDisplay'), key: 'motion', label: t('lblWebOptionsMotion'), hint: t('lblWebOptionsMotionHint'), type: 'choice',
    options: [['full', t('lblWebOptionsFull')], ['system', t('lblWebOptionsSystem')], ['reduced', t('lblWebOptionsReduced')]], def: 'full',
  },
  {
    section: t('lblCards'), key: 'previewSize', label: t('lblWebOptionsPreviewSize'), hint: t('lblWebOptionsPreviewSizeHint'), type: 'choice',
    options: [['small', t('lblWebOptionsSmall')], ['medium', t('lblMedium')], ['large', t('lblWebOptionsLarge')]], def: 'medium',
  },
  { section: t('lblCards'), key: 'handSize', label: t('lblHandSize'), type: 'slider', min: 70, max: 130, def: 100 },
  {
    section: t('lblCards'), key: 'handSort', label: t('lblWebOptionsHandSort'), type: 'choice',
    options: [['mana', t('lblCMC')], ['color', t('lblColor')], ['draw', t('lblWebOptionsDrawn')]], def: 'mana',
  },
  {
    // Forge ships only the default set, so the choice shows once someone has added another, as on desktop
    section: t('lblWebOptionsSectionSound'), key: 'soundSet', label: t('cbpSoundSets'), type: 'choice', server: true, hostOnly: true,
    when: () => (server.soundSets?.length ?? 0) > 1,
    get options(): [string, string][] {
      return (server.soundSets ?? []).map(s => [s, s]);
    },
    def: 'Default',
  },
  {
    section: t('lblWebOptionsSectionSound'), key: 'soundVolume', label: t('lblWebOptionsSoundVolume'), type: 'slider', server: true, volume: true, min: 0, max: 100, def: 100,
  },
  {
    section: t('lblWebOptionsSectionSound'), key: 'musicVolume', label: t('lblWebOptionsMusicVolume'), type: 'slider', server: true, volume: true, min: 0, max: 100, def: 100,
  },
  {
    section: t('GamePlay'), key: 'pace', label: t('lblWebOptionsPace'), hint: t('lblWebOptionsPaceHint'),
    type: 'slider', min: 0, max: 3000, step: 250, unit: 'seconds', def: 1000,
  },
  {
    section: t('GamePlay'), key: 'logDetail', label: t('cbpGameLogEntryType'), type: 'choice', server: true,
    options: [['LOW', t('lblWebOptionsLow')], ['MEDIUM', t('lblMedium')], ['HIGH', t('lblWebOptionsHigh')]], def: 'MEDIUM',
  },
  { section: t('lblAutoYieldsAndTriggers'), key: 'yieldSettings', label: t('lblYieldSettings'), type: 'dialog', dialog: 'stops', def: '' },
  {
    // The host reads the remembered list from the player's own game, so a spectator or the menu has none to show
    section: t('lblAutoYieldsAndTriggers'), key: 'autoDecisions', label: t('lblWebGameMenuDecisions'), type: 'dialog', dialog: 'decisions', def: '',
    when: () => !!document.querySelector('#match:not([hidden])') && !document.querySelector('#prompt.spectating'),
  },
  {
    section: t('lblAutoYieldsAndTriggers'), key: 'autoYieldMode', label: t('cbpAutoDecisionMode'), type: 'choice', server: true, menu: 'decisions',
    hint: t('lblWebOptionsAutoYieldModeHint'),
    options: [['ability', t('lblWebOptionsPerAbility')], ['card', t('lblWebOptionsPerCard')]], def: 'ability',
  },
  {
    section: t('lblWebOptionsSectionTable'), key: 'mulliganRule', label: t('cbpMulliganRule'), type: 'choice', server: true, hostOnly: true,
    options: ['London', 'Vancouver', 'Paris', 'Original', 'Houston'].map(r => [r, r]), def: 'London',
  },
  {
    section: t('lblWebOptionsSectionTable'), key: 'aiProfile', label: t('cbpAiProfiles'), hint: t('lblWebOptionsAiProfileHint'),
    type: 'choice', server: true, hostOnly: true,
    options: ['Default', 'Cautious', 'Reckless', 'Experimental', 'Random (Every Match)', 'Random (Every Game)'].map(p => [p, p]), def: 'Default',
  },
  {
    section: t('lblWebOptionsSectionTable'), key: 'filteredHands', label: t('cbFilteredHands'), hint: t('lblWebOptionsFilteredHandsHint'),
    type: 'toggle', server: true, hostOnly: true, def: false,
  },
  {
    section: t('lblWebOptionsSectionTable'), key: 'ante', label: t('cbAnte'), type: 'toggle', server: true, hostOnly: true, def: false,
  },
  { section: t('lblKeyboardShortcuts'), key: 'keyOk', label: t('lblOK'), action: 'ok', type: 'key', def: ' ' },
  { section: t('lblKeyboardShortcuts'), key: 'keyEndTurn', label: t('lblEndTurn'), action: 'endTurn', type: 'key', def: 'e' },
  { section: t('lblKeyboardShortcuts'), key: 'keyAutoPass', label: t('lblWebOptionsKeyAutoPass'), hint: t('lblWebOptionsKeyAutoPassHint'), action: 'autoPass', type: 'key', def: 'p' },
  { section: t('lblKeyboardShortcuts'), key: 'keyUndo', label: t('lblUndo'), action: 'undo', type: 'key', def: 'z' },
  { section: t('lblKeyboardShortcuts'), key: 'keyNextFace', label: t('lblWebOptionsKeyNextFace'), hint: t('lblWebOptionsUnderPointer'), action: 'nextFace', type: 'key', def: 'f' },
  { section: t('lblKeyboardShortcuts'), key: 'keyCardText', label: t('lblWebOptionsKeyCardText'), hint: t('lblWebOptionsUnderPointer'), action: 'cardText', type: 'key', def: 't' },
  {
    section: t('lblAdvanced'), key: 'customCss', label: t('lblWebOptionsCustomCss'), hint: t('lblWebOptionsCustomCssHint'), type: 'css', def: '',
  },
  {
    section: t('lblAdvanced'), key: 'devMode', label: t('lblDevMode'), type: 'toggle', server: true, hostOnly: true, def: false,
    hint: t('lblWebOptionsDevModeHint'),
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

export function boundKeys(): KeyBindings {
  return Object.fromEntries(KEY_SETTINGS.map(d => [d.action, String(setting(d.key))])) as unknown as KeyBindings;
}

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

// Pushes the hand and preview sizes, the highlight colour, the playmat, the motion choice and the custom CSS into CSS; the rest is read where it is used
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
