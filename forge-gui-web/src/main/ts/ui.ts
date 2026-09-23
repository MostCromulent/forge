// How the player has arranged the table: what is open, collapsed or pointed at. None of it is the game's, and none
// of it goes to the server. It lives here, in one place, so any renderer reads the same arrangement and a new match
// clears it at once.

/** The card or player the pointer is over, whose details the zoom panel shows. A card's src is its image, empty
 *  when the viewer may not see it. */
export type Hover = { card: number | null; src: string } | { player: number };

export interface UiState {
  /** Battlefield piles the player has laid out card by card, by the pile's signature. */
  openPiles: Set<string>;
  /** Zone panels the player has opened by clicking a tile, as `${playerKey}/${zone}`. */
  openZones: Set<string>;
  stackCollapsed: boolean;
  /** The stack item under the pointer, whose targets get arrows. */
  hoveredStackItem: number | null;
  /** Where a right-click on a stack item happened, until the server's answer opens the menu there. */
  stackMenuAt: { key: number; x: number; y: number } | null;
  /** The grid of phase stops is open. */
  stopsOpen: boolean;
  /** The options dialog is open. */
  optionsOpen: boolean;
  hover: Hover | null;
  /** Which face of the hovered card the zoom panel shows. */
  faceIndex: number;
  /** The side column's panels. Kept in the browser across sessions. */
  sidePanels: { log: boolean; chat: boolean };
}

const SIDE_KEY = 'forge.sidePanels';

export const ui: UiState = {
  openPiles: new Set(),
  openZones: new Set(),
  stackCollapsed: false,
  hoveredStackItem: null,
  stackMenuAt: null,
  stopsOpen: false,
  optionsOpen: false,
  hover: null,
  faceIndex: 0,
  sidePanels: { log: true, chat: false, ...storedSidePanels() },
};

let redraw: () => void = () => {};

export function initUi(schedule: () => void): void {
  redraw = schedule;
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
  ui.hoveredStackItem = null;
  ui.stackMenuAt = null;
  ui.stopsOpen = false;
  ui.optionsOpen = false;
  ui.hover = null;
  ui.faceIndex = 0;
}

export function rememberSidePanels(): void {
  try {
    localStorage.setItem(SIDE_KEY, JSON.stringify(ui.sidePanels));
  } catch {
    // Storage can be unavailable; the choice then lasts until reload
  }
}

function storedSidePanels(): Partial<UiState['sidePanels']> {
  try {
    return JSON.parse(localStorage.getItem(SIDE_KEY) ?? 'null') ?? {};
  } catch {
    // Storage can be unavailable or hold something else; the defaults then last until reload
    return {};
  }
}
