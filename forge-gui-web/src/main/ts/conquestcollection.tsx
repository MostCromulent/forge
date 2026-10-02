// A conquest's cards, owned and exiled, where every price shown is the server's

import { useEffect, useRef, useState } from 'preact/hooks';
import { conquestIconUrl, imageUrl } from './images';
import { ColourToggles, toggled } from './symbols';
import { TYPES as CARD_TYPES } from './catalogue';
import { useDebounced } from './hooks';
import { showNotice } from './notices';
import type { Actions } from './actions';
import type { Model } from './model';
import type { CatalogueRow } from './protocol';
import { t } from './text';

/** A conquest's planes hold no battles. */
const TYPES = CARD_TYPES.filter(([id]) => id !== 'battle');
/** Where mobile's sentences put the shard symbol. */
const SHARD = '{AE}';

/** A sentence of mobile's with the shard symbol drawn where it names it. */
function Priced({ text }: { text: string }) {
  const [before, after] = text.split(SHARD);
  return <>{before}<img class="cq-shard" alt={t('lblAetherShards')} src={conquestIconUrl('IMG_AETHER_SHARD')} />{after}</>;
}

const amount = (n: number): string => n.toLocaleString('en-GB');

export function Collection({ model, actions }: { model: Model; actions: Actions }) {
  const info = model.conquestCollection;
  const [exiled, setExiled] = useState(false);
  const [typed, setTyped] = useState('');
  const text = useDebounced(typed);
  const [colours, setColours] = useState<Set<string>>(() => new Set());
  const [type, setType] = useState('any');
  const [plane, setPlane] = useState('');
  /** The cards picked, by image key, which is what names a printing. */
  const [picked, setPicked] = useState<Map<string, CatalogueRow>>(() => new Map());
  const [asking, setAsking] = useState(false);
  const asked = useRef(0);
  const query = { text, colours: [...colours].join(''), type, filters: '', sort: 'name', showAll: true,
    source: exiled ? 'exile' : 'collection', plane: plane || undefined };

  useEffect(() => { actions.conquestCollection(); }, []);
  // The lists change when a card is exiled or brought back, which the counts say
  useEffect(() => {
    asked.current = 0;
    actions.queryCatalogue(0, { ...query, offset: 0 });
  }, [text, query.colours, type, plane, exiled, info?.collection, info?.exiled]);
  useEffect(() => { setPicked(new Map()); setAsking(false); }, [exiled, info?.collection, info?.exiled]);

  // The catalogue is shared with the deck editor and the other list, whose page may still be the one held
  const page = model.catalogue?.source === query.source ? model.catalogue : null;
  const rows = page?.rows ?? [];
  const more = (e: Event) => {
    const el = e.currentTarget as HTMLElement;
    if (page && rows.length < page.total && asked.current < rows.length && el.scrollTop + el.clientHeight > el.scrollHeight - 600) {
      asked.current = rows.length;
      actions.queryCatalogue(0, { ...query, offset: rows.length });
    }
  };
  const toggle = (row: CatalogueRow) => {
    const next = new Map(picked);
    if (!next.delete(row.image)) next.set(row.image, row);
    setPicked(next);
  };
  const chosen = [...picked.values()];
  const total = chosen.reduce((sum, c) => sum + (c.value ?? 0), 0);
  const cards = chosen.length === 1 ? t('lblCard') : t('lblCards');
  const title = exiled
    ? (chosen.length === 1 ? t('lblRetrieveCard') : t('lblRetrieveNCard', chosen.length))
    : (chosen.length === 1 ? t('lblExileCard') : t('lblExileNCard', chosen.length));
  return (
    <div class="cq-coll">
      <div class="cq-coll-bar">
        <span class="seg" role="group">
          <button aria-pressed={!exiled} onClick={() => setExiled(false)}>{t('lblCollection')}{info && ` (${amount(info.collection)})`}</button>
          <button aria-pressed={exiled} onClick={() => setExiled(true)}>{t('lblExile')}{info && ` (${amount(info.exiled)})`}</button>
        </span>
        <input class="find" type="search" placeholder={t('lblSearch')} autocomplete="off" value={typed} onInput={e => setTyped(e.currentTarget.value)} />
        <ColourToggles label={t('lblWebEditorColours')} colourless pressed={c => colours.has(c)} toggle={c => setColours(toggled(colours, c))} />
        <select aria-label={t('lblWebCatalogueCardType')} value={type} onChange={e => setType(e.currentTarget.value)}>
          {TYPES.map(([id, name]) => <option key={id} value={id}>{t(name)}</option>)}
        </select>
        <select aria-label={t('lblAllPlanes')} value={plane} onChange={e => setPlane(e.currentTarget.value)}>
          <option value="">{t('lblAllPlanes')}</option>
          {(info?.planes ?? []).map(p => <option key={p} value={p}>{p}</option>)}
        </select>
        {info && <span class="cq-coll-note">{info.note}</span>}
      </div>
      <div class="cq-coll-grid" onScroll={more}>
        {page && page.total === 0 && <p class="none-found">{t('lblWebCatalogueNoMatchHint')}</p>}
        {rows.map(row => (
          <button key={row.image} class={`cq-cc${picked.has(row.image) ? ' sel' : ''}${row.problem ? ' used' : ''}`} title={row.problem ?? row.name}
            aria-pressed={picked.has(row.image)} aria-disabled={!!row.problem}
            onClick={() => (row.problem ? showNotice({ t: 'notice', title: row.problem, error: false }) : toggle(row))}>
            <span class="cq-pic"><span class="nm">{row.name}</span><img loading="lazy" alt="" src={imageUrl(row.image)} onError={e => { e.currentTarget.hidden = true; }} /></span>
            {row.isNew && <span class="new-card">{t('lblNew')}</span>}
            {row.problem && <span class="inuse">{t('lblWebConquestInUse')}</span>}
            <span class="val"><Priced text={t(exiled ? 'lblRetrieveForNAE' : 'lblExileForNAE', amount(row.value ?? 0), SHARD)} /></span>
          </button>
        ))}
      </div>
      {chosen.length > 0 && (
        <div class="cq-sel-bar">
          <button onClick={() => setPicked(new Map())}>{t('lblCancel')}</button>
          <button class="primary" onClick={() => setAsking(true)}>
            {title}<img class="cq-shard" alt={t('lblAetherShards')} src={conquestIconUrl('IMG_AETHER_SHARD')} />{amount(total)}
          </button>
        </div>
      )}
      {asking && (
        <div class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) setAsking(false); }}>
          <div class="dialog cq-exile">
            <h3>{title}</h3>
            <p class="cq-exile-total"><Priced text={(exiled
              ? t('lblSpendAECostToRetrieveCardsFromExile', SHARD, amount(total), cards)
              : t('lblExileFollowCardsToReceiveNAE', cards, SHARD, amount(total))).trim()} /></p>
            <ul class="cq-cards-list">
              {chosen.map(c => <li key={c.image}>{c.name}<span><Priced text={`${SHARD} ${amount(c.value ?? 0)}`} /></span></li>)}
            </ul>
            <div class="actions">
              <button onClick={() => setAsking(false)}>{t('lblCancel')}</button>
              <button class="primary" onClick={() => { setAsking(false); actions.conquestExile(chosen.map(c => c.image), exiled); }}>{t('lblOK')}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
