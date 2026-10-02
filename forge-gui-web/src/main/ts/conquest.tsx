// Planar Conquest: the saved conquests as a shelf, and inside one, a bar every page shares over the page itself.

import { useEffect, useState } from 'preact/hooks';
import { usePressOutside } from './hooks';
import { HeadControls, PageHeader, SetupHead, WAY_NAMES } from './header';
import { changeUi, ui, type ConquestTab } from './ui';
import { skinIconUrl } from './images';
import { CampaignBar, Purse } from './campaign';
import { shortDay } from './limited';
import { ConquestMap } from './conquestmap';
import { Party } from './conquestparty';
import { Collection } from './conquestcollection';
import { NewConquest } from './conquestnew';
import { Aether } from './conquestaether';
import { Planes } from './conquestplanes';
import { Stats } from './conqueststats';
import { Prefs } from './conquestprefs';
import { artUrl } from './sleeves';
import { Reveal, wheelLabels, type Owed } from './conquestreward';
import { setting } from './settings';
import type { Actions } from './actions';
import type { Model } from './model';
import type { ConquestSave } from './protocol';
import { t, type TextKey } from './text';

/** The two balances' icons, asked for as the mode opens: on a first visit they would wait behind every picture of the map. */
const BALANCE_ICONS = ['IMG_AETHER_SHARD', 'IMG_PW_BADGE_COMMON'];

export function Conquest({ model, actions }: { model: Model; actions: Actions }) {
  const open = model.campaignSave !== null;
  useEffect(() => { for (const icon of BALANCE_ICONS) new Image().src = skinIconUrl(icon); }, []);
  // The form that starts a conquest takes the shelf's place, and gives it back when left or when its conquest opens
  const [creating, setCreating] = useState(false);
  useEffect(() => { if (open) setCreating(false); }, [open]);
  return (
    <div class={open ? 'cq-shell' : 'limited-page'}>
      {/* A reward being revealed is gone through to its end: nothing behind it can be reached, by key or by pointer */}
      <div class="cq-under" inert={open && !!model.reward}>
        <PageHeader class="limited-head">
          <div class="head-right">
            <HeadControls />
            <button onClick={() => (creating && !open ? setCreating(false) : actions.campaignLeave())}>{t('lblBack')}</button>
          </div>
        </PageHeader>
      </div>
      {open ? <Campaign model={model} actions={actions} />
        : <Shelf model={model} actions={actions} creating={creating} setCreating={setCreating} />}
    </div>
  );
}

/** The saved conquests as cards, each with the one thing to do next, after a card that starts a new one. */
function Shelf({ model, actions, creating, setCreating }: { model: Model; actions: Actions; creating: boolean; setCreating: (on: boolean) => void }) {
  const saves = model.conquestSaves;
  const trail = [
    { label: t('lblWebHeadStart'), go: () => { changeUi(u => { u.menuChoice = null; }); actions.campaignLeave(); } },
    { label: WAY_NAMES.play, go: () => { changeUi(u => { u.menuChoice = 'play'; }); actions.campaignLeave(); } },
    creating ? { label: t('lblPlanarConquest'), go: () => setCreating(false) } : { label: t('lblPlanarConquest') },
    ...(creating ? [{ label: t('lblWebConquestNew') }] : []),
  ];
  return <>
    <SetupHead trail={trail} title={creating ? t('lblWebConquestNew') : t('lblWebConquestYours')} />
    {model.error && !creating && <p class="limited-error">{model.error}</p>}
    {creating ? <NewConquest model={model} actions={actions} />
      : !saves ? <p class="muted pools-wait">{t('lblWebConquestReading')}</p>
      : <Saves saves={saves.saves} current={saves.current ?? null} actions={actions} create={() => setCreating(true)} />}
  </>;
}

function Saves({ saves, current, actions, create }: { saves: ConquestSave[]; current: string | null; actions: Actions; create: () => void }) {
  const [menu, setMenu] = useState<string | null>(null);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  usePressOutside(menu !== null, '.ev-more', () => setMenu(null));
  return (
    <div class="event-shelf">
      <button class="ev new" onClick={create}>
        <span class="plus" aria-hidden="true">+</span>
        <b>{t('lblWebConquestNew')}</b>
      </button>
      {saves.map(s => (
        <article key={s.name} class={s.name === current ? 'ev cq-save current' : 'ev cq-save'}>
          <div class="cq-save-art" style={{ backgroundImage: `url("${artUrl(s.art)}")` }}>
            <img class="cq-medal" alt="" src={artUrl(s.walkerImage)} onError={e => { e.currentTarget.hidden = true; }} />
          </div>
          <div class="ev-top">
            {renaming === s.name
              ? (
                <form class="cq-rename" onSubmit={e => {
                  e.preventDefault();
                  const to = new FormData(e.currentTarget).get('name')?.toString().trim();
                  setRenaming(null);
                  if (to && to !== s.name) actions.campaignRename(s.name, to);
                }}>
                  <input name="name" defaultValue={s.name} maxLength={60} aria-label={t('lblConquestName')} autoFocus
                    onKeyDown={e => { if (e.key === 'Escape') setRenaming(null); }} />
                  <button class="primary" type="submit">{t('lblRename')}</button>
                  <button type="button" onClick={() => setRenaming(null)}>{t('lblCancel')}</button>
                </form>
              )
              : <b>{s.name}</b>}
            <span class="sub">{s.planeswalker} - {s.plane}</span>
          </div>
          <div class="ev-mid">
            <div class="cq-pbar"><i style={{ width: `${s.total ? 100 * s.conquered / s.total : 0}%` }} /></div>
            <span class="ev-line dim">
              <b class="pct">{s.conquered} / {s.total}</b>
              <span class="sp" />
              {t('lblWebNCards', s.cards)}
              <Purse icon="IMG_AETHER_SHARD" n={s.shards} label={t('lblAetherShards')} />
              <Purse icon="IMG_PW_BADGE_COMMON" n={s.emblems} label={t('lblPlaneswalkEmblems')} />
            </span>
          </div>
          <div class="ev-foot">
            {deleting === s.name
              ? <>
                  <span class="ev-ask">{t('lblAreYouSuerDeleteConquest', s.name)}</span>
                  <span class="sp" />
                  <button onClick={() => setDeleting(null)}>{t('lblCancel')}</button>
                  <button class="danger" onClick={() => { setDeleting(null); actions.campaignDelete(s.name); }}>{t('lblDelete')}</button>
                </>
              : <>
                  <button class="primary" onClick={() => actions.campaignLoad(s.name)}>{t('lblPlay')}</button>
                  {s.saved && <span class="ev-ask">{t('lblWebLimitedSaved', shortDay(s.saved))}</span>}
                  <span class="sp" />
                  <span class="ev-more">
                    <button class="more" title={t('lblWebLimitedMore')} aria-label={t('lblWebLimitedMoreFor', s.name)} aria-expanded={menu === s.name}
                      onClick={() => setMenu(menu === s.name ? null : s.name)}>⋯</button>
                    {menu === s.name && (
                      <div class="ev-menu" role="menu">
                        <button role="menuitem" class="plain" onClick={() => { setMenu(null); setRenaming(s.name); }}>{t('lblRename')}</button>
                        <button role="menuitem" onClick={() => { setMenu(null); setDeleting(s.name); }}>{t('lblDelete')}</button>
                      </div>
                    )}
                  </span>
                </>}
          </div>
        </article>
      ))}
    </div>
  );
}

const DEV_WHEEL = ['BOOSTER', 'DOUBLE_BOOSTER', 'SHARDS', 'DOUBLE_SHARDS', 'PLANESWALK', 'CHAOS'] as const;
const TABS: [ConquestTab, TextKey][] = [['map', 'lblTheMultiverse'], ['aether', 'lblTheAether'], ['party', 'lblCommanders'],
  ['collection', 'lblCollection'], ['planes', 'lblPlaneswalk'], ['stats', 'lblStatistics']];

/** The page of the open tab. */
function Page({ tab, model, actions }: { tab: ConquestTab; model: Model; actions: Actions }) {
  switch (tab) {
    case 'aether': return <Aether model={model} actions={actions} />;
    case 'party': return <Party model={model} actions={actions} />;
    case 'collection': return <Collection model={model} actions={actions} />;
    case 'planes': return <Planes model={model} actions={actions} />;
    case 'stats': return <Stats model={model} actions={actions} />;
    default: return <ConquestMap actions={actions} state={model.conquestState!} />;
  }
}

function Campaign({ model, actions }: { model: Model; actions: Actions }) {
  // What a reward being revealed has yet to show. The server's balances already hold it all, so the bar shows less.
  const [owed, setOwed] = useState<Owed>({});
  const reward = model.reward;
  const [prefs, setPrefs] = useState(false);
  // The preferences set the prices every page shows, so the page behind them asks again when they close
  const [priced, setPriced] = useState(0);
  useEffect(() => { if (!reward) setOwed({}); }, [reward]);
  if (!model.campaignBar || !model.conquestState) return <p class="muted pools-wait">{t('lblWebConquestReading')}</p>;
  const bar = model.campaignBar;
  return <>
    <CampaignBar bar={bar} tabs={TABS} tab={ui.conquestTab} setTab={tab => changeUi(u => { u.conquestTab = tab; })}
      held={reward ? owed : {}} prefs={() => setPrefs(true)} under={!!reward}
      extra={setting('devMode') && (
        // Dev mode: where the next Chaos Wheel stops, so each reward can be looked at
        <select class="cq-dev" aria-label={t('lblWebConquestDevWheel')} title={t('lblWebConquestDevWheel')} onChange={e => actions.devConquestWheel(e.currentTarget.value)}>
          <option value="">{t('lblWebConquestDevWheel')}</option>
          {DEV_WHEEL.map(o => <option key={o} value={o}>{wheelLabels()[o]}</option>)}
        </select>
      )} />
    {model.error && <p class="limited-error">{model.error}</p>}
    <div class="cq-main" inert={!!reward}><Page key={priced} tab={ui.conquestTab} model={model} actions={actions} /></div>
    {prefs && <Prefs model={model} actions={actions} close={() => { setPrefs(false); setPriced(priced + 1); }} />}
    {reward && <Reveal key={reward.steps.length + ':' + bar.name} reward={reward} onOwed={setOwed} done={() => actions.rewardClaim()} />}
  </>;
}
