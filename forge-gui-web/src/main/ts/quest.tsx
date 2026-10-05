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
import { Shop } from './questshop';
import { Challenges } from './questchallenges';
import { Bazaar } from './questbazaar';
import { Reveal, type Owed } from './conquestreward';
import { NewQuest } from './questnew';
import type { Actions } from './actions';
import type { Model } from './model';
import type { QuestSave } from './protocol';
import { t, type TextKey } from './text';

/** The two balances' icons, asked for as the mode opens: on a first visit they would wait behind every picture of the duels. */
const BALANCE_ICONS = ['ICO_QUEST_COINSTACK', 'ICO_QUEST_LIFE'];
const TABS: [QuestTab, TextKey][] = [['duels', 'lblDuels'], ['challenges', 'lblChallenges'], ['decks', 'lblQuestDecks'], ['shop', 'lblSpellShop'],
  ['bazaar', 'lblBazaar']];
/** The bazaar is Fantasy mode's only, which a Classic quest's bar shows by having no life. */
const FANTASY_ONLY: QuestTab[] = ['bazaar'];

export function Quest({ model, actions }: { model: Model; actions: Actions }) {
  const open = model.campaignSave !== null;
  useEffect(() => { for (const icon of BALANCE_ICONS) new Image().src = skinIconUrl(icon); }, []);
  // The form that starts a quest takes the shelf's place, and gives it back when left or when its quest opens
  const [creating, setCreating] = useState(false);
  useEffect(() => { if (open) setCreating(false); }, [open]);
  return (
    <div key={open ? 'open' : 'shelf'} class={open ? 'cq-shell' : 'limited-page'}>
      {/* A reward being revealed is gone through to its end: nothing behind it can be reached, by key or by pointer */}
      <div class="cq-under" inert={open && !!model.reward}>
        <PageHeader class="limited-head">
          <div class="head-right">
            <HeadControls />
            <button onClick={() => (creating && !open ? setCreating(false) : actions.campaignLeave())}>{t('lblBack')}</button>
          </div>
        </PageHeader>
      </div>
      {open ? <Campaign model={model} actions={actions} /> : <Shelf model={model} actions={actions} creating={creating} setCreating={setCreating} />}
    </div>
  );
}

/** The saved quests as cards, each with the one thing to do next, after a card that starts a new one. */
function Shelf({ model, actions, creating, setCreating }: { model: Model; actions: Actions; creating: boolean; setCreating: (on: boolean) => void }) {
  const saves = model.questSaves;
  const trail = [
    { label: t('lblWebHeadStart'), go: () => { changeUi(u => { u.menuChoice = null; }); actions.campaignLeave(); } },
    { label: WAY_NAMES.play, go: () => { changeUi(u => { u.menuChoice = 'play'; }); actions.campaignLeave(); } },
    creating ? { label: t('lblQuestMode'), go: () => setCreating(false) } : { label: t('lblQuestMode') },
    ...(creating ? [{ label: t('lblWebQuestNew') }] : []),
  ];
  return <>
    <SetupHead trail={trail} title={creating ? t('lblWebQuestNew') : t('lblWebQuestYours')} />
    {model.error && !creating && <p class="limited-error">{model.error}</p>}
    {creating ? <NewQuest model={model} actions={actions} />
      : !saves ? <p class="muted pools-wait">{t('lblWebQuestReading')}</p>
      : <Saves saves={saves.saves} current={saves.current ?? null} actions={actions} create={() => setCreating(true)} />}
  </>;
}

function Saves({ saves, current, actions, create }: { saves: QuestSave[]; current: string | null; actions: Actions; create: () => void }) {
  return (
    <Cards rows={saves} cls={s => (s.name === current ? 'ev qu-save current' : 'ev qu-save')}
      create={{ title: t('lblWebQuestNew'), line: t('lblWebQuestNewLine'), go: create }}
      rename={{ field: t('lblQuestName'), go: (s, to) => actions.campaignRename(s.name, to) }}
      remove={{ item: t('lblDelete'), ask: s => t('lblAreYouSuerDeleteConquest', s.name), keep: t('lblCancel'), go: s => actions.campaignDelete(s.name) }}
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
  if (!model.campaignBar || !model.questDuels || !model.questDecks || !model.questChallenges) return <p class="muted pools-wait">{t('lblWebQuestReading')}</p>;
  const bar = model.campaignBar;
  const fantasy = bar.balances.some(b => b.icon === 'ICO_QUEST_LIFE');
  return <>
    <CampaignBar bar={bar} tabs={fantasy ? TABS : TABS.filter(([id]) => !FANTASY_ONLY.includes(id))} tab={ui.questTab} setTab={tab => changeUi(u => { u.questTab = tab; })}
      held={reward ? owed : {}} under={!!reward} />
    {model.error && <p class="limited-error">{model.error}</p>}
    <div class="cq-main" inert={!!reward}>
      {ui.questTab === 'decks' ? <Decks page={model.questDecks} model={model} actions={actions} />
        : ui.questTab === 'shop' ? <Shop model={model} actions={actions} />
        : ui.questTab === 'challenges' ? <Challenges page={model.questChallenges} duels={model.questDuels} actions={actions} />
        : ui.questTab === 'bazaar' && fantasy ? <Bazaar model={model} actions={actions} />
        : <Duels page={model.questDuels} actions={actions} />}
    </div>
    {reward && <Reveal key={reward.steps.length + ':' + bar.name} reward={reward} onOwed={setOwed} done={() => actions.rewardClaim()} />}
  </>;
}
