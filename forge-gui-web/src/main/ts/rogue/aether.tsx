// The Aether: the Aetherworks, which Echoes build and rank and Aether Energy switches on, and the Aether Upgrades Sparks buy

import { useLayoutEffect, useRef } from 'preact/hooks';
import { SidePanel } from '../campaign/campaign';
import { Icon } from '../icons';
import { artUrl } from '../images';
import { isPortrait } from '../form';
import { playSound } from '../audio';
import { changeUi, ui } from '../ui';
import { t } from '../text';
import { ROGUE_ICONS } from './icons';
import { Sprite, sealGlyph } from './kit';
import { rankUp } from './motion';
import type { AetherWork, RogueAether } from '../protocol';

const echo = (cls?: string) => <Sprite k="shards" cls={cls} />;
const spark = <Sprite k="spark" />;
const built = (play: () => void) => () => {
  playSound({ t: 'sound', name: 'artifact', sync: false });
  play();
};

/** The description with the value of the rank built in bold, when the text holds the values as the server joins them. */
function Ranked({ work }: { work: AetherWork }) {
  const joined = work.values.join(' / ');
  const at = work.text.indexOf(joined);
  if (work.rank === 0 || at < 0) return <p>{work.text}</p>;
  const values = work.values.flatMap((v, i) => [i ? ' / ' : '', i === work.rank - 1 ? <b key={i}>{v}</b> : `${v}`]);
  return <p>{work.text.slice(0, at)}{values}{work.text.slice(at + joined.length)}</p>;
}

function Work({ work, chosen, choose }: { work: AetherWork; chosen: boolean; choose: () => void }) {
  const max = work.ranks.length;
  return (
    <button class={work.on ? 'rg-work on' : work.rank ? 'rg-work' : 'rg-work unbuilt'} data-id={work.id} aria-pressed={chosen} onClick={choose}>
      <span class="h"><span class="rgt-seal">{sealGlyph('gem')}</span><b>{work.name}</b></span>
      <Ranked work={work} />
      <span class="f">
        <span class="rg-rank">{work.ranks.map((_, i) => <i key={i} class={i < work.rank ? 'on' : ''} />)}</span>
        {!work.rank ? <span>{t('lblWebRogueNotBuilt')}</span> : work.on ? <span class="rk"><b class="act">{t('lblWebRogueActive')}</b>{` · ${t('lblWebRogueRankOf', work.rank, max)}`}</span>
          : <span class="rk">{t('lblWebRogueRankOf', work.rank, max)}</span>}
        <span class="sp" />
        {work.cost >= 0 && <span class="rg-price">{echo()}{work.cost}</span>}
      </span>
    </button>
  );
}

/** The button that builds the Aetherwork or raises its rank, or says it is at its highest. */
function Raise({ work, cls, shard, raise }: { work: AetherWork; cls: string; shard?: string; raise: () => void }) {
  if (work.cost < 0) return <button class={cls} disabled>{t('lblRogueMaxRank')}</button>;
  return <button class={cls} disabled={!work.afford} onClick={built(raise)}>{`${t(work.rank ? 'lblRogueUpgradeLabel' : 'lblWebRogueBuildLabel')} `}{echo(shard)}{work.cost}</button>;
}

/** The menu row a phone has in place of the wide page's reset button. */
export function aetherMenu(close: () => void, reset: () => void) {
  return <button role="menuitem" onClick={() => { close(); reset(); }}>{t('lblRogueResetAetherworks')}</button>;
}

/** The Aetherworks later upgrades unlock, locked. */
const lockedGroups = (aether: RogueAether) => aether.locked.map(group => (
  <div key={group.title} class="rg-group">
    <h4>{group.title}</h4>
    <div class="rg-works" style={{ opacity: 0.6 }}>
      {group.works.map(name => (
        <div key={name} class="rg-work unbuilt" style={{ minHeight: 0 }}><span class="h"><Icon d={ROGUE_ICONS.lock} cls="rg-ic" /><b>{name}</b></span></div>
      ))}
    </div>
  </div>
));

/** raise builds or ranks a work, toggle switches it, expand buys the next Aether Upgrade and reset asks before refunding every work. */
export function AetherPage({ aether, raise, toggle, expand, reset }: {
  aether: RogueAether; raise: (id: string) => void; toggle: (id: string, on: boolean) => void; expand: () => void; reset: () => void;
}) {
  const phone = isPortrait();
  const page = useRef<HTMLDivElement>(null);
  // The work being raised, its rank and the Echoes it costs, until the page shows the new rank
  const raising = useRef<{ id: string; rank: number; paid: number } | null>(null);
  useLayoutEffect(() => {
    const was = raising.current;
    const now = was && aether.works.find(w => w.id === was.id);
    if (!was || !now || now.rank <= was.rank) return;
    raising.current = null;
    const tile = page.current!.querySelector<HTMLElement>(`.rg-work[data-id="${was.id}"]`);
    if (tile) rankUp(tile, now.rank, was.paid, left => changeUi(u => { u.rogueEchoesOwed = left; }));
  }, [aether]);
  const raiseWork = (w: AetherWork) => {
    raising.current = { id: w.id, rank: w.rank, paid: w.cost };
    raise(w.id);
  };
  const work = aether.works.find(w => w.id === ui.rogueWork) ?? aether.works[0];
  const choose = (id: string) => changeUi(u => { u.rogueWork = id; });
  const pips = <span class="pips">{Array.from({ length: aether.slots }, (_, i) => <i key={i} class={i < aether.used ? 'on' : undefined} />)}</span>;
  const switcher = work && (
    <button class={work.on ? 'rg-toggle on' : 'rg-toggle'} disabled={!work.toggle} aria-label={phone ? t('lblWebRogueActiveInNewRuns') : undefined}
      onClick={() => toggle(work.id, !work.on)}>
      <span class="sw" />{!phone && t('lblWebRogueActiveInNewRuns')}
    </button>
  );
  const works = <div class="rg-works">{aether.works.map(w => <Work key={w.id} work={w} chosen={w === work} choose={() => choose(w.id)} />)}</div>;
  const upgrade = aether.upgrade;
  if (phone) {
    // As on mobile, the next Aether Upgrade, or before Descension why there is none, heads the list
    return (
      <div class="rg-aether" ref={page}>
        <span class="rg-haze" style={{ backgroundImage: aether.art ? `url("${artUrl(aether.art)}")` : undefined }} />
        <div class="rg-scroll">
          <span class="rg-energy">{t('lblRogueAetherEnergyLabel')}{pips}{`${aether.used} / ${aether.slots}`}</span>
          {upgrade ? (
            <div class="rg-upcard">
              <b>{upgrade.name}</b>
              <p>{upgrade.text}</p>
              <div class="f">
                <span class="rg-price">{spark}{upgrade.sparks}</span><span class="rg-price">{echo()}{upgrade.echoes}</span><span class="sp" />
                <button class="primary" disabled={!upgrade.afford} onClick={built(expand)}>{t('lblRogueUpgrade')}</button>
              </div>
            </div>
          ) : !aether.descension && <p class="rg-upcard">{t('lblRogueAetherEnergyHint').replace(/\n/g, ' ')}</p>}
          {works}
          {lockedGroups(aether)}
        </div>
        {work && (
          <div class="rg-sheetbar">
            <div class="top">
              <div class="t"><b>{work.name}</b><span>{t('lblRogueRank', work.rank, work.ranks.length)}</span><Ranked work={work} /></div>
              {switcher}
            </div>
            <div class="go" style={{ gridTemplateColumns: '1fr' }}><Raise work={work} cls="primary" raise={() => raiseWork(work)} /></div>
          </div>
        )}
      </div>
    );
  }
  return (
    <div class="rg-aether" ref={page}>
      <span class="rg-haze" style={{ backgroundImage: aether.art ? `url("${artUrl(aether.art)}")` : undefined }} />
      <div class="rg-scroll">
        <div class="rg-head">
          <h2>{t('lblRogueTheAether')}</h2>
          <span class="rg-energy">{t('lblRogueAetherEnergyLabel')}{pips}{t('lblWebRogueEnergyInUse', aether.used, aether.slots)}</span>
          <span class="sp" />
          <button class="link" onClick={reset}>{t('lblRogueResetAetherworks')}</button>
        </div>
        {works}
        {!aether.descension && <p class="rg-pnote">{t('lblRogueAetherEnergyHint').replace(/\n/g, ' ')}</p>}
        {lockedGroups(aether)}
      </div>
      {work && (
        <SidePanel kicker={t('lblWebRogueAetherwork')} title={work.name} noArt zoom={null} setZoom={() => {}}
          foot={<Raise work={work} cls="primary rg-go" shard="camp-shard" raise={() => raiseWork(work)} />}>
          <div class="rg-sec">
            <h4>{t('lblWebRogueRanks')}</h4>
            <div class="rg-ladder">
              {work.ranks.map((rank, i) => (
                <div key={i} class={i < work.rank ? 'got' : i === work.rank ? 'nxt' : undefined}>
                  <span>{i + 1}</span>
                  <span>{rank.text}</span>
                  {i < work.rank ? <Icon d={ROGUE_ICONS.check} cls="rg-ic" /> : <span class="rg-price">{echo()}{rank.cost}</span>}
                </div>
              ))}
            </div>
          </div>
          {switcher}
          {upgrade && (
            <div class="rg-sec">
              <h4>{upgrade.name}</h4>
              <p>{upgrade.text}</p>
              <button disabled={!upgrade.afford} onClick={built(expand)}>{`${t('lblRogueUpgradeLabel')} `}{spark}{`${upgrade.sparks} `}{echo()}{upgrade.echoes}</button>
            </div>
          )}
        </SidePanel>
      )}
    </div>
  );
}
