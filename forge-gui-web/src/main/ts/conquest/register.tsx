import { ui } from '../ui';
import { t } from '../text';
import { registerMode } from '../campaign/modes';
import { Conquest } from './conquest';
import type { Model } from '../model';
import type { ConquestAetherState, ConquestOptions, ConquestParty, ConquestPlanes, ConquestSaves, ConquestState } from '../protocol';

export type ConquestTab = 'map' | 'aether' | 'party' | 'collection' | 'planes' | 'stats';

declare module '../model' {
  interface Model {
    conquestSaves: ConquestSaves | null;
    conquestState: ConquestState | null;
    /** The commanders and planeswalkers found. It is set once the Commanders page has requested them. */
    conquestParty: ConquestParty | null;
    /** The choices a new conquest can start with, as far as the form has requested them. */
    conquestOptions: ConquestOptions | null;
    /** The Aether and the planes. Each holds what its page last requested. */
    conquestAether: ConquestAetherState | null;
    conquestPlanes: ConquestPlanes | null;
  }
}

declare module '../ui' {
  interface UiState {
    /** The page of the open conquest that is shown. It is kept while the deck editor replaces the page for a while. */
    conquestTab: ConquestTab;
    conquestOpened: Record<string, boolean>;
    /** Dev mode: where the next Chaos Wheel stops. It is kept here because on a phone it is chosen in the header's menu, which closes after each choice. */
    conquestWheel: string;
  }
}

interface ConquestActions {
  /** Selects a place on the map. The player does not move. */
  conquestSelect(region: number, row: number, col: number): void;
  /** Walks to the selected place. */
  conquestMove(): void;
  /** Starts the battle for the event at the player's current place. */
  conquestBattle(): void;
  /** Dev mode: sets where the next Chaos Wheel stops. */
  devConquestWheel(outcome: string): void;
  /** Requests the choices for a new conquest. These are the planes, a plane's commanders, or a commander's planeswalkers. */
  conquestOptions(plane?: string, commander?: string): void;
  conquestCreate(c: { name: string; plane: string; commander: string; planeswalker: string }): void;
  /** Requests the Aether's cards under these filters. With pull, it spends shards for a card. An empty type requests the filters a visit starts with. */
  conquestAether(q: { colors: string; type: string; rarity: string; cmc: string; pull: boolean }): void;
  conquestPlanes(): void;
  /** Travels to a plane. With unlock, it first spends emblems to unlock the plane. */
  conquestPlaneswalk(plane: string, unlock: boolean): void;
  /** Requests the commanders and planeswalkers found. */
  conquestParty(): void;
  conquestLead(commander: string): void;
  conquestWalker(planeswalker: string): void;
  /** Requests a commander's deck. It arrives in the same message as other deck details. */
  conquestViewDeck(commander: string): void;
  conquestEditDeck(commander: string): void;
}

declare module '../actions' {
  interface Actions extends ConquestActions {}
}

/** An open conquest's page fields when no conquest is open. hello resets them to this when the conquest is left. */
const NO_CONQUEST = { conquestState: null, conquestParty: null, conquestAether: null, conquestPlanes: null } satisfies Partial<Model>;

// A ringed planet
const ICON = <><circle cx="24" cy="24" r="11" /><ellipse cx="24" cy="24" rx="20" ry="6.5" transform="rotate(-18 24 24)" /></>;

Object.assign(ui, { conquestTab: 'map', conquestOpened: {}, conquestWheel: '' });

registerMode({
  mode: 'conquest',
  fields: () => ({ conquestSaves: null, conquestOptions: null, ...NO_CONQUEST }),
  hello: (model, msg) => {
    if (msg.campaign !== 'conquest') {
      Object.assign(model, NO_CONQUEST);
      ui.conquestTab = 'map';
      ui.conquestWheel = '';
    }
  },
  stored: ['conquestSaves', 'conquestState', 'conquestParty', 'conquestOptions', 'conquestAether', 'conquestPlanes'],
  actions: (send, model): ConquestActions => ({
    conquestSelect: (region, row, col) => send({ t: 'conquestSelect', region, row, col }),
    conquestMove: () => send({ t: 'conquestMove' }),
    conquestBattle: () => send({ t: 'conquestBattle' }),
    devConquestWheel: outcome => send({ t: 'devConquestWheel', outcome }),
    conquestOptions: (plane, commander) => send({ t: 'conquestOptions', plane, commander }),
    conquestCreate: c => {
      model.error = null;
      send({ t: 'conquestCreate', ...c });
    },
    // A new visit to the Aether starts from its default filters. The card pulled on the last visit is cleared too
    conquestAether: q => {
      if (!q.type) model.conquestAether = null;
      send({ t: 'conquestAether', ...q });
    },
    conquestPlanes: () => send({ t: 'conquestPlanes' }),
    conquestPlaneswalk: (plane, unlock) => {
      model.error = null;
      send({ t: 'conquestPlaneswalk', plane, unlock });
    },
    conquestParty: () => send({ t: 'conquestParty' }),
    conquestLead: commander => send({ t: 'conquestLead', commander }),
    conquestWalker: planeswalker => send({ t: 'conquestWalker', planeswalker }),
    conquestViewDeck: commander => send({ t: 'conquestViewDeck', commander }),
    conquestEditDeck: commander => send({ t: 'conquestEditDeck', commander }),
  }),
  Page: Conquest,
  tile: {
    icon: ICON, art: 'c:Portal of Sanctuary|M20|1', name: 'lblPlanarConquest', blurb: 'lblWebConquestBlurb',
    resumeLabel: save => t('lblWebCampaignResume', save), order: 1,
  },
});
