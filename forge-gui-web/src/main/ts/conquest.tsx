// Planar Conquest: the saved conquests as a shelf, and inside one, a bar every page shares over the page itself.

import { useEffect, useState } from 'preact/hooks';
import { HeadControls, PageHeader, SetupHead, WAY_NAMES } from './header';
import { changeUi, ui, type ConquestTab } from './ui';
import { conquestIconUrl, imageUrl } from './images';
import { shortDay } from './limited';
import { ConquestMap } from './conquestmap';
import { Party } from './conquestparty';
import { Collection } from './conquestcollection';
import { NewConquest } from './conquestnew';
import { artUrl } from './sleeves';
import { Reveal, wheelLabels, type Owed } from './conquestreward';
import { setting } from './settings';
import type { Actions } from './actions';
import type { Model } from './model';
import type { ConquestBar, ConquestSave } from './protocol';
import { t, type TextKey } from './text';

export function Conquest({ model, actions }: { model: Model; actions: Actions }) {
  const open = model.conquest !== null;
  // The form that starts a conquest takes the shelf's place, and gives it back when left or when its conquest opens
  const [creating, setCreating] = useState(false);
  useEffect(() => { if (open) setCreating(false); }, [open]);
  return (
    <div class={open ? 'cq-shell' : 'limited-page'}>
      <PageHeader class="limited-head">
        <div class="head-right">
          <HeadControls />
          <button onClick={() => (creating && !open ? setCreating(false) : actions.conquestLeave())}>{t('lblBack')}</button>
        </div>
      </PageHeader>
      {open ? <Campaign model={model} actions={actions} />
        : <Shelf model={model} actions={actions} creating={creating} setCreating={setCreating} />}
    </div>
  );
}

/** The saved conquests as cards, each with the one thing to do next, after a card that starts a new one. */
function Shelf({ model, actions, creating, setCreating }: { model: Model; actions: Actions; creating: boolean; setCreating: (on: boolean) => void }) {
  const saves = model.conquestSaves;
  const trail = [
    { label: t('lblWebLimitedStart'), go: () => { changeUi(u => { u.menuChoice = null; }); actions.conquestLeave(); } },
    { label: WAY_NAMES.play, go: () => { changeUi(u => { u.menuChoice = 'play'; }); actions.conquestLeave(); } },
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
  // The menu closes on any press outside it, as the table's menus do
  useEffect(() => {
    if (menu === null) return;
    const outside = (e: PointerEvent) => { if (!(e.target as Element).closest?.('.ev-more')) setMenu(null); };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [menu]);
  return (
    <div class="event-shelf">
      <button class="ev new" onClick={create}>
        <span class="plus" aria-hidden="true">+</span>
        <b>{t('lblWebConquestNew')}</b>
      </button>
      {saves.map(s => (
        <article key={s.name} class={s.name === current ? 'ev cq-save current' : 'ev cq-save'}>
          <div class="cq-save-art" style={{ backgroundImage: `url("${artUrl(s.art)}")` }}>
            <img class="cq-medal" alt="" src={imageUrl(s.walkerImage)} />
          </div>
          <div class="ev-top">
            {renaming === s.name
              ? (
                <form class="cq-rename" onSubmit={e => {
                  e.preventDefault();
                  const to = new FormData(e.currentTarget).get('name')?.toString().trim();
                  setRenaming(null);
                  if (to && to !== s.name) actions.conquestRename(s.name, to);
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
            <div class="cq-pbar"><i style={{ width: s.progress }} /></div>
            <span class="ev-line dim">
              <b class="pct">{s.progress}</b>
              <span class="sp" />
              {t('lblWebConquestCards', s.cards)}
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
                  <button class="danger" onClick={() => { setDeleting(null); actions.conquestDelete(s.name); }}>{t('lblDelete')}</button>
                </>
              : <>
                  <button class="primary" onClick={() => actions.conquestLoad(s.name)}>{t('lblWebLimitedPlay')}</button>
                  {s.saved && <span class="ev-ask">{t('lblWebConquestSaved', shortDay(s.saved))}</span>}
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
