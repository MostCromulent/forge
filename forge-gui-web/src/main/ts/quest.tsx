// Quest: the saved quests as a shelf, and inside one, the bar every page shares over the page itself.

import { useEffect, useState } from 'preact/hooks';
import { Shelf as Cards } from './shelf';
import { HeadControls, PageHeader, SetupHead, WAY_NAMES } from './header';
import { changeUi, ui, type QuestTab } from './ui';
import { skinIconUrl } from './images';
import { CampaignBar, Purse } from './campaign';
import { shortDay } from './limited';
import { Duels } from './questduels';
import { Decks } from './questdecks';
import { Reveal, type Owed } from './conquestreward';
import type { Actions } from './actions';
import type { Model } from './model';
import type { QuestSave } from './protocol';
import { t, type TextKey } from './text';

/** The two balances' icons, asked for as the mode opens: on a first visit they would wait behind every picture of the duels. */
const BALANCE_ICONS = ['ICO_QUEST_COINSTACK', 'ICO_QUEST_LIFE'];
const TABS: [QuestTab, TextKey][] = [['duels', 'lblDuels'], ['decks', 'lblQuestDecks']];

export function Quest({ model, actions }: { model: Model; actions: Actions }) {
  const open = model.campaignSave !== null;
  useEffect(() => { for (const icon of BALANCE_ICONS) new Image().src = skinIconUrl(icon); }, []);
  return (
    <div key={open ? 'open' : 'shelf'} class={open ? 'cq-shell' : 'limited-page'}>
      {/* A reward being revealed is gone through to its end: nothing behind it can be reached, by key or by pointer */}
      <div class="cq-under" inert={open && !!model.reward}>
        <PageHeader class="limited-head">
          <div class="head-right">
            <HeadControls />
            <button onClick={() => actions.campaignLeave()}>{t('lblBack')}</button>
          </div>
        </PageHeader>
      </div>
      {open ? <Campaign model={model} actions={actions} /> : <Shelf model={model} actions={actions} />}
    </div>
  );
}

/** The saved quests as cards, each with Play. Starting, renaming and deleting a quest come with the new-quest form. */
function Shelf({ model, actions }: { model: Model; actions: Actions }) {
  const saves = model.questSaves;
  const trail = [
    { label: t('lblWebHeadStart'), go: () => { changeUi(u => { u.menuChoice = null; }); actions.campaignLeave(); } },
    { label: WAY_NAMES.play, go: () => { changeUi(u => { u.menuChoice = 'play'; }); actions.campaignLeave(); } },
    { label: t('lblQuestMode') },
  ];
  return <>
    <SetupHead trail={trail} title={t('lblWebQuestYours')} />
    {model.error && <p class="limited-error">{model.error}</p>}
    {!saves ? <p class="muted pools-wait">{t('lblWebQuestReading')}</p>
      : <Saves saves={saves.saves} current={saves.current ?? null} actions={actions} />}
  </>;
}

function Saves({ saves, current, actions }: { saves: QuestSave[]; current: string | null; actions: Actions }) {
  return (
    <Cards rows={saves} cls={s => (s.name === current ? 'ev qu-save current' : 'ev qu-save')}
      sub={s => <span class="sub">{s.difficulty ? `${s.mode} · ${s.difficulty}` : s.mode}</span>}
      mid={s => <>
        <span class="ev-line">{s.rank}</span>
        <span class="ev-line dim">
          <b class="pct">{t('lblWebQuestRecord', s.wins, s.losses)}</b>
          <span class="sp" />
          {t('lblWebNCards', s.cards)}
          <Purse icon="ICO_QUEST_COINSTACK" n={s.credits} label={t('lblCredits')} />
        </span>
        <span class="ev-line dim">{s.world}</span>
      </>}
      foot={s => <>
        <button class="primary" onClick={() => actions.campaignLoad(s.name)}>{t('lblPlay')}</button>
        {s.saved && <span class="ev-ask">{t('lblWebLimitedSaved', shortDay(s.saved))}</span>}
      </>} />
  );
}

function Campaign({ model, actions }: { model: Model; actions: Actions }) {
  // What a reward being revealed has yet to show. The server's balances already hold it all, so the bar shows less.
  const [owed, setOwed] = useState<Owed>({});
  const reward = model.reward;
  useEffect(() => { if (!reward) setOwed({}); }, [reward]);
  if (!model.campaignBar || !model.questDuels || !model.questDecks) return <p class="muted pools-wait">{t('lblWebQuestReading')}</p>;
  const bar = model.campaignBar;
  return <>
    <CampaignBar bar={bar} tabs={TABS} tab={ui.questTab} setTab={tab => changeUi(u => { u.questTab = tab; })}
      held={reward ? owed : {}} under={!!reward} />
    {model.error && <p class="limited-error">{model.error}</p>}
    <div class="cq-main" inert={!!reward}>
      {ui.questTab === 'decks' ? <Decks page={model.questDecks} model={model} actions={actions} />
        : <Duels page={model.questDuels} actions={actions} />}
    </div>
    {reward && <Reveal key={reward.steps.length + ':' + bar.name} reward={reward} onOwed={setOwed} done={() => actions.rewardClaim()} />}
  </>;
}
