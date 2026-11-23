import { t } from '../text';
import { ui } from '../ui';
import { registerMode } from '../campaign/modes';
import { RogueHub } from './rogue';
import type { Model } from '../model';
import type { RogueAnnounce, RogueHome, RogueQuestion } from '../protocol';

export type RogueTab = 'runs' | 'aether' | 'codex' | 'history';

declare module '../model' {
  interface Model {
    rogueHome: RogueHome | null;
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
  }
}

interface RogueActions {
  rogueAbandon(snapshot: number): void;
  rogueAnswer(id: number, value: unknown): void;
  /** The oldest announcement has been shown. */
  rogueAnnounced(): void;
}

declare module '../actions' {
  interface Actions extends RogueActions {}
}

/** The pages' fields when Rogue is not open. Announcements not yet shown wait for the next visit. */
const NO_ROGUE = { rogueHome: null, rogueQuestion: null } satisfies Partial<Model>;

// A winding trail between two points
const ICON = <><circle cx="12" cy="37" r="4" /><circle cx="36" cy="11" r="4" /><path d="M16 37h10a7 7 0 0 0 0-14h-4a7 7 0 0 1 0-12h10" /></>;

Object.assign(ui, { rogueTab: 'runs', rogueNewRun: false, rogueRunTab: null });

registerMode({
  mode: 'rogue',
  fields: () => ({ ...NO_ROGUE, rogueAnnounced: [] }),
  hello: (model, msg) => {
    if (msg.campaign !== 'rogue') {
      Object.assign(model, NO_ROGUE);
      Object.assign(ui, { rogueTab: 'runs', rogueNewRun: false, rogueRunTab: null });
    }
  },
  stored: ['rogueHome', 'rogueQuestion'],
  message: (model, msg) => {
    if (msg.t !== 'rogueAnnounce') return false;
    model.rogueAnnounced = [...model.rogueAnnounced, msg];
    return true;
  },
  actions: (send, model, schedule): RogueActions => ({
    rogueAbandon: snapshot => send({ t: 'rogueAbandon', snapshot }),
    rogueAnswer: (id, value) => {
      model.rogueQuestion = null;
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
