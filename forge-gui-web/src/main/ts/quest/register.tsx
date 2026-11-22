import { ui } from '../ui';
import { t } from '../text';
import { registerMode } from '../campaign/modes';
import { Quest } from './quest';
import { TournamentDrafted } from './tournamentdrafted';
import type { Model } from '../model';
import type { QuestBazaar, QuestChallenges, QuestCreate, QuestDecks, QuestDuels, QuestOptions, QuestSaves, QuestTournaments } from '../protocol';

export type QuestTab = 'duels' | 'challenges' | 'tournaments' | 'decks' | 'shop' | 'bazaar' | 'stats';

declare module '../model' {
  interface Model {
    questSaves: QuestSaves | null;
    questDuels: QuestDuels | null;
    questDecks: QuestDecks | null;
    questChallenges: QuestChallenges | null;
    /** The bazaar with one stall open. It is set once the Bazaar tab has requested it. */
    questBazaar: QuestBazaar | null;
    /** The choices the new-quest form offers. It is set once the form has requested them. */
    questOptions: QuestOptions | null;
    questTournaments: QuestTournaments | null;
  }
}

declare module '../ui' {
  interface UiState {
    questTab: QuestTab;
  }
}

interface QuestActions {
  /** Starts a duel from the Duels page, by its index in the list. */
  questDuel(index: number): void;
  /** Chooses the pet for a slot. The plant uses slot 0. null chooses no pet. */
  questPet(slot: number, name: string | null): void;
  questMatchLength(games: number): void;
  /** Creates an empty deck with the given name and opens it in the editor. */
  questDeckNew(name: string): void;
  questDeckRename(deck: string, to: string): void;
  /** Makes a deck the one used for duels. */
  questDeckCurrent(deck: string): void;
  /** Requests a deck's list. It arrives in the same message as other deck details. */
  questDeckView(deck: string): void;
  questDeckEdit(deck: string): void;
  questDeckDelete(deck: string): void;
  /** Requests the choices the new-quest form offers. */
  questOptions(): void;
  questChallenge(id: string): void;
  /** Flies the zeppelin. It redraws the challenges, and can be used once between two matches. */
  questZeppelin(): void;
  /** Opens a stall of the bazaar. null opens the first stall. */
  questStall(name: string | null): void;
  questBuy(stall: string, item: string): void;
  /** Enters a tournament on offer. This pays its fee and opens its draft. */
  questEnter(title: string): void;
  /** Spends a draft token. The host then asks for the format. */
  questToken(): void;
  questTournamentStart(): void;
  questTournamentNext(): void;
  /** Leaves the tournament. If it has started, its prizes are collected. */
  questTournamentLeave(): void;
  /** Opens the tournament's deck in the editor. */
  questTournamentDeck(): void;
  /** Travels to another world. The host asks which one. */
  questTravel(): void;
  /** Unlocks a set. The host asks which one. */
  questUnlock(): void;
  questCreate(c: Omit<QuestCreate, 't'>): void;
}

declare module '../actions' {
  interface Actions extends QuestActions {}
}

/** An open quest's page fields when no quest is open. hello resets them to this when the quest is left. */
const NO_QUEST = {
  questSaves: null, questDuels: null, questDecks: null, questChallenges: null, questBazaar: null, questTournaments: null,
} satisfies Partial<Model>;

// A pennant on a staff
const ICON = <><path d="M13 42V7" /><path d="M13 9h22l-6 7 6 7H13" /></>;

Object.assign(ui, { questTab: 'duels' });

registerMode({
  mode: 'quest',
  fields: () => ({ questOptions: null, ...NO_QUEST }),
  hello: (model, msg) => {
    if (msg.campaign !== 'quest') {
      Object.assign(model, NO_QUEST);
      // A match is played from the quest's page, so the player returns to the tab the match was started from
      if (msg.screen !== 'playing') ui.questTab = 'duels';
    }
  },
  stored: ['questSaves', 'questDuels', 'questDecks', 'questOptions', 'questChallenges', 'questBazaar', 'questTournaments'],
  actions: (send, model): QuestActions => ({
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
  }),
  Page: Quest,
  tile: {
    icon: ICON, art: "c:Explorer's Scope|CMM|1", name: 'lblQuestMode', blurb: 'lblWebQuestBlurb',
    resumeLabel: save => t('lblWebCampaignResume', save), order: 2,
  },
  draft: () => ({ Done: TournamentDrafted, leaveText: 'lblLeaveDraftConfirm' }),
  ending: (_model, actions, _result, panel, redraw) => ({
    // A forfeit loses the entry fee, so the player must confirm it first, as on desktop
    forfeit: () => {
      panel.clear();
      panel.say(t('lblWebQuestForfeitAsk'));
      panel.add(t('lblCancel'), true, redraw);
      panel.add(t('lblWebQuestForfeitTournament'), false, () => { actions.quitMatch(); actions.leave(); });
    },
  }),
});
