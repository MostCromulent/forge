// The Aether: the Aetherworks, which Echoes build and rank and Aether Energy switches on, and the Aether Upgrades Sparks buy

import { SidePanel } from '../campaign/campaign';
import { Icon } from '../icons';
import { artUrl, skinIconUrl } from '../images';
import { isPortrait } from '../form';
import { playSound } from '../audio';
import { changeUi, ui } from '../ui';
import { t } from '../text';
import { ROGUE_ICONS } from './icons';
import type { AetherWork, RogueAether } from '../protocol';

const echo = (cls?: string) => <img class={cls} alt="" src={skinIconUrl('ICO_QUEST_GOLD')} />;
const spark = <img alt="" src={skinIconUrl('ICO_QUEST_ELIXIR')} />;
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
    <button class={work.on ? 'rg-work on' : work.rank ? 'rg-work' : 'rg-work unbuilt'} aria-pressed={chosen} onClick={choose}>
      <span class="h"><span class="rgt-seal"><Icon d={ROGUE_ICONS.gem} cls="rg-ic" /></span><b>{work.name}</b></span>
      <Ranked work={work} />
      <span class="f">
        <span class="rg-rank">{work.ranks.map((_, i) => <i key={i} class={i < work.rank ? 'on' : ''} />)}</span>
        {!work.rank ? <span>{t('lblWebRogueNotBuilt')}</span> : work.on ? <span><b class="act">{t('lblWebRogueActive')}</b>{` · ${t('lblWebRogueRankOf', work.rank, max)}`}</span>
          : <span>{t('lblWebRogueRankOf', work.rank, max)}</span>}
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

/** The menu rows a phone has in place of the wide page's reset and upgrade buttons. */
export function aetherMenu(aether: RogueAether, close: () => void, expand: () => void, reset: () => void) {
  const upgrade = aether.upgrade;
  return <>
    {upgrade && (
      <button role="menuitem" disabled={!upgrade.afford} onClick={() => { close(); built(expand)(); }}>
        {t('lblWebRogueBuyUpgrade', upgrade.name, upgrade.sparks, upgrade.echoes)}
      </button>
    )}
    <button role="menuitem" onClick={() => { close(); reset(); }}>{t('lblRogueResetAetherworks')}</button>
  </>;
}

/** raise builds or ranks a work, toggle switches it, expand buys the next Aether Upgrade and reset asks before refunding every work. */
export function AetherPage({ aether, raise, toggle, expand, reset }: {
  aether: RogueAether; raise: (id: string) => void; toggle: (id: string, on: boolean) => void; expand: () => void; reset: () => void;
}) {
  const phone = isPortrait();
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
  if (phone) {
    return (
      <div class="rg-aether">
        <span class="rg-haze" style={{ backgroundImage: aether.art ? `url("${artUrl(aether.art)}")` : undefined }} />
        <div class="rg-scroll">
          <span class="rg-energy">{t('lblWebRogueAetherEnergy')}{pips}{`${aether.used} / ${aether.slots}`}</span>
          {works}
        </div>
        {work && (
          <div class="rg-sheetbar">
            <div class="top">
              <div class="t"><b>{work.name}</b><span>{work.ranks[Math.min(work.rank, work.ranks.length - 1)]?.text}</span></div>
              {switcher}
            </div>
            <div class="go" style={{ gridTemplateColumns: '1fr' }}><Raise work={work} cls="primary" raise={() => raise(work.id)} /></div>
          </div>
        )}
      </div>
    );
  }
  const upgrade = aether.upgrade;
  return (
    <div class="rg-aether">
      <span class="rg-haze" style={{ backgroundImage: aether.art ? `url("${artUrl(aether.art)}")` : undefined }} />
      <div class="rg-scroll">
        <div class="rg-head">
          <h2>{t('lblRogueTheAether')}</h2>
          <span class="rg-energy">{t('lblWebRogueAetherEnergy')}{pips}{t('lblWebRogueEnergyInUse', aether.used, aether.slots)}</span>
          <span class="sp" />
          <button onClick={reset}>{t('lblRogueResetAetherworks')}</button>
        </div>
        {works}
        {!aether.descension && <p class="rg-pnote">{t('lblRogueAetherEnergyHint').replace(/\n/g, ' ')}</p>}
        {aether.locked.map(group => (
          <div key={group.title} class="rg-group">
            <h4>{group.title}</h4>
            <div class="rg-works" style={{ opacity: 0.6 }}>
              {group.works.map(name => (
                <div key={name} class="rg-work unbuilt" style={{ minHeight: 0 }}><span class="h"><Icon d={ROGUE_ICONS.lock} cls="rg-ic" /><b>{name}</b></span></div>
              ))}
            </div>
          </div>
        ))}
      </div>
      {work && (
        <SidePanel kicker={t('lblWebRogueAetherwork')} title={work.name} noArt zoom={null} setZoom={() => {}}
          foot={<Raise work={work} cls="primary rg-go" shard="camp-shard" raise={() => raise(work.id)} />}>
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
