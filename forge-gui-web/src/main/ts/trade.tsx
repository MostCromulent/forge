// A campaign's lists of cards and the trade between them, where every price shown is the server's

import type { ComponentChildren } from 'preact';
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
  /** The badge on a card for how many its list holds, for a list that counts copies. */
  count?: (n: number) => string;
}

/** Something picked: a card's printing or a product, by the key a trade names it by, and how many of it. */
export interface Pick { key: string; name: string; value: number; count: number; most: number }

const amount = (n: number): string => n.toLocaleString('en-GB');

/** A row as a pick of one, up to as many as its list holds. */
export const pickOf = (row: CatalogueRow, count = 1): Pick => ({ key: keyOf(row), name: row.name, value: row.value ?? 0, count, most: row.count ?? 1 });

/** What names a row in a trade: the server's key where its image key is not enough, as for a foil, otherwise its image key. */
const keyOf = (row: CatalogueRow): string => row.key ?? row.image;

/** One button that spends a balance: its verb, the currency's icon and the cost, refused while the balance is short. */
export function PricedButton({ label, icon, iconLabel, size, cost, have, disabled, cls, onClick }: {
  label: string; icon: string; iconLabel: string; size: number; cost: number; have: number; disabled?: boolean; cls?: string; onClick: () => void;
}) {
  return (
    <button class={cls ? `primary ${cls}` : 'primary'} disabled={disabled || cost > have} onClick={onClick}>
      {label}<span class="cq-sep">·</span><img class="cq-shard" alt={iconLabel} style={{ width: `${size}px`, height: `${size}px` }} src={skinIconUrl(icon)} />{cost.toLocaleString('en-GB')}
    </button>
  );
}

/** Owned copies as a playset of squares, filled for each copy held, and the number beside them. */
function Owned({ n }: { n: number }) {
  return (
    <span class="cq-owned" title={t('lblWebTradeOwned', n)}>
      {[1, 2, 3, 4].map(i => <i key={i} class={i <= n ? 'on' : undefined} />)}
      <span>{t('lblWebTradeOwned', n)}</span>
    </span>
  );
}

/** The lists are the keys of words, in that order, and each is the source its cards are asked for by. */
export function TradePage({ model, actions, icon, iconLabel, token, words, types, groupLabel, blocked, top }: {
  model: Model; actions: Actions;
  /** The currency's skin icon, its name for a reader who cannot see it, and where a sentence names it. */
  icon: string; iconLabel: string; token: string;
  words: Record<string, TradeWords>; types: [string, TextKey][];
  /** The choice that narrows a list to none of the server's groups, when the mode has groups. */
  groupLabel?: string;
  /** The mark on a card that cannot be picked. */
  blocked: string;
  /** What a list shows above its cards: given the picks, a way to pick or unpick one, and a way to ask about a whole set of picks at once. */
  top?: (source: string, picked: Map<string, Pick>, toggle: (pick: Pick) => void, askAbout: (picks: Pick[]) => void) => ComponentChildren;
}) {
  const info = model.trading;
  const [source, setSource] = useState(Object.keys(words)[0]);
  const [typed, setTyped] = useState('');
  const text = useDebounced(typed);
  const [colours, setColours] = useState<Set<string>>(() => new Set());
  const [type, setType] = useState('any');
  const [group, setGroup] = useState('');
  /** What is picked, by key, which for a card is its image key, the key of its printing. */
  const [picked, setPicked] = useState<Map<string, Pick>>(() => new Map());
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
  const toggle = (pick: Pick) => {
    const next = new Map(picked);
    if (!next.delete(pick.key)) next.set(pick.key, pick);
    setPicked(next);
  };
  const recount = (pick: Pick, by: number) => {
    const count = Math.min(Math.max(1, pick.count + by), pick.most);
    setPicked(new Map(picked).set(pick.key, { ...pick, count }));
  };
  const askAbout = (picks: Pick[]) => {
    setPicked(new Map(picks.map(p => [p.key, p])));
    if (picks.length) setAsking(true);
  };
  const chosen = [...picked.values()];
  const n = chosen.reduce((sum, c) => sum + c.count, 0);
  const total = chosen.reduce((sum, c) => sum + c.value * c.count, 0);
  const said = words[source];
  const title = said.verb(n);
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
      {top?.(source, picked, toggle, askAbout)}
      <div class="cq-coll-grid" onScroll={more}>
        {page && page.total === 0 && <p class="none-found">{t('lblWebCatalogueNoMatchHint')}</p>}
        {rows.map(row => {
          const pick = picked.get(keyOf(row));
          const card = (
              <button key={keyOf(row)} class={`cq-cc${pick ? ' sel' : ''}${row.problem ? ' used' : ''}`} title={row.problem ?? row.name}
                aria-pressed={!!pick} aria-disabled={!!row.problem}
                onClick={() => (row.problem ? showNotice({ t: 'notice', title: row.problem, error: false }) : toggle(pickOf(row)))}>
                <span class="cq-pic"><span class="nm">{row.name}</span><img loading="lazy" alt="" src={imageUrl(row.image)} onError={e => { e.currentTarget.hidden = true; }} /></span>
                {row.isNew && <span class="new-card">{t('lblNew')}</span>}
                {row.problem && <span class="inuse">{blocked}</span>}
                {said.count && row.count != null && <span class="cq-stock">{said.count(row.count)}</span>}
                <span class="val">{priced(said.row(amount(row.value ?? 0)))}{row.owned != null && <Owned n={row.owned} />}{row.note && <span class="cq-cc-note">{row.note}</span>}</span>
              </button>
          );
          // A list that counts copies takes a count for a pick, under the card; any other is the card alone
          if (row.count == null) return card;
          return (
            <div key={keyOf(row)} class="cq-cc-slot">
              {card}
              {pick && pick.most > 1 && (
                <span class="cq-step">
                  <button aria-label={t('lblWebTradeOneFewer', row.name)} disabled={pick.count <= 1} onClick={() => recount(pick, -1)}>&minus;</button>
                  <b>{t('lblWebTradeNOf', pick.count, pick.most)}</b>
                  <button aria-label={t('lblWebTradeOneMore', row.name)} disabled={pick.count >= pick.most} onClick={() => recount(pick, 1)}>+</button>
                </span>
              )}
            </div>
          );
        })}
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
            <p class="cq-exile-total">{priced(said.confirm(n, amount(total)))}</p>
            <ul class="cq-cards-list">
              {chosen.map(c => <li key={c.key}>{c.count > 1 ? `${c.name} ×${c.count}` : c.name}<span>{priced(`${token} ${amount(c.value * c.count)}`)}</span></li>)}
            </ul>
            <div class="actions">
              <button onClick={() => setAsking(false)}>{t('lblCancel')}</button>
              <button class="primary" onClick={() => { setAsking(false); actions.trade(source, chosen.map(c => ({ key: c.key, count: c.count }))); }}>{t('lblOK')}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
