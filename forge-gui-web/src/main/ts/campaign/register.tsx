// The campaign modes' part of the page: their model fields, actions and tabs, and the hooks the shared pages call (campaigns.tsx)

import { registerCampaigns } from '../campaigns';
import { Kind } from '../menu';
import { ui } from '../ui';
import { t } from '../text';
import { Conquest } from '../conquest/conquest';
import { Quest } from '../quest/quest';
import { TournamentDrafted } from '../quest/tournamentdrafted';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type {
  CampaignBar, CampaignPrefs, CampaignResult, CampaignStats, ConquestAetherState, ConquestOptions, ConquestParty, ConquestPlanes,
  ConquestSaves, ConquestState, QuestBazaar, QuestChallenges, QuestCreate, QuestDecks, QuestDuels, QuestOptions, QuestSaves, QuestTournaments,
  Reward, Trading,
} from '../protocol';

/** The modes, by the name the server knows each by. */
export type CampaignMode = 'conquest' | 'quest';
export type ConquestTab = 'map' | 'aether' | 'party' | 'collection' | 'planes' | 'stats';
export type QuestTab = 'duels' | 'challenges' | 'tournaments' | 'decks' | 'shop' | 'bazaar' | 'stats';

declare module '../model' {
  interface Model {
    conquestSaves: ConquestSaves | null;
    questSaves: QuestSaves | null;
    questDuels: QuestDuels | null;
    questDecks: QuestDecks | null;
    questChallenges: QuestChallenges | null;
    /** The bazaar with one stall open, once its tab has asked. */
    questBazaar: QuestBazaar | null;
    /** What the new-quest form offers, once it has asked. */
    questOptions: QuestOptions | null;
    questTournaments: QuestTournaments | null;
    campaignBar: CampaignBar | null;
    conquestState: ConquestState | null;
    /** The result of a battle's game, while its match is open; and what a won battle gave, until it has been shown. */
    campaignResult: CampaignResult | null;
    reward: Reward | null;
    /** The commanders and planeswalkers found, once the Commanders page has asked. */
    conquestParty: ConquestParty | null;
    /** The lists a campaign trades between, by size, once its trade page has asked. */
    trading: Trading | null;
    /** What a new conquest may start with, as far as the form has asked. */
    conquestOptions: ConquestOptions | null;
    /** The Aether, the planes, the statistics and the preferences, each as its page last asked for it. */
    conquestAether: ConquestAetherState | null;
    conquestPlanes: ConquestPlanes | null;
    campaignStats: CampaignStats | null;
    campaignPrefs: CampaignPrefs | null;
  }
}

declare module '../ui' {
  interface UiState {
    /** Which page of the open conquest is showing. It outlives the deck editor, which takes the page's place for a while. */
    conquestTab: ConquestTab;
    /** Which page of the open quest is showing. */
    questTab: QuestTab;
    conquestOpened: Record<string, boolean>;
  }
}

declare module '../actions' {
  interface Actions {
    /** Opens a mode's saved games, or with resume, the one played last. */
    campaignOpen(mode: CampaignMode, resume: boolean): void;
    campaignLoad(name: string): void;
    /** Back from an open game to the saved ones, or from those to the start page. */
    campaignLeave(): void;
    campaignRename(name: string, to: string): void;
    campaignDelete(name: string): void;
    /** Selects a place on the map; the player does not move. */
    conquestSelect(region: number, row: number, col: number): void;
    /** Walks to the selected place. */
    conquestMove(): void;
    /** Fights the event the player stands on. */
    conquestBattle(): void;
    /** Says a won battle's reward has been shown. */
    rewardClaim(): void;
    /** Dev mode: where the next Chaos Wheel stops. */
    devConquestWheel(outcome: string): void;
    /** Asks what a new conquest may start with: the planes, a plane's commanders, a commander's planeswalkers. */
    conquestOptions(plane?: string, commander?: string): void;
    conquestCreate(c: { name: string; plane: string; commander: string; planeswalker: string }): void;
    /** Asks what the Aether holds under these filters, or with pull spends the shards for a card. An empty type asks for the filters a visit starts with. */
    conquestAether(q: { colors: string; type: string; rarity: string; cmc: string; pull: boolean }): void;
    conquestPlanes(): void;
    /** Travels to a plane, with unlock spending the emblems to unlock it first. */
    conquestPlaneswalk(plane: string, unlock: boolean): void;
    /** Asks for the statistics of a plane, or of every plane. */
    campaignStats(scope?: string): void;
    campaignPrefs(): void;
    campaignPref(key: string, value: string): void;
    campaignPrefsReset(): void;
    /** Asks for the commanders and planeswalkers found. */
    conquestParty(): void;
    conquestLead(commander: string): void;
    conquestWalker(planeswalker: string): void;
    /** Asks for a commander's deck, which arrives as a deck's details do. */
    conquestViewDeck(commander: string): void;
    conquestEditDeck(commander: string): void;
    /** Asks for the sizes of the lists a campaign trades between. */
    trading(): void;
    /** Trades the picks out of the list of that source: for Conquest, exiles them or brings them back. */
    trade(source: string, picks: { key: string; count: number }[]): void;

    /** Fights a duel of the Duels page, by its place in the list. */
    questDuel(index: number): void;
    /** Chooses the pet for a slot, the plant's being 0; null summons none. */
    questPet(slot: number, name: string | null): void;
    questMatchLength(games: number): void;
    /** Makes an empty deck of a name and opens it in the editor. */
    questDeckNew(name: string): void;
    questDeckRename(deck: string, to: string): void;
    /** Makes a deck the one duels are fought with. */
    questDeckCurrent(deck: string): void;
    /** Asks for a deck's list, which arrives as a deck's details do. */
    questDeckView(deck: string): void;
    questDeckEdit(deck: string): void;
    questDeckDelete(deck: string): void;
    /** Asks what the new-quest form offers. */
    questOptions(): void;
    questChallenge(id: string): void;
    /** Flies the zeppelin, which draws the challenges again once between two matches. */
    questZeppelin(): void;
    /** Opens a stall of the bazaar, or with null the first. */
    questStall(name: string | null): void;
    questBuy(stall: string, item: string): void;
    /** Enters a tournament on offer, which pays its fee and opens its draft. */
    questEnter(title: string): void;
    /** Spends a draft token; the host asks for the format. */
    questToken(): void;
    questTournamentStart(): void;
    questTournamentNext(): void;
    /** Leaves the tournament, collecting its prizes once it has started. */
    questTournamentLeave(): void;
    /** Opens the tournament's deck in the editor. */
    questTournamentDeck(): void;
    /** Travels to another world; the host asks which. */
    questTravel(): void;
    /** Unlocks a set; the host asks which. */
    questUnlock(): void;
    questCreate(c: Omit<QuestCreate, 't'>): void;
  }
}

/** An open campaign's pages, as they stand with none open; hello resets them to this when the campaign is left. */
const NO_CAMPAIGN = { campaignBar: null, reward: null, trading: null, campaignStats: null } satisfies Partial<Model>;
/** The same for an open conquest's own pages. */
const NO_CONQUEST = { conquestState: null, conquestParty: null, conquestAether: null, conquestPlanes: null } satisfies Partial<Model>;
/** The same for an open quest's own pages. */
const NO_QUEST = {
  questSaves: null, questDuels: null, questDecks: null, questChallenges: null, questBazaar: null, questTournaments: null,
} satisfies Partial<Model>;

/** The messages that only fill a campaign's field of the same name. */
const STORED = new Set(['conquestSaves', 'questSaves', 'questDuels', 'questDecks', 'questOptions', 'questChallenges', 'questBazaar', 'questTournaments',
  'campaignBar', 'conquestState', 'campaignResult', 'reward', 'conquestParty', 'trading', 'conquestOptions', 'conquestAether', 'conquestPlanes',
  'campaignStats', 'campaignPrefs']);

// A ringed world for Conquest; a pennant on its staff for Quest
const CONQUEST_ICON = <><circle cx="24" cy="24" r="11" /><ellipse cx="24" cy="24" rx="20" ry="6.5" transform="rotate(-18 24 24)" /></>;
const QUEST_ICON = <><path d="M13 42V7" /><path d="M13 9h22l-6 7 6 7H13" /></>;

Object.assign(ui, { conquestTab: 'map', questTab: 'duels', conquestOpened: {} });

registerCampaigns({
  fields: () => ({ conquestSaves: null, questOptions: null, campaignResult: null, conquestOptions: null, campaignPrefs: null, ...NO_CAMPAIGN, ...NO_CONQUEST, ...NO_QUEST }),

  hello: (model, msg) => {
    // A match's result belongs to its match, and a campaign's pages to the campaign
    if (msg.screen !== 'playing') model.campaignResult = null;
    if (!msg.campaign) Object.assign(model, NO_CAMPAIGN);
    if (msg.campaign !== 'conquest') {
      Object.assign(model, NO_CONQUEST);
      ui.conquestTab = 'map';
    }
    if (msg.campaign !== 'quest') {
      Object.assign(model, NO_QUEST);
      // A match is played from the quest's page, so the tab it was started from is the one returned to
      if (msg.screen !== 'playing') ui.questTab = 'duels';
    }
  },

  message: (model, msg) => {
    if (!STORED.has(msg.t)) return false;
    (model as unknown as Record<string, unknown>)[msg.t] = msg;
    return true;
  },

  actions: (send, model, schedule) => ({
    campaignOpen: (mode, resume) => send({ t: 'campaignOpen', mode, resume }),
    campaignLoad: name => send({ t: 'campaignLoad', name }),
    campaignLeave: () => send({ t: 'campaignLeave' }),
    // What the last one was refused for is no longer what the page should say
    campaignRename: (name, to) => {
      model.error = null;
      send({ t: 'campaignRename', name, to });
    },
    campaignDelete: name => {
      model.error = null;
      send({ t: 'campaignDelete', name });
    },
    conquestSelect: (region, row, col) => send({ t: 'conquestSelect', region, row, col }),
    conquestMove: () => send({ t: 'conquestMove' }),
    conquestBattle: () => send({ t: 'conquestBattle' }),
    // A reward that has been shown leaves the model at once, so its dialog closes without waiting for the server
    rewardClaim: () => {
      model.reward = null;
      send({ t: 'rewardClaim' });
      schedule();
    },
    devConquestWheel: outcome => send({ t: 'devConquestWheel', outcome }),
    questDuel: index => send({ t: 'questDuel', index }),
    questPet: (slot, name) => send({ t: 'questPet', slot, name: name ?? undefined }),
    questMatchLength: games => send({ t: 'questMatchLength', games }),
    questDeckNew: name => send({ t: 'questDeckNew', name }),
    questDeckRename: (deck, to) => send({ t: 'questDeckRename', deck, to }),
    questDeckCurrent: deck => send({ t: 'questDeckCurrent', deck }),
    questDeckView: deck => send({ t: 'questDeckView', deck }),
    questDeckEdit: deck => send({ t: 'questDeckEdit', deck }),
    questDeckDelete: deck => send({ t: 'questDeckDelete', deck }),
    questOptions: () => send({ t: 'questOptions' }),
    questChallenge: id => send({ t: 'questChallenge', id }),
    questZeppelin: () => send({ t: 'questZeppelin' }),
    questStall: name => send({ t: 'questStall', name: name ?? undefined }),
    questBuy: (stall, item) => send({ t: 'questBuy', stall, item }),
    questEnter: title => send({ t: 'questEnter', title }),
    questToken: () => send({ t: 'questToken' }),
    questTournamentStart: () => send({ t: 'questTournamentStart' }),
    questTournamentNext: () => send({ t: 'questTournamentNext' }),
    questTournamentLeave: () => send({ t: 'questTournamentLeave' }),
    questTournamentDeck: () => send({ t: 'questTournamentDeck' }),
    questTravel: () => send({ t: 'questTravel' }),
    questUnlock: () => send({ t: 'questUnlock' }),
    questCreate: c => {
      model.error = null;
      send({ t: 'questCreate', ...c });
    },
    conquestOptions: (plane, commander) => send({ t: 'conquestOptions', plane, commander }),
    conquestCreate: c => {
      model.error = null;
      send({ t: 'conquestCreate', ...c });
    },
    // A visit to the Aether starts from its own filters, and the card the last visit pulled is not this one's
    conquestAether: q => {
      if (!q.type) model.conquestAether = null;
      send({ t: 'conquestAether', ...q });
    },
    conquestPlanes: () => send({ t: 'conquestPlanes' }),
    conquestPlaneswalk: (plane, unlock) => {
      model.error = null;
      send({ t: 'conquestPlaneswalk', plane, unlock });
    },
    campaignStats: scope => send({ t: 'campaignStats', scope }),
    campaignPrefs: () => send({ t: 'campaignPrefs' }),
    campaignPref: (key, value) => send({ t: 'campaignPref', key, value }),
    campaignPrefsReset: () => send({ t: 'campaignPrefsReset' }),
    conquestParty: () => send({ t: 'conquestParty' }),
    conquestLead: commander => send({ t: 'conquestLead', commander }),
    conquestWalker: planeswalker => send({ t: 'conquestWalker', planeswalker }),
    conquestViewDeck: commander => send({ t: 'conquestViewDeck', commander }),
    conquestEditDeck: commander => send({ t: 'conquestEditDeck', commander }),
    trading: () => send({ t: 'trading' }),
    trade: (source, picks) => send({ t: 'trade', source, picks }),
  }),

  Page: ({ model, actions }) => (model.campaign === 'quest' ? <Quest model={model} actions={actions} /> : <Conquest model={model} actions={actions} />),

  Menu: ({ model, actions }) => {
    const resume = (mode: CampaignMode) => model.campaignResume[mode];
    return (
      <>
        <h3 class="chooser-more">{t('lblWebConquestCampaigns')}</h3>
        <div class="chooser-kinds">
          <Kind id="conquest" icon={CONQUEST_ICON} name={t('lblPlanarConquest')} blurb={t('lblWebConquestBlurb')} onClick={() => actions.campaignOpen('conquest', false)}
            resume={resume('conquest') ? () => actions.campaignOpen('conquest', true) : undefined}
            resumeLabel={resume('conquest') ? t('lblWebConquestResume', resume('conquest')) : undefined} />
          <Kind id="quest" icon={QUEST_ICON} name={t('lblQuestMode')} note={t('lblWebMenuUnderConstruction')} blurb={t('lblWebQuestBlurb')}
            onClick={() => actions.campaignOpen('quest', false)}
            resume={resume('quest') ? () => actions.campaignOpen('quest', true) : undefined}
            resumeLabel={resume('quest') ? t('lblWebConquestResume', resume('quest')) : undefined} />
        </div>
      </>
    );
  },

  draft: model => (model.campaign === 'quest' ? { Done: TournamentDrafted, leaveText: 'lblLeaveDraftConfirm' } : null),

  ending: (model, actions: Actions) => {
    if (!model.campaignSave) return null;
    // A campaign's result says which buttons the ending offers, so the panel waits for it
    const result = model.campaignResult;
    if (!result) return { key: null, line: null, buttons: () => {} };
    return {
      key: `${result.won}:${result.matchOver}`,
      line: result.line ?? null,
      buttons: panel => {
        const quit = () => { actions.quitMatch(); actions.leave(); };
        const draw = () => {
          panel.clear();
          const does: Record<string, () => void> = {
            nextGame: () => actions.nextGame(), leave: () => actions.leave(), restart: () => actions.restartGame(), quit,
            // A forfeit loses what was paid to enter, so it is asked first, as desktop asks
            forfeit: () => {
              panel.clear();
              panel.say(t('lblWebQuestForfeitAsk'));
              panel.add(t('lblCancel'), true, draw);
              panel.add(t('lblWebQuestForfeitTournament'), false, quit);
            },
          };
          result.buttons.forEach((b, i) => {
            panel.add(b.label, b.primary, does[b.action]);
            if (i === 0) panel.add(t('lblReconnectViewBattlefield'), false, panel.view);
          });
        };
        draw();
      },
    };
  },
});
