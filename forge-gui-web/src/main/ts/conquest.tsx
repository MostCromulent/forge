// Planar Conquest: the saved conquests as a shelf, and inside one, a bar every page shares over the page itself.

import { useEffect, useState } from 'preact/hooks';
import { HeadControls, PageHeader, SetupHead, WAY_NAMES } from './header';
import { changeUi, ui, type ConquestTab } from './ui';
import { conquestIconUrl, imageUrl } from './images';
import { shortDay } from './limited';
import { ConquestMap } from './conquestmap';
import { Party } from './conquestparty';
import { Collection } from './conquestcollection';
import { Reveal, wheelLabels, type Owed } from './conquestreward';
import { setting } from './settings';
import type { Actions } from './actions';
import type { Model } from './model';
import type { ConquestBar, ConquestSave } from './protocol';
import { t, type TextKey } from './text';

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

const DEV_WHEEL = ['BOOSTER', 'DOUBLE_BOOSTER', 'SHARDS', 'DOUBLE_SHARDS', 'PLANESWALK', 'CHAOS'] as const;
const TABS: [ConquestTab, TextKey][] = [['map', 'lblTheMultiverse'], ['aether', 'lblTheAether'], ['party', 'lblCommanders'],
  ['collection', 'lblCollection'], ['planes', 'lblPlaneswalk'], ['stats', 'lblStatistics']];
/** The pages that exist so far. */
const BUILT = new Set<ConquestTab>(['map', 'party', 'collection']);

/** The bar every page of a conquest shares: its name and plane, the tabs, and the two balances. */
function CampaignBar({ bar, actions }: { bar: ConquestBar; actions: Actions }) {
  return (
    <div class="cq-bar">
      <div class="cq-id"><b>{bar.name}</b><span>{bar.plane} · {bar.conquered} / {bar.total}</span></div>
      <nav class="cq-tabs">
        {TABS.map(([tab, name]) => (
          <button key={tab} class="cq-tab" aria-current={tab === ui.conquestTab ? 'page' : undefined} disabled={!BUILT.has(tab)}
            onClick={() => changeUi(u => { u.conquestTab = tab; })}>{t(name)}</button>
        ))}
      </nav>
      <div class="cq-purse">
        {setting('devMode') && (
          // Dev mode: where the next Chaos Wheel stops, so each reward can be looked at
          <select class="cq-dev" aria-label={t('lblWebConquestDevWheel')} title={t('lblWebConquestDevWheel')} onChange={e => actions.devConquestWheel(e.currentTarget.value)}>
            <option value="">{t('lblWebConquestDevWheel')}</option>
            {DEV_WHEEL.map(o => <option key={o} value={o}>{wheelLabels()[o]}</option>)}
          </select>
        )}
        <Purse icon="IMG_AETHER_SHARD" n={bar.shards} label={t('lblAetherShards')} />
        <Purse icon="IMG_PW_BADGE_COMMON" n={bar.emblems} label={t('lblPlaneswalkEmblems')} />
      </div>
    </div>
  );
}

function Campaign({ model, actions }: { model: Model; actions: Actions }) {
  // What a reward being revealed has yet to show. The server's balances already hold it all, so the bar shows less.
  const [owed, setOwed] = useState<Owed>({ shards: 0, emblems: 0 });
  const reward = model.conquestReward;
  useEffect(() => { if (!reward) setOwed({ shards: 0, emblems: 0 }); }, [reward]);
  if (!model.conquestBar || !model.conquestState) return <p class="muted pools-wait">{t('lblWebConquestReading')}</p>;
  const bar = model.conquestBar;
  return <>
    <CampaignBar actions={actions} bar={reward ? { ...bar, shards: bar.shards - owed.shards, emblems: bar.emblems - owed.emblems } : bar} />
    {model.error && <p class="limited-error">{model.error}</p>}
    <div class="cq-main">
      {ui.conquestTab === 'party' ? <Party model={model} actions={actions} />
        : ui.conquestTab === 'collection' ? <Collection model={model} actions={actions} />
        : <ConquestMap actions={actions} state={model.conquestState} />}
    </div>
    {reward && <Reveal key={reward.steps.length + ':' + bar.name} reward={reward} onOwed={setOwed} done={() => actions.conquestClaim()} />}
  </>;
}
