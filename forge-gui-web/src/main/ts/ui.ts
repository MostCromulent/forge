// What this browser has arranged and the server never hears of: the match table, which a new match clears at once (resetMatchUi),
// and the menu pages' tabs and open panels, which it keeps

import { storeJson, storedJson } from './storage';
import { reducedMotion } from './dom';

/** The card or player the pointer is over, where src is empty when the viewer may not see the card and at is the element hovered in. */
type Hover = { card: number | null; src: string; from?: string; at?: HTMLElement } | { player: number };

/** A picker open over match setup, for one seat. */
export interface Picker {
  kind: 'deck' | 'sleeve' | 'avatar' | 'planes' | 'schemes' | 'vanguard' | 'commander';
  seat: number;
}

/** How an open zone is ordered: as the zone holds them, by name, by card type, or by mana value. */
export type ZoneSort = 'order' | 'name' | 'type' | 'mana';

/** A field that belongs to one match is cleared in resetMatchUi, and one that only one layout draws when the layout changes (main.ts). */
export interface UiState {
  /** Battlefield piles the player has laid out card by card, by the pile's signature. */
  openPiles: Set<string>;
  /** Zone panels the player has opened by clicking a tile, as `${playerKey}/${zone}`. */
  openZones: Set<string>;
  /** The open zones are folded to a bar so the board can be read; they are still open. */
  zonesMinimised: boolean;
  /** The question folded to a bar so the board can be read, by its id; it is still open. */
  requestFolded: number | null;
  /** Narrows the open zone to cards whose name contains this, lower case. */
  zoneSearch: string;
  zoneSort: ZoneSort;
  stackCollapsed: boolean;
  /** The stack item under the pointer, whose targets get arrows. */
  hoveredStackItem: number | null;
  /** Where a right-click on a stack item happened, until the server's answer opens the menu there. */
  stackMenuAt: { key: number; x: number; y: number } | null;
  stopsOpen: boolean;
  optionsOpen: boolean;
  /** Asking whether to concede, from the game menu or its shortcut. */
  concedeAsk: boolean;
  /** The game menu behind the prompt's ⋯ button, or a dialog opened from it. */
  gameMenu: 'menu' | 'stops' | 'decisions' | 'dev' | 'devSetup' | null;
  /** The host keeps the macro and does not report on it, so the page remembers whether it is recording one or has one. */
  macro: 'none' | 'recording' | 'recorded';
  /** The volume control beside the options button is open. */
  volumeOpen: boolean;
  /** The menu is asking for a new name and face, as the dock's edit button asked. */
  renaming: boolean;
  /** Which way to play the start page is showing the kinds of game for, kept so a step back from a table lands there. */
  menuChoice: 'play' | 'friends' | null;
  picker: Picker | null;
  /** The deck finder opened from the start page, with no seat, and the format it lists. A hello does not close it. */
  browse: { format: string } | null;
  /** The importer, and where it was opened from: a seat's finder, the start page, or the editor. */
  importer: { from: 'seat' | 'start' | 'editor'; seat?: number; text?: string; url?: string; sync?: boolean } | null;
  /** The host has chosen to watch the computer play its seat. */
  spectate: boolean;
  hover: Hover | null;
  /** Which face of the hovered card the zoom panel shows. */
  faceIndex: number;
  /** The zoom panel shows the hovered card's rules text instead of its image. Its key, T by default, swaps them. */
  cardText: boolean;
  /** The side column's panels. Kept in the browser across sessions. */
  sidePanels: { log: boolean; chat: boolean };
  draftHidden: boolean;
  /** The hovered card or player is pinned open as a sheet, as a rested finger asks on a phone. */
  inspect: boolean;
  /** The hand is open as a drawer over the board, on a phone. */
  handOpen: boolean;
  /** Where one opponent shows at a time: whose seat it is, the turn in which the player last chose one, and whether a phone's list of them is open. */
  openSeat: number | null;
  seatChosenTurn: number;
  seatsOpen: boolean;
  /** The permanents each opponent had when their seat was last showing, by player key. */
  seenOnField: Map<number, Set<number>>;
  /** On a phone, the player whose zones are open from their bar, where only the counts are kept on show. */
  zonesFor: number | null;
  /** The phone's menu sheet, and which of its tabs shows. */
  menuSheet: 'log' | 'chat' | 'players' | null;
}

const SIDE_KEY = 'forge.sidePanels';

export const ui: UiState = {
  openPiles: new Set(),
  openZones: new Set(),
  zonesMinimised: false,
  requestFolded: null,
  zoneSearch: '',
  zoneSort: 'order',
  stackCollapsed: false,
  hoveredStackItem: null,
  stackMenuAt: null,
  stopsOpen: false,
  optionsOpen: false,
  concedeAsk: false,
  gameMenu: null,
  macro: 'none',
  volumeOpen: false,
  renaming: false,
  menuChoice: null,
  picker: null,
  browse: null,
  importer: null,
  spectate: false,
  hover: null,
  faceIndex: 0,
  cardText: false,
  sidePanels: { log: true, chat: true, ...storedSidePanels() },
  draftHidden: false,
  inspect: false,
  handOpen: false,
  openSeat: null,
  zonesFor: null,
  seatChosenTurn: 0,
  seatsOpen: false,
  seenOnField: new Map(),
  menuSheet: null,
  // The campaigns add their own fields as they load (campaign/register.tsx)
} as UiState;

let redraw: () => void = () => {};
let drawNow: () => void = () => {};

export function initUi(schedule: () => void, now: () => void): void {
  redraw = schedule;
  drawNow = now;
}

/** Changes the arrangement and draws it at once, for a view transition, which shows no frames until it is drawn. */
function changeUiNow(change: (state: UiState) => void): void {
  change(ui);
  drawNow();
}

/** How long a change waits for what it shows to arrive before it eases in anyway. */
const READY_WAIT_MS = 500;

/**
 * Changes the arrangement inside a view transition, which eases what CSS cannot, such as a change of grid areas or of page.
 * `mark` names, once the change is drawn, the attributes the root carries while it runs, which pick the animation in CSS.
 * `ready` says whether the new page has what it shows; until it does the old one stays, so a placeholder never slides in.
 */
export function eased(change: (state: UiState) => void, mark: () => Record<string, string> = () => ({}), ready?: () => boolean): void {
  transition(async () => {
    changeUiNow(change);
    const until = performance.now() + READY_WAIT_MS;
    // Frames are held while a transition gets ready, so what arrives meanwhile is drawn here
    while (ready && !ready() && performance.now() < until) {
      await new Promise(next => setTimeout(next, 16));
      drawNow();
    }
  }, () => changeUi(change), mark);
}

/** As eased, for a change held in a component's own state, which Preact draws a moment after it is made rather than at once. */
export function easedLocal(change: () => void, mark: () => Record<string, string>): void {
  transition(async () => {
    change();
    await new Promise(drawn => setTimeout(drawn));
  }, change, mark);
}

function transition(update: () => void | Promise<void>, still: () => void, mark: () => Record<string, string>): void {
  if (!document.startViewTransition || reducedMotion()) {
    still();
    return;
  }
  const root = document.documentElement;
  let marks: Record<string, string> = {};
  const running = document.startViewTransition(async () => {
    await update();
    marks = mark();
    Object.assign(root.dataset, marks);
  });
  running.finished.finally(() => { for (const key of Object.keys(marks)) delete root.dataset[key]; });
}

/** Changes the arrangement and draws the table again. */
export function changeUi(change: (state: UiState) => void): void {
  change(ui);
  redraw();
}

/** Draws again for a change kept outside ui, such as a stored preference or a picture that failed to load. */
export function redrawUi(): void {
  redraw();
}

/** A new match: the cards the arrangement was about are gone, but how the player likes the side column stays. */
export function resetMatchUi(): void {
  ui.openPiles.clear();
  ui.openZones.clear();
  ui.zonesMinimised = false;
  ui.requestFolded = null;
  ui.zoneSearch = '';
  ui.zoneSort = 'order';
  ui.hoveredStackItem = null;
  ui.stackMenuAt = null;
  ui.stopsOpen = false;
  ui.optionsOpen = false;
  ui.concedeAsk = false;
  ui.gameMenu = null;
  ui.macro = 'none';
  ui.volumeOpen = false;
  ui.menuSheet = null;
  ui.picker = null;
  ui.hover = null;
  ui.inspect = false;
  ui.handOpen = false;
  ui.openSeat = null;
  ui.zonesFor = null;
  ui.seatChosenTurn = 0;
  ui.seatsOpen = false;
  ui.seenOnField.clear();
  ui.faceIndex = 0;
}

export function rememberSidePanels(): void {
  storeJson(SIDE_KEY, ui.sidePanels);
}

function storedSidePanels(): Partial<UiState['sidePanels']> {
  return storedJson(SIDE_KEY, {});
}
