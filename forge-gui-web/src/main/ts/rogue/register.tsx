import { t } from '../text';
import { ui } from '../ui';
import { registerMode } from '../campaign/modes';
import { RogueHub } from './rogue';
import type { Model } from '../model';
import type { RogueAnnounce, RogueChoice, RogueHome, RogueMap, RogueQuestion, RogueRoster, RogueRunDeck, RogueTraits } from '../protocol';

export type RogueTab = 'runs' | 'aether' | 'codex' | 'history';

declare module '../model' {
  interface Model {
    rogueHome: RogueHome | null;
    rogueRoster: RogueRoster | null;
    rogueMap: RogueMap | null;
    rogueRunDeck: RogueRunDeck | null;
    rogueTraits: RogueTraits | null;
    /** The server's latest answer about a custom run before it starts. */
    rogueChoice: RogueChoice | null;
    /** The question the flow waits on. It is cleared here as soon as it is answered. */
    rogueQuestion: RogueQuestion | null;
    /** What was won or unlocked and not yet shown, oldest first. */
    rogueAnnounced: RogueAnnounce[];
  }
}

declare module '../ui' {
  interface UiState {
    rogueTab: RogueTab;
    /** Start New Run was pressed over an unfinished run, so Runs shows the commanders instead of the run. */
    rogueNewRun: boolean;
    /** The run's page that opens next, such as its deck after Deck on Runs. */
    rogueRunTab: string | null;
    /** The question whose scene View Map folded, so the page beneath can be read. */
    rogueFolded: number | null;
    /** The run's deck is open, read only, over a decision. */
    rogueDeckView: boolean;
    /** The map node whose panel is shown in place of the current node's, such as a plane ahead read before it can be entered. */
    rogueViewing: number | null;
    /** The map is shown whole, fitted to the page. */
    rogueWhole: boolean;
    /** The phone's sheet of the plane shown is open. */
    rogueSheet: boolean;
    /** The run's traits are shown in place of its map, or of the decision drawn over the map. */
    rogueTraits: boolean;
  }
}

interface RogueActions {
  rogueSelect(snapshot: number, node: number): void;
  rogueEnter(snapshot: number, node: number): void;
  rogueReroll(snapshot: number, node: number): void;
  rogueAbandon(snapshot: number): void;
  /** Opens the run's deck in the deck editor. */
  rogueEditDeck(snapshot: number): void;
  /** deck is empty when a custom run generates its commander. */
  rogueBegin(snapshot: number, deck: string, custom: boolean, modifications: string[], descension: number): void;
  rogueStartDeck(deck: string): void;
  /** Asks about a custom run, after ticking or unticking toggle when it is set. */
  rogueChoose(deck: string, modifications: string[], descension: number, toggle?: string): void;
  rogueAnswer(id: number, value: unknown): void;
  /** The oldest announcement has been shown. */
  rogueAnnounced(): void;
}

declare module '../actions' {
  interface Actions extends RogueActions {}
}

/** The pages' fields when Rogue is not open. Announcements not yet shown wait for the next visit. */
const NO_ROGUE = { rogueHome: null, rogueRoster: null, rogueMap: null, rogueRunDeck: null, rogueTraits: null, rogueChoice: null, rogueQuestion: null } satisfies Partial<Model>;

// A winding trail between two points
const ICON = <><circle cx="12" cy="37" r="4" /><circle cx="36" cy="11" r="4" /><path d="M16 37h10a7 7 0 0 0 0-14h-4a7 7 0 0 1 0-12h10" /></>;

Object.assign(ui, { rogueTab: 'runs', rogueNewRun: false, rogueRunTab: null, rogueFolded: null, rogueDeckView: false, rogueViewing: null, rogueWhole: false, rogueSheet: false, rogueTraits: false });

registerMode({
  mode: 'rogue',
  fields: () => ({ ...NO_ROGUE, rogueAnnounced: [] }),
  hello: (model, msg) => {
    if (msg.campaign !== 'rogue') {
      Object.assign(model, NO_ROGUE);
      Object.assign(ui, { rogueTab: 'runs', rogueNewRun: false, rogueRunTab: null, rogueFolded: null, rogueDeckView: false, rogueViewing: null, rogueWhole: false, rogueSheet: false, rogueTraits: false });
    }
  },
  stored: ['rogueHome', 'rogueRoster', 'rogueMap', 'rogueRunDeck', 'rogueTraits', 'rogueChoice', 'rogueQuestion'],
  message: (model, msg) => {
    if (msg.t !== 'rogueAnnounce') return false;
    model.rogueAnnounced = [...model.rogueAnnounced, msg];
    return true;
  },
  actions: (send, model, schedule): RogueActions => ({
    rogueSelect: (snapshot, node) => send({ t: 'rogueSelect', snapshot, node }),
    rogueEnter: (snapshot, node) => send({ t: 'rogueEnter', snapshot, node }),
    rogueReroll: (snapshot, node) => send({ t: 'rogueReroll', snapshot, node }),
    rogueAbandon: snapshot => send({ t: 'rogueAbandon', snapshot }),
    rogueEditDeck: snapshot => send({ t: 'rogueEditDeck', snapshot }),
    rogueBegin: (snapshot, deck, custom, modifications, descension) => send({ t: 'rogueBegin', snapshot, deck, custom, modifications, descension }),
    rogueStartDeck: deck => send({ t: 'rogueStartDeck', deck }),
    rogueChoose: (deck, modifications, descension, toggle) => send({ t: 'rogueChoose', deck, modifications, descension, toggle }),
    rogueAnswer: (id, value) => {
      model.rogueQuestion = null;
      ui.rogueDeckView = false;
      send({ t: 'rogueAnswer', id, value });
      schedule();
    },
    rogueAnnounced: () => {
      model.rogueAnnounced = model.rogueAnnounced.slice(1);
      schedule();
    },
  }),
  Page: RogueHub,
  tile: {
    icon: ICON, art: 'c:Jund|PC2|1', name: 'lblRogueCommander', blurb: 'lblRogueCommanderDesc',
    resumeLabel: () => t('lblRogueContinueRun'), order: 3,
  },
});
