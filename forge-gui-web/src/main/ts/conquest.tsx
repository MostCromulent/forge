// Planar Conquest: the saved conquests as a shelf, and inside one, a bar every page shares over the page itself.

import { HeadControls, PageHeader, SetupHead, WAY_NAMES } from './header';
import { changeUi } from './ui';
import { conquestIconUrl, imageUrl } from './images';
import { shortDay } from './limited';
import { ConquestMap } from './conquestmap';
import type { Actions } from './actions';
import type { Model } from './model';
import type { ConquestBar, ConquestReward, ConquestSave, ConquestStep } from './protocol';
import { t } from './text';

export function Conquest({ model, actions }: { model: Model; actions: Actions }) {
  const open = model.conquest !== null;
  return (
    <div class={open ? 'cq-shell' : 'limited-page'}>
      <PageHeader class="limited-head">
        <div class="head-right">
          <HeadControls />
          <button onClick={() => actions.conquestLeave()}>{t('lblBack')}</button>
        </div>
      </PageHeader>
      {open ? <Campaign model={model} actions={actions} /> : <Shelf model={model} actions={actions} />}
    </div>
  );
}

/** The saved conquests as cards, each with the one thing to do next. */
function Shelf({ model, actions }: { model: Model; actions: Actions }) {
  const saves = model.conquestSaves?.saves;
  const trail = [
    { label: t('lblWebLimitedStart'), go: () => { changeUi(u => { u.menuChoice = null; }); actions.conquestLeave(); } },
    { label: WAY_NAMES.play, go: () => { changeUi(u => { u.menuChoice = 'play'; }); actions.conquestLeave(); } },
    { label: t('lblPlanarConquest') },
  ];
  return <>
    <SetupHead trail={trail} title={t('lblWebConquestYours')} />
    {model.error && <p class="limited-error">{model.error}</p>}
    {!saves ? <p class="muted pools-wait">{t('lblWebConquestReading')}</p>
      : !saves.length ? <p class="muted pools-wait">{t('lblWebConquestNone')}</p>
      : <div class="event-shelf">{saves.map(s => <SaveCard key={s.name} save={s} actions={actions} />)}</div>}
  </>;
}

function SaveCard({ save, actions }: { save: ConquestSave; actions: Actions }) {
  return (
    <article class="ev cq-save">
      <div class="ev-top">
        <b>{save.name}</b>
        {save.saved && <span class="sub">{t('lblWebConquestSaved', shortDay(save.saved))}</span>}
      </div>
      <div class="ev-mid">
        <span class="ev-line"><img class="cq-face" alt="" src={imageUrl(save.walkerImage)} />{save.planeswalker} - {save.plane}</span>
        <span class="ev-line dim">
          {save.progress} · {t('lblWebConquestCards', save.cards)}
          <Purse icon="IMG_AETHER_SHARD" n={save.shards} label={t('lblAetherShards')} />
          <Purse icon="IMG_PW_BADGE_COMMON" n={save.emblems} label={t('lblPlaneswalkEmblems')} />
        </span>
      </div>
      <div class="ev-foot">
        <button class="primary" onClick={() => actions.conquestLoad(save.name)}>{t('lblWebLimitedPlay')}</button>
      </div>
    </article>
  );
}

/** A balance: its icon, its number, and its name for a reader that cannot see the icon. */
export function Purse({ icon, n, label }: { icon: string; n: number; label: string }) {
  return <span class="cq-coin" title={label}><img alt={label} src={conquestIconUrl(icon)} /><b>{n.toLocaleString('en-GB')}</b></span>;
}

const TABS = ['lblTheMultiverse', 'lblTheAether', 'lblCommanders', 'lblCollection', 'lblPlaneswalk', 'lblStatistics'] as const;

/** The bar every page of a conquest shares: its name and plane, the tabs, and the two balances. */
function CampaignBar({ bar }: { bar: ConquestBar }) {
  return (
    <div class="cq-bar">
      <div class="cq-id"><b>{bar.name}</b><span>{bar.plane} · {bar.conquered} / {bar.total}</span></div>
      <nav class="cq-tabs">
        {TABS.map((key, i) => <button key={key} class="cq-tab" aria-current={i === 0 ? 'page' : undefined} disabled={i !== 0}>{t(key)}</button>)}
      </nav>
      <div class="cq-purse">
        <Purse icon="IMG_AETHER_SHARD" n={bar.shards} label={t('lblAetherShards')} />
        <Purse icon="IMG_PW_BADGE_COMMON" n={bar.emblems} label={t('lblPlaneswalkEmblems')} />
      </div>
    </div>
  );
}

function Campaign({ model, actions }: { model: Model; actions: Actions }) {
  if (!model.conquestBar || !model.conquestState) return <p class="muted pools-wait">{t('lblWebConquestReading')}</p>;
  return <>
    <CampaignBar bar={model.conquestBar} />
    {model.error && <p class="limited-error">{model.error}</p>}
    <div class="cq-main"><ConquestMap actions={actions} state={model.conquestState} /></div>
    {model.conquestReward && <RewardList reward={model.conquestReward} done={() => actions.conquestClaim()} />}
  </>;
}

/** What one step of a reward says, in mobile's own words; nothing for the wheel, whose next step says what it gave. */
function stepLine(step: ConquestStep): string | null {
  switch (step.kind) {
    case 'CONQUER_EMBLEMS': return t('lblWebConquestFirstConquest');
    case 'EMBLEMS': return `${t('lblReceivedBonusPlaneswalkEmblems')}: ${step.amount}`;
    case 'BOOSTER': return `${t('lblReceivedBoosterPack')}: ${t('lblWebConquestNewCards', (step.cards ?? []).filter(c => !c.shards).length)}`;
    case 'DUPLICATE_SHARDS': return `${t('lblReceivedAetherShardsForDuplicateCards')}: ${step.amount}`;
    case 'SHARDS': return `${t('lblReceivedAetherShards')}: ${step.amount}`;
    case 'ALL_PLANES_UNLOCKED': return t('lblAllPlanesUnlocked');
    case 'CHAOS_BATTLE': return t('lblChaosApproaching');
    default: return null;
  }
}

/** What a won battle gave, as a plain list. It is already in the save; this only says so. */
function RewardList({ reward, done }: { reward: ConquestReward; done: () => void }) {
  return (
    <div class="backdrop">
      <div class="dialog cq-reward">
        <h3>{t('lblWebConquestReward')}</h3>
        <ul>{reward.steps.map(stepLine).filter(Boolean).map((line, i) => <li key={i}>{line}</li>)}</ul>
        <div class="buttons"><button class="primary" onClick={done}>{t('lblOK')}</button></div>
      </div>
    </div>
  );
}
