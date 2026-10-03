// Planar Conquest: the saved conquests as a shelf, and inside one, a bar every page shares over the page itself.

import { useEffect, useState } from 'preact/hooks';
import { Shelf as Cards } from './shelf';
import { HeadControls, PageHeader, SetupHead, WAY_NAMES } from './header';
import { changeUi, eased, ui, type ConquestTab } from './ui';
import { skinIconUrl } from './images';
import { CampaignBar, Prefs, Purse, Stats } from './campaign';
import { shortDay } from './limited';
import { ConquestMap } from './conquestmap';
import { Party } from './conquestparty';
import { Collection } from './conquestcollection';
import { NewConquest } from './conquestnew';
import { Aether } from './conquestaether';
import { Planes } from './conquestplanes';
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
  return (
    <Cards rows={saves} cls={s => (s.name === current ? 'ev cq-save current' : 'ev cq-save')}
      create={{ title: t('lblWebConquestNew'), go: create }}
      art={s => (
        <div class="cq-save-art" style={{ backgroundImage: `url("${artUrl(s.art)}")` }}>
          <img class="cq-medal" alt="" src={artUrl(s.walkerImage)} onError={e => { e.currentTarget.hidden = true; }} />
        </div>
      )}
      sub={s => <span class="sub">{s.planeswalker} - {s.plane}</span>}
      mid={s => <>
        <div class="cq-pbar"><i style={{ width: `${s.total ? 100 * s.conquered / s.total : 0}%` }} /></div>
        <span class="ev-line dim">
          <b class="pct">{s.conquered} / {s.total}</b>
          <span class="sp" />
          {t('lblWebNCards', s.cards)}
          <Purse icon="IMG_AETHER_SHARD" n={s.shards} label={t('lblAetherShards')} />
          <Purse icon="IMG_PW_BADGE_COMMON" n={s.emblems} label={t('lblPlaneswalkEmblems')} />
        </span>
      </>}
      foot={s => <>
        <button class="primary" onClick={() => actions.campaignLoad(s.name)}>{t('lblPlay')}</button>
        {s.saved && <span class="ev-ask">{t('lblWebLimitedSaved', shortDay(s.saved))}</span>}
      </>}
      rename={{ field: t('lblConquestName'), go: (s, to) => actions.campaignRename(s.name, to) }}
      remove={{ item: t('lblDelete'), ask: s => t('lblAreYouSuerDeleteConquest', s.name), keep: t('lblCancel'), go: s => actions.campaignDelete(s.name) }} />
  );
}

const DEV_WHEEL = ['BOOSTER', 'DOUBLE_BOOSTER', 'SHARDS', 'DOUBLE_SHARDS', 'PLANESWALK', 'CHAOS'] as const;
const TABS: [ConquestTab, TextKey][] = [['map', 'lblTheMultiverse'], ['aether', 'lblTheAether'], ['party', 'lblCommanders'],
  ['collection', 'lblCollection'], ['planes', 'lblPlaneswalk'], ['stats', 'lblStatistics']];

/** Dev mode: where the next Chaos Wheel stops, so each reward can be looked at, in a menu drawn as the deck editor's is. */
function DevWheel({ actions }: { actions: Actions }) {
  const [open, setOpen] = useState(false);
  const [chosen, setChosen] = useState('');
  useEffect(() => {
    if (!open) return;
    const away = () => setOpen(false);
    document.addEventListener('pointerdown', away);
    return () => document.removeEventListener('pointerdown', away);
  }, [open]);
  const labels: Record<string, string> = wheelLabels();
  const name = (o: string) => (o ? labels[o] : t('lblWebConquestDevWheel'));
  return (
    <div class="menu-anchor" onPointerDown={e => e.stopPropagation()}>
      <button class="cq-dev" aria-expanded={open} title={t('lblWebConquestDevWheel')} onClick={() => setOpen(!open)}>{name(chosen)} &#8964;</button>
      {open && (
        <div class="deck-menu" role="menu">
          {['', ...DEV_WHEEL].map(o => (
            <button key={o} role="menuitemradio" aria-checked={o === chosen} onClick={() => {
              setChosen(o);
              setOpen(false);
              actions.devConquestWheel(o);
            }}>{name(o)}</button>
          ))}
        </div>
      )}
    </div>
  );
}

/** A change of tab slides the page the way the tabs run (dialogs.css). */
function tabTo(tab: ConquestTab): void {
  const order = TABS.map(([id]) => id);
  const way = order.indexOf(tab) > order.indexOf(ui.conquestTab) ? 'on' : 'back';
  if (tab !== ui.conquestTab) eased(u => { u.conquestTab = tab; }, () => ({ way }));
}

/** The page of the open tab. */
function Page({ tab, model, actions }: { tab: ConquestTab; model: Model; actions: Actions }) {
  switch (tab) {
    case 'aether': return <Aether model={model} actions={actions} />;
    case 'party': return <Party model={model} actions={actions} />;
    case 'collection': return <Collection model={model} actions={actions} />;
    case 'planes': return <Planes model={model} actions={actions} />;
    case 'stats': return <Stats model={model} actions={actions} first={model.conquestState?.plane} />;
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
    <CampaignBar bar={bar} tabs={TABS} tab={ui.conquestTab} setTab={tabTo}
      held={reward ? owed : {}} prefs={() => setPrefs(true)} under={!!reward}
      extra={setting('devMode') && <DevWheel actions={actions} />} />
    {model.error && <p class="limited-error">{model.error}</p>}
    <div class="cq-main" inert={!!reward}><Page key={`${priced} ${model.conquestState.plane}`} tab={ui.conquestTab} model={model} actions={actions} /></div>
    {prefs && <Prefs model={model} actions={actions} close={() => { setPrefs(false); setPriced(priced + 1); }} />}
    {reward && <Reveal key={reward.steps.length + ':' + bar.name} reward={reward} onOwed={setOwed} done={() => actions.rewardClaim()} />}
  </>;
}
