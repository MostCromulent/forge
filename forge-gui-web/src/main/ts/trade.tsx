// A campaign's lists of cards and the trade between them, where every price shown is the server's

import { useEffect, useRef, useState } from 'preact/hooks';
import { imageUrl, skinIconUrl } from './images';
import { ColourToggles, toggled } from './symbols';
import { useDebounced } from './hooks';
import { showNotice } from './notices';
import type { Actions } from './actions';
import type { Model } from './model';
import type { CatalogueRow } from './protocol';
import { t, type TextKey } from './text';

/** What a mode calls one of its lists, and what it says of trading from it. */
export interface TradeWords {
  label: string;
  /** The title of the bar and of the question, for n cards picked. */
  verb: (n: number) => string;
  /** Under a card: what it costs or pays in this list, with the currency's token where its symbol goes. */
  row: (price: string) => string;
  /** The question's sentence for n cards at this total, with the token where the symbol goes. */
  confirm: (n: number, total: string) => string;
}

const amount = (n: number): string => n.toLocaleString('en-GB');

/** The lists are the keys of words, in that order, and each is the source its cards are asked for by. */
export function TradePage({ model, actions, icon, iconLabel, token, words, types, groupLabel, blocked }: {
  model: Model; actions: Actions;
  /** The currency's skin icon, its name for a reader who cannot see it, and where a sentence names it. */
  icon: string; iconLabel: string; token: string;
  words: Record<string, TradeWords>; types: [string, TextKey][];
  /** The choice that narrows a list to none of the server's groups, when the mode has groups. */
  groupLabel?: string;
  /** The mark on a card that cannot be picked. */
  blocked: string;
}) {
  const info = model.trading;
  const [source, setSource] = useState(Object.keys(words)[0]);
  const [typed, setTyped] = useState('');
  const text = useDebounced(typed);
  const [colours, setColours] = useState<Set<string>>(() => new Set());
  const [type, setType] = useState('any');
  const [group, setGroup] = useState('');
  /** The cards picked, by image key, which is what names a printing. */
  const [picked, setPicked] = useState<Map<string, CatalogueRow>>(() => new Map());
  const [asking, setAsking] = useState(false);
  const asked = useRef(0);
  const query = { text, colours: [...colours].join(''), type, filters: '', sort: 'name', showAll: true, source, group: group || undefined };
  const sizes = info?.lists.map(l => l.count).join(',');
  const priced = (sentence: string) => {
    const [before, after] = sentence.split(token);
    return <>{before}<img class="cq-shard" alt={iconLabel} src={skinIconUrl(icon)} />{after}</>;
  };

  useEffect(() => { actions.trading(); }, []);
  // The lists change when cards are traded, which their sizes say
  useEffect(() => {
    asked.current = 0;
    actions.queryCatalogue(0, { ...query, offset: 0 });
  }, [text, query.colours, type, group, source, sizes]);
  useEffect(() => { setPicked(new Map()); setAsking(false); }, [source, sizes]);

  // The catalogue is shared with the deck editor and the other lists, whose page may still be the one held
  const page = model.catalogue?.source === source ? model.catalogue : null;
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
  const said = words[source];
  const title = said.verb(chosen.length);
  return (
    <div class="cq-coll">
      <div class="cq-coll-bar">
        <span class="seg" role="group">
          {Object.entries(words).map(([id, w]) => {
            const size = info?.lists.find(l => l.source === id)?.count;
            return <button key={id} aria-pressed={id === source} onClick={() => setSource(id)}>{w.label}{size !== undefined && ` (${amount(size)})`}</button>;
          })}
        </span>
        <input class="find" type="search" placeholder={t('lblSearch')} autocomplete="off" value={typed} onInput={e => setTyped(e.currentTarget.value)} />
        <ColourToggles label={t('lblColors')} colourless pressed={c => colours.has(c)} toggle={c => setColours(toggled(colours, c))} />
        <select aria-label={t('lblCardType')} value={type} onChange={e => setType(e.currentTarget.value)}>
          {types.map(([id, name]) => <option key={id} value={id}>{t(name)}</option>)}
        </select>
        {groupLabel && (
          <select aria-label={groupLabel} value={group} onChange={e => setGroup(e.currentTarget.value)}>
            <option value="">{groupLabel}</option>
            {(info?.groups ?? []).map(g => <option key={g} value={g}>{g}</option>)}
          </select>
        )}
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
            {row.problem && <span class="inuse">{blocked}</span>}
            <span class="val">{priced(said.row(amount(row.value ?? 0)))}</span>
          </button>
        ))}
      </div>
      {chosen.length > 0 && (
        <div class="cq-sel-bar">
          <button onClick={() => setPicked(new Map())}>{t('lblCancel')}</button>
          <button class="primary" onClick={() => setAsking(true)}>
            {title}<img class="cq-shard" alt={iconLabel} src={skinIconUrl(icon)} />{amount(total)}
          </button>
        </div>
      )}
      {asking && (
        <div class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) setAsking(false); }}>
          <div class="dialog cq-exile">
            <h3>{title}</h3>
            <p class="cq-exile-total">{priced(said.confirm(chosen.length, amount(total)))}</p>
            <ul class="cq-cards-list">
              {chosen.map(c => <li key={c.image}>{c.name}<span>{priced(`${token} ${amount(c.value ?? 0)}`)}</span></li>)}
            </ul>
            <div class="actions">
              <button onClick={() => setAsking(false)}>{t('lblCancel')}</button>
              <button class="primary" onClick={() => { setAsking(false); actions.trade(source, chosen.map(c => ({ key: c.image, count: 1 }))); }}>{t('lblOK')}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
