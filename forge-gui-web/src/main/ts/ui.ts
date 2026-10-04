// How the player has arranged the table, none of which goes to the server, kept in one place so a new match clears it at once

import { storeJson, storedJson } from './storage';

/** The card or player the pointer is over, where src is empty when the viewer may not see the card and at is the element hovered in. */
export type Hover = { card: number | null; src: string; from?: string; at?: HTMLElement } | { player: number };

/** A picker open over match setup, for one seat. */
export interface Picker {
  kind: 'deck' | 'sleeve' | 'avatar' | 'planes' | 'schemes' | 'vanguard';
  seat: number;
}

/** How an open zone is ordered: as the zone holds them, by name, or by card type. */
export type ZoneSort = 'order' | 'name' | 'type' | 'mana';

export type ConquestTab = 'map' | 'aether' | 'party' | 'collection' | 'planes' | 'stats';

export interface UiState {
  /** Battlefield piles the player has laid out card by card, by the pile's signature. */
  openPiles: Set<string>;
  /** Zone panels the player has opened by clicking a tile, as `${playerKey}/${zone}`. */
  openZones: Set<string>;
  /** The open zones are folded to a bar so the board can be read; they are still open. */
  zonesMinimised: boolean;
  /** Narrows the open zone to cards whose name contains this, lower case. */
  zoneSearch: string;
  zoneSort: ZoneSort;
  stackCollapsed: boolean;
  /** The stack item under the pointer, whose targets get arrows. */
  hoveredStackItem: number | null;
  /** Where a right-click on a stack item happened, until the server's answer opens the menu there. */
  stackMenuAt: { key: number; x: number; y: number } | null;
  /** The grid of phase stops is open. */
  stopsOpen: boolean;
  /** The options dialog is open. */
  optionsOpen: boolean;
  /** The game menu behind the prompt's ⋯ button, or a dialog opened from it. */
  gameMenu: 'menu' | 'stops' | 'decisions' | 'dev' | 'devSetup' | null;
  /** The volume control beside the options button is open. */
  volumeOpen: boolean;
  /** The menu is asking for a new name and face, as the dock's edit button asked. */
  renaming: boolean;
  /** Which way to play the start page is showing the kinds of game for, kept so a step back from a table lands there. */
  menuChoice: 'play' | 'friends' | null;
  /** Match setup's picker for a seat's deck, sleeve or avatar. */
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
  /** The zoom panel shows the hovered card's rules text instead of its image. T swaps them. */
  cardText: boolean;
  /** The side column's panels. Kept in the browser across sessions. */
  sidePanels: { log: boolean; chat: boolean };
  /** Which page of the open conquest is showing. It outlives the deck editor, which takes the page's place for a while. */
  conquestTab: ConquestTab;
  /** The map's regions the player has opened or closed by hand, as `plane:region`. */
  conquestOpened: Record<string, boolean>;
  /** An online draft was left for the table on this browser; the draft goes on, and Return to draft comes back to it. */
  draftHidden: boolean;
  /** The hovered card or player is pinned open as a sheet, as a rested finger asks on a phone. */
  inspect: boolean;
  /** The hand is open as a drawer over the board, on a phone. */
  handOpen: boolean;
  /** On a phone, the opponent whose seat is showing, and the turn in which the player last chose one. */
  openSeat: number | null;
  seatChosenTurn: number;
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
  zoneSearch: '',
  zoneSort: 'order',
  stackCollapsed: false,
  hoveredStackItem: null,
  stackMenuAt: null,
  stopsOpen: false,
  optionsOpen: false,
  gameMenu: null,
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
  conquestTab: 'map',
  conquestOpened: {},
  draftHidden: false,
  inspect: false,
  handOpen: false,
  openSeat: null,
  zonesFor: null,
  seatChosenTurn: 0,
  seenOnField: new Map(),
  menuSheet: null,
};

let redraw: () => void = () => {};
let drawNow: () => void = () => {};

export function initUi(schedule: () => void, now: () => void): void {
  redraw = schedule;
  drawNow = now;
}

/** Changes the arrangement and draws it at once, for a view transition, which shows no frames until it is drawn. */
export function changeUiNow(change: (state: UiState) => void): void {
  change(ui);
  drawNow();
}

/**
 * Changes the arrangement inside a view transition, which eases what CSS cannot, such as a change of grid areas or of page.
 * `mark` names, once the change is drawn, the attributes the root carries while it runs, which pick the animation in CSS.
 */
export function eased(change: (state: UiState) => void, mark: () => Record<string, string> = () => ({})): void {
  transition(() => changeUiNow(change), () => changeUi(change), mark);
}

/** As eased, for a change held in a component's own state, which Preact draws a moment after it is made rather than at once. */
export function easedLocal(change: () => void, mark: () => Record<string, string>): void {
  transition(async () => {
    change();
    await new Promise(drawn => setTimeout(drawn));
  }, change, mark);
}

function transition(update: () => void | Promise<void>, still: () => void, mark: () => Record<string, string>): void {
  if (!document.startViewTransition || document.documentElement.dataset.motion === 'reduced') {
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

/** A new match: the cards the arrangement was about are gone, but how the player likes the side column stays. */
export function resetMatchUi(): void {
  ui.openPiles.clear();
  ui.openZones.clear();
  ui.zonesMinimised = false;
  ui.zoneSearch = '';
  ui.zoneSort = 'order';
  ui.hoveredStackItem = null;
  ui.stackMenuAt = null;
  ui.stopsOpen = false;
  ui.optionsOpen = false;
  ui.gameMenu = null;
  ui.volumeOpen = false;
  ui.menuSheet = null;
  ui.picker = null;
  ui.hover = null;
  ui.inspect = false;
  ui.handOpen = false;
  ui.openSeat = null;
  ui.zonesFor = null;
  ui.seatChosenTurn = 0;
  ui.seenOnField.clear();
  ui.faceIndex = 0;
}

export function rememberSidePanels(): void {
  storeJson(SIDE_KEY, ui.sidePanels);
}

function storedSidePanels(): Partial<UiState['sidePanels']> {
  return storedJson(SIDE_KEY, {});
}
