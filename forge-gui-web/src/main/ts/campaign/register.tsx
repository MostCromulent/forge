import { registerCampaigns } from '../campaigns';
import { Kind } from '../menu';
import { t } from '../text';
import { modes } from './modes';
import type { Model } from '../model';
import type { CampaignBar, CampaignPrefs, CampaignResult, CampaignStats, Reward, Trading } from '../protocol';

declare module '../model' {
  interface Model {
    campaignBar: CampaignBar | null;
    /** The result of a match's game while its match is open. reward holds what a match gave until it has been shown. */
    campaignResult: CampaignResult | null;
    reward: Reward | null;
    /** The sizes of the lists a campaign trades between. It is set once the trade page has requested them. */
    trading: Trading | null;
    /** The statistics and the preferences. Each holds what its page last requested. */
    campaignStats: CampaignStats | null;
    campaignPrefs: CampaignPrefs | null;
  }
}

interface CampaignActions {
  /** Opens a mode's saved games. With resume, it opens the save played last. */
  campaignOpen(mode: string, resume: boolean): void;
  campaignLoad(name: string): void;
  /** Goes back from an open save to the saved games, or from the saved games to the start page. */
  campaignLeave(): void;
  campaignRename(name: string, to: string): void;
  campaignDelete(name: string): void;
  /** Tells the server that a match's reward has been shown. */
  rewardClaim(): void;
  /** Requests the campaign's statistics. No scope requests all of them. */
  campaignStats(scope?: string): void;
  campaignPrefs(): void;
  campaignPref(key: string, value: string): void;
  campaignPrefsReset(): void;
  /** Requests the sizes of the lists a campaign trades between. */
  trading(): void;
  /** Trades the picks out of the list for that source. */
  trade(source: string, picks: { key: string; count: number }[]): void;
}

declare module '../actions' {
  interface Actions extends CampaignActions {}
}

/** An open campaign's page fields when no campaign is open. hello resets them to this when the campaign is left. */
const NO_CAMPAIGN = { campaignBar: null, reward: null, trading: null, campaignStats: null } satisfies Partial<Model>;

/** The messages that are only stored in the campaign field of the same name. */
const STORED = ['campaignBar', 'campaignResult', 'reward', 'trading', 'campaignStats', 'campaignPrefs'];

const openMode = (model: Model) => modes().find(m => m.mode === model.campaign);

registerCampaigns({
  fields: () => Object.assign({ campaignResult: null, campaignPrefs: null, ...NO_CAMPAIGN }, ...modes().map(m => m.fields())),

  hello: (model, msg) => {
    // A match's result is cleared when its match ends. A campaign's pages are cleared when the campaign is left
    if (msg.screen !== 'playing') model.campaignResult = null;
    if (!msg.campaign) Object.assign(model, NO_CAMPAIGN);
    for (const m of modes()) m.hello(model, msg);
  },

  message: (model, msg) => {
    if (!STORED.includes(msg.t) && !modes().some(m => m.stored.includes(msg.t))) return false;
    (model as unknown as Record<string, unknown>)[msg.t] = msg;
    return true;
  },

  actions: (send, model, schedule) => {
    const shared: CampaignActions = {
      campaignOpen: (mode, resume) => send({ t: 'campaignOpen', mode, resume }),
      campaignLoad: name => send({ t: 'campaignLoad', name }),
      campaignLeave: () => send({ t: 'campaignLeave' }),
      // The error from the last refused request no longer applies, so it is cleared
      campaignRename: (name, to) => {
        model.error = null;
        send({ t: 'campaignRename', name, to });
      },
      campaignDelete: name => {
        model.error = null;
        send({ t: 'campaignDelete', name });
      },
      // A shown reward is removed from the model at once, so its dialog closes without waiting for the server
      rewardClaim: () => {
        model.reward = null;
        send({ t: 'rewardClaim' });
        schedule();
      },
      campaignStats: scope => send({ t: 'campaignStats', scope }),
      campaignPrefs: () => send({ t: 'campaignPrefs' }),
      campaignPref: (key, value) => send({ t: 'campaignPref', key, value }),
      campaignPrefsReset: () => send({ t: 'campaignPrefsReset' }),
      trading: () => send({ t: 'trading' }),
      trade: (source, picks) => send({ t: 'trade', source, picks }),
    };
    return Object.assign(shared, ...modes().map(m => m.actions(send, model, schedule)));
  },

  Page: ({ model, actions }) => {
    const Page = openMode(model)?.Page;
    return Page ? <Page model={model} actions={actions} /> : null;
  },

  Menu: ({ model, actions }) => (
    <>
      <h3 class="chooser-more">{t('lblWebCampaigns')}</h3>
      <div class="chooser-kinds">
        {[...modes()].sort((a, b) => a.tile.order - b.tile.order).map(({ mode, tile }) => {
          const save = model.campaignResume[mode];
          return (
            <Kind key={mode} id={mode} icon={tile.icon} artKey={tile.art} name={t(tile.name)} blurb={t(tile.blurb)}
              onClick={() => actions.campaignOpen(mode, false)}
              resume={save ? () => actions.campaignOpen(mode, true) : undefined}
              resumeLabel={save ? tile.resumeLabel(save) : undefined} />
          );
        })}
      </div>
    </>
  ),

  draft: model => openMode(model)?.draft?.(model) ?? null,

  ending: (model, actions) => {
    if (!model.campaignSave) return null;
    // The campaign's result sets which buttons the ending offers, so the panel waits for it
    const result = model.campaignResult;
    if (!result) return { key: null, line: null, buttons: () => {} };
    return {
      key: `${result.won}:${result.matchOver}`,
      line: result.line ?? null,
      buttons: panel => {
        const draw = () => {
          panel.clear();
          result.buttons.forEach((b, i) => {
            panel.add(b.label, b.primary, does[b.action]);
            if (i === 0) panel.add(t('lblReconnectViewBattlefield'), false, panel.view);
          });
        };
        // hello names no campaign during a match, so every mode adds its actions
        const does: Record<string, () => void> = Object.assign({
          nextGame: () => actions.nextGame(), leave: () => actions.leave(), restart: () => actions.restartGame(),
          quit: () => { actions.quitMatch(); actions.leave(); },
        }, ...modes().map(m => m.ending?.(model, actions, result, panel, draw)));
        draw();
      },
    };
  },
});
