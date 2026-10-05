// The Aether, where the server decides what matches the filters, what a pull costs and which card comes

import { useEffect, useState } from 'preact/hooks';
import { skinIconUrl, imageUrl } from '../images';
import { balance } from '../campaign/campaign';
import { PricedButton } from '../campaign/trade';
import { ColourToggles, Pip, Pips } from '../symbols';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { ConquestAetherState, ConquestOption, PackCard } from '../protocol';
import { t, type TextKey } from '../text';

const RARITIES: TextKey[] = ['lblCommon', 'lblUncommon', 'lblRare', 'lblMythic'];
/** How many of the cards pulled this visit stay in view. */
const MOST_RECENT = 12;

const shard = (size: number) => <img class="cq-shard" alt={t('lblAetherShards')} style={{ width: `${size}px`, height: `${size}px` }} src={skinIconUrl('IMG_AETHER_SHARD')} />;

export function Aether({ model, actions }: { model: Model; actions: Actions }) {
  const a = model.conquestAether;
  const [recent, setRecent] = useState<PackCard[]>([]);
  const [zoom, setZoom] = useState<string | null>(null);
  useEffect(() => { actions.conquestAether({ colors: '', type: '', rarity: '', cmc: '', pull: false }); }, []);
  useEffect(() => { if (a?.pulled) setRecent(r => [a.pulled!, ...r].slice(0, MOST_RECENT)); }, [a?.pulled]);
  if (!a) return <p class="muted pools-wait">{t('lblWebConquestReading')}</p>;
  const ask = (patch: Partial<Pick<ConquestAetherState, 'colors' | 'type' | 'rarity' | 'cmc'>>, pull = false) =>
    actions.conquestAether({ colors: a.colors, type: a.type, rarity: a.rarity, cmc: a.cmc, pull, ...patch });
  const toggle = (letter: string) => ask({ colors: [...'WUBRG'].filter(c => (c === letter) !== a.colors.includes(c)).join('') });
  // The price is on the button, so a pull that cannot be paid for is not offered
  const short = a.cost > balance(model.campaignBar, 'IMG_AETHER_SHARD');
  const pull = <PricedButton label={t('lblWebConquestPull')} icon="IMG_AETHER_SHARD" iconLabel={t('lblAetherShards')} size={18} cost={a.cost}
    have={balance(model.campaignBar, 'IMG_AETHER_SHARD')} disabled={!a.cost || !a.strict} cls="cq-ae-pull" onClick={() => ask({}, true)} />;
  return (
    <div class="cq-aether">
      <aside class="cq-ae-filters">
        <div>
          <h4>{t('lblColor')}<span>{t('lblWebConquestPlayableIn')} <span class="pips">{a.commanderColors ? <Pips colors={a.commanderColors} /> : <Pip letter="C" />}</span></span></h4>
          <ColourToggles label={t('lblColor')} pressed={c => a.colors.includes(c)} toggle={toggle} />
        </div>
        <Options title={t('lblType')} options={a.types} chosen={a.type} pick={type => ask({ type })} />
        <Options title={t('lblRarity')} options={a.rarities} chosen={a.rarity} pick={rarity => ask({ rarity })} />
        <Options title={t('lblCMC')} options={a.cmcs} chosen={a.cmc} pick={cmc => ask({ cmc })} two />
      </aside>
      <div class="cq-ae-stage">
        {a.pulled
          ? (
            <div class="cq-ae-card">
              <button class="cq-ae-face" onClick={() => setZoom(a.pulled!.image)}><img alt={a.pulled.name} src={imageUrl(a.pulled.image)} /></button>
              <div class="what"><span class="new-card">{t('lblNew')}</span><b>{a.pulled.name}</b></div>
              {pull}
            </div>
          )
          : (
            <div class="cq-ae-core">
              <img class="cq-ae-orb" alt="" src={skinIconUrl('IMG_MULTIVERSE')} />
              <div class="cq-chips">
                {RARITIES.map((name, i) => <span key={name} class="cq-chip">{a.byRarity[i]} {t(name)}</span>)}
              </div>
              {pull}
            </div>
          )}
        {(a.problem || (short && a.cost > 0)) && <p class="cq-warn">{a.problem ?? t('lblWebConquestTooFewShards')}</p>}
        <div class="cq-ae-count">{a.matching.toLocaleString('en-GB')} / {a.locked.toLocaleString('en-GB')}</div>
        <div class="cq-ae-recent">
          {recent.map((c, i) => <button key={`${c.image}:${recent.length - i}`} title={c.name} onClick={() => setZoom(c.image)}><img alt={c.name} src={imageUrl(c.image)} /></button>)}
        </div>
      </div>
      {zoom && <div class="backdrop cq-zoom-layer" onClick={() => setZoom(null)}><img class="cq-zoom" alt="" src={imageUrl(zoom)} /></div>}
    </div>
  );
}

/** One of the filters: its options as tiles, of which one is chosen. A rarity carries what a pull at it costs. */
function Options({ title, options, chosen, pick, two }: { title: string; options: ConquestOption[]; chosen: string; pick: (key: string) => void; two?: boolean }) {
  return (
    <div>
      <h4>{title}</h4>
      <div class={two ? 'cq-opts two' : 'cq-opts'} role="radiogroup" aria-label={title}>
        {options.map(o => (
          <button key={o.key} class="cq-opt" role="radio" aria-checked={o.key === chosen} onClick={() => pick(o.key)}>
            <b>{o.label}</b>
            {o.cost != null && <span class="r">{shard(13)}{o.cost.toLocaleString('en-GB')}</span>}
          </button>
        ))}
      </div>
    </div>
  );
}
