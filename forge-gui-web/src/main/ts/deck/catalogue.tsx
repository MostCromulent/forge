// The editor's left half, where cards the deck can't use are left out until a filter asks for them

import { useEffect, useRef, useState } from 'preact/hooks';
import { imageUrl, hideBroken } from '../images';
import { lift, liftFromLine } from '../flight';
import { ColourToggles, SymbolText, toggled } from '../symbols';
import { useDebounced } from '../hooks';
import { AnyOf, Between, FilterBar, type FilterKind, OneOf, rangeWords, Words } from './filters';
import type { Actions } from '../actions';
import type { CardHandlers } from './drag';
import type { Model } from '../model';
import type { CataloguePage, CatalogueQuery, CatalogueRow, DeckSection, EditorState, LimitedEdition } from '../protocol';
import { store, stored } from '../storage';
import { addCard, choosesPrintings, preferredPrinting } from './preferred';
import { CARD_TYPES } from './decklist';
import { t, type TextKey } from '../text';

export const TYPES: [string, TextKey][] = [['any', 'lblWebCatalogueAnyType'], ...CARD_TYPES.map(([type, , heading]): [string, TextKey] => [type.toLowerCase(), heading])];
const SORTS: [string, TextKey][] = [['name', 'lblWebEditorSortName'], ['mv', 'lblWebCatalogueSortManaValue'], ['colour', 'lblWebEditorSortColour'],
  ['type', 'lblWebCatalogueSortType']];
const VIEW_KEY = 'forge.catalogueView';
// Forge's card search syntax, as desktop's card search reads it
const SEARCH_TIPS: [string, TextKey][] = [
  ['bolt', 'lblWebCatalogueTipName'],
  ['c:bg', 'lblWebCatalogueTipColours'],
  ['c=bg', 'lblWebCatalogueTipOnlyColours'],
  ['t:creature', 'lblCreature'],
  ['o:"draw a card"', 'lblWebCatalogueTipRulesText'],
  ['mv<=3', 'lblWebCatalogueTipManaValue'],
  ['kw:flying', 'lblWebCatalogueTipKeyword'],
  ['r:rare', 'lblRare'],
  ['s:mh2', 'lblWebCatalogueTipSet'],
  ['-t:land', 'lblWebCatalogueTipNot'],
  ['t:elf | t:goblin', 'lblWebCatalogueTipOr'],
  ['Enter', 'lblWebCatalogueTipEnter'],
];
const BASICS = new Set(['Plains', 'Island', 'Swamp', 'Mountain', 'Forest', 'Wastes']);

/** Pages through the catalogue: the first page whenever deps change, and the next as the list scrolls near its end. Returns the list's scroll handler. */
export function useCataloguePages(actions: Actions, query: Omit<CatalogueQuery, 't' | 'request' | 'offset'>, page: CataloguePage | null,
  deps: unknown[]): (e: Event) => void {
  const asked = useRef(0);
  useEffect(() => {
    asked.current = 0;
    actions.queryCatalogue({ ...query, offset: 0 });
  }, deps);
  const shown = page?.rows.length ?? 0;
  return e => {
    const el = e.currentTarget as HTMLElement;
    if (page && shown < page.total && asked.current < shown && el.scrollTop + el.clientHeight > el.scrollHeight - 600) {
      asked.current = shown;
      actions.queryCatalogue({ ...query, offset: shown });
    }
  };
}

/** How many copies of each card the deck holds, by name, across every section. A limited deck's sideboard is its pool, not the deck. */
export function countsInDeck(state: EditorState): Map<string, number> {
  const counts = new Map<string, number>();
  for (const c of [...state.commanders, ...state.main.flatMap(g => g.cards), ...(state.limited ? [] : state.sideboard)]) {
    counts.set(c.name, (counts.get(c.name) ?? 0) + c.count);
  }
  return counts;
}

/** The copy limit the editor shows; the server enforces the exact one, which a few cards raise. A main-only collection holds one of each, and a collection that counts copies no more than owned. */
export function copyLimit(state: EditorState, name: string, owned?: number | null): number {
  if (BASICS.has(name)) return Infinity;
  if (state.mainOnly) return 1;
  const rule = state.copyLimit ?? Infinity;
  return owned == null ? rule : Math.min(rule, owned);
}

/** How many more of a card the deck can take: what the pool has left in limited mode, otherwise the copy limit's room. */
function roomFor(state: EditorState, row: CatalogueRow, inDeck: number): number {
  if (state.limited) return state.sideboard.find(c => c.name === row.name)?.count ?? 0;
  return copyLimit(state, row.name, row.count) - inDeck;
}

export function Catalogue({ model, actions, state, handlers, adding, stopAdding }: {
  model: Model; actions: Actions; state: EditorState; handlers: CardHandlers; adding: DeckSection | null; stopAdding: () => void;
}) {
  const [typed, setTyped] = useState('');
  const text = useDebounced(typed);
  const [colours, setColours] = useState<Set<string>>(() => new Set());
  const [type, setType] = useState('any');
  const [sort, setSort] = useState('name');
  // A deck built from a collection may hold any card owned; the list opens on those its commander's colours allow, when it has one
  const owned = !!state.collection;
  const leader = state.mainOnly ? state.identity || 'C' : state.identity || undefined;
  const opening: CatalogueFilter = owned && leader ? { ...NO_FILTER, identity: leader } : NO_FILTER;
  const [filter, setFilter] = useState<CatalogueFilter>(opening);
  const [view, setView] = useState<'cards' | 'table'>(() => storedView());
  const query = { text, colours: [...colours].join(''), type, filters: asSyntax(filter), sort, showAll: filter.showAll,
    identity: owned ? filter.identity ?? undefined : undefined, sets: filter.sets, format: filter.format ?? undefined,
    section: adding ?? undefined };
  // A section beyond the main deck lists its own kind of card, any number of each, and no commander is chosen from it
  const extra = adding ? state.sections.find(s => s.section === adding) : undefined;
  const wanted = state.commanderWanted && !extra;

  // A page of a collection's own list, left from that page, is not this deck's catalogue
  const page = model.catalogue && !model.catalogue.source ? model.catalogue : null;
  // The deck's rules decide which cards show, so a new commander or check asks again
  const more = useCataloguePages(actions, query, page, [text, query.colours, type, query.filters, sort, filter.showAll, query.identity,
    filter.sets.join(), filter.format, state.check, state.identity, state.commanderWanted, adding]);
  // A card shows in the printing this browser prefers for it, in the tile, the table, the preview and the drag alike
  const rows = (page?.rows ?? []).map(row => {
    const key = choosesPrintings(state) ? preferredPrinting(row.name) : undefined;
    return key ? { ...row, image: key } : row;
  });
  const counts = extra ? new Map(extra.cards.map(c => [c.name, c.count])) : countsInDeck(state);
  const room = (row: CatalogueRow, count: number) => (extra ? Infinity : roomFor(state, row, count));
  const canAdd = (row: CatalogueRow) => !row.problem && room(row, counts.get(row.name) ?? 0) > 0;
  const top = text.trim() ? rows.find(canAdd) : undefined;
  const searched = page?.ranked ? text.trim() : '';
  const add = (name: string, to: DeckSection = adding ?? 'Main') => {
    liftCard(name, to);
    addCard(actions, state, name, to);
  };
  const remove = (name: string) => actions.edit({ op: 'remove', name, from: adding ?? 'Main', count: 1 });
  const makeCommander = (name: string) => actions.edit({ op: 'commander', name, count: 1 });
  const kinds = catalogueKinds(state, model, actions).filter(k => !extra || (k.id !== 'identity' && k.id !== 'unusable'));
  const narrowed = !!typed || colours.size > 0 || type !== 'any'
    || kinds.some(k => k.id !== 'identity' && k.chip(filter) !== null && !k.fixed?.(filter));
  const clearAll = () => {
    setTyped('');
    setColours(new Set());
    setType('any');
    setFilter(opening);
  };
  return (
    <section class="catalogue" data-zone="catalogue">
      <div class="cat-bar">
        <FilterBar kinds={kinds} filter={filter} set={setFilter} clearAll={clearAll} narrowed={narrowed}>
          <span class="search-wrap">
            <input class="find" type="search" placeholder={t('lblWebCatalogueSearch')} autocomplete="off" value={typed}
              aria-describedby="search-tip"
              onInput={e => setTyped(e.currentTarget.value)}
              onKeyDown={e => { if (e.key === 'Enter' && top) (wanted ? makeCommander : add)(top.name); }} />
            <table class="search-tip" id="search-tip" role="tooltip">
              <tbody>
                {SEARCH_TIPS.map(([key, what]) => <tr key={key}><th>{key}</th><td>{t(what)}</td></tr>)}
              </tbody>
            </table>
          </span>
          <ColourToggles label={t('lblColors')} colourless pressed={c => colours.has(c)} toggle={c => setColours(toggled(colours, c))} />
          <select class="type-by" aria-label={t('lblCardType')} value={type} onChange={e => setType(e.currentTarget.value)}>
            {TYPES.map(([id, name]) => <option key={id} value={id}>{t(name)}</option>)}
          </select>
        </FilterBar>
        {extra && (
          <p class="adding-to">{t('lblWebEditorAddingTo', extra.name)}
            <button class="link" onClick={stopAdding}>{t('lblWebEditorBackToMain')}</button></p>
        )}
      </div>
      <div class="count-row">
        <p class={wanted ? 'shown commanders-only' : 'shown'}>{shownLine(page?.total, searched, wanted)}</p>
        {text.trim() && page?.ranked !== false
          ? <select class="sort-by" aria-label={t('lblSort')} disabled><option>{t('lblWebEditorSortBestMatch')}</option></select>
          : <select class="sort-by" aria-label={t('lblSort')} value={sort} onChange={e => setSort(e.currentTarget.value)}>
              {SORTS.map(([id, name]) => <option key={id} value={id}>{t(name)}</option>)}
            </select>}
        <span class="seg" role="group" aria-label={t('lblView')}>
          <button aria-pressed={view === 'cards'} onClick={() => { setView('cards'); store(VIEW_KEY, 'cards'); }}>{t('lblCards')}</button>
          <button aria-pressed={view === 'table'} onClick={() => { setView('table'); store(VIEW_KEY, 'table'); }}>{t('lblWebCatalogueViewTable')}</button>
        </span>
      </div>
      <div class={view === 'cards' ? 'cat-grid' : 'cat-table'} data-list onScroll={more}>
        {page && page.total === 0 && <Empty text={searched} identity={state.identity} hidden={page.hiddenBySwitch}
          showThem={() => setFilter(f => ({ ...f, showAll: true }))} />}
        {view === 'cards'
          ? rows.map(row => <Tile key={row.name} row={row} count={counts.get(row.name) ?? 0} top={row === top}
              limit={extra ? Infinity : copyLimit(state, row.name, row.count)} room={room(row, counts.get(row.name) ?? 0)} limited={state.limited}
              commanderWanted={wanted} mainOnly={state.mainOnly || !!extra} add={add} remove={remove}
              makeCommander={makeCommander} handlers={handlers} />)
          : <Table rows={rows} counts={counts} room={room} add={add} remove={remove} handlers={handlers} />}
      </div>
    </section>
  );
}

/** Numbers from and to, either left open; null is no limit at all. */
type Range = { from: number | null; to: number | null } | null;

/** What the catalogue is narrowed by beyond the bar. Each becomes Forge's search syntax, which the server reads. */
interface CatalogueFilter {
  mv: Range;
  rarity: string[];
  /** Set codes, any printing in which counts. The server reads these and the format, which search syntax cannot say. */
  sets: string[];
  format: string | null;
  rules: string | null;
  subtype: string | null;
  power: Range;
  toughness: Range;
  colourCount: Range;
  showAll: boolean;
  /** A collection's deck: only cards within these colours, as letters. */
  identity: string | null;
}

const NO_FILTER: CatalogueFilter = {
  mv: null, rarity: [], sets: [], format: null, rules: null, subtype: null, power: null, toughness: null, colourCount: null, showAll: false,
  identity: null,
};
const RARITIES: [string, TextKey][] = [['common', 'lblCommon'], ['uncommon', 'lblUncommon'], ['rare', 'lblRare'], ['mythic', 'lblMythic']];

const between = (key: string, r: Range) => [r?.from != null ? `${key}>=${r.from}` : '', r?.to != null ? `${key}<=${r.to}` : ''];
const quoted = (key: string, words: string | null) => (words ? `${key}:"${words.replace(/"/g, '')}"` : '');

/** The filters as search syntax, every term of which a card must meet. */
function asSyntax(f: CatalogueFilter): string {
  return [
    ...between('mv', f.mv), ...between('pow', f.power), ...between('tou', f.toughness), ...between('c', f.colourCount),
    f.rarity.length ? `(${f.rarity.map(r => `r:${r}`).join(' | ')})` : '',
    quoted('o', f.rules), quoted('t', f.subtype),
  ].filter(Boolean).join(' ');
}

/** Everything the catalogue can be narrowed by beyond the bar, as desktop's card filters have it. */
function catalogueKinds(state: EditorState, model: Model, actions: Actions): FilterKind<CatalogueFilter>[] {
  const rules = t('lblWebFilterGroupRules');
  const numbers = t('lblWebFilterGroupNumbers');
  const printing = t('lblWebFilterGroupPrinting');
  const deck = t('lblWebFilterGroupDeck');
  const range = (id: 'mv' | 'power' | 'toughness' | 'colourCount', group: string, label: string): FilterKind<CatalogueFilter> => ({
    id, group, label,
    chip: f => rangeWords(f[id]?.from ?? null, f[id]?.to ?? null),
    clear: f => ({ ...f, [id]: null }),
    panel: (f, set, done) => <Between from={f[id]?.from ?? null} to={f[id]?.to ?? null} apply={(from, to) => {
      set({ ...f, [id]: from === null && to === null ? null : { from, to } });
      done();
    }} />,
  });
  const words = (id: 'rules' | 'subtype', group: string, label: string, placeholder: string): FilterKind<CatalogueFilter> => ({
    id, group, label,
    chip: f => f[id],
    clear: f => ({ ...f, [id]: null }),
    panel: (f, set, done) => <Words value={f[id] ?? ''} placeholder={placeholder} apply={v => { set({ ...f, [id]: v }); done(); }} />,
  });
  const kinds: FilterKind<CatalogueFilter>[] = [
    words('rules', rules, t('lblRulesText'), t('lblWebFilterRulesTextHint')),
    words('subtype', rules, t('lblWebFilterSubtype'), t('lblWebFilterSubtypeHint')),
    range('mv', numbers, t('lblCMC')),
    range('power', numbers, t('lblPower')),
    range('toughness', numbers, t('lblToughness')),
    range('colourCount', numbers, t('lblColorCount')),
    {
      id: 'rarity', group: printing, label: t('lblRarity'),
      chip: f => (f.rarity.length ? RARITIES.filter(([r]) => f.rarity.includes(r)).map(([, name]) => t(name)).join(', ') : null),
      clear: f => ({ ...f, rarity: [] }),
      panel: (f, set, done) => <AnyOf options={RARITIES.map(([r, name]) => [r, t(name)] as const)} value={f.rarity}
        apply={rarity => { set({ ...f, rarity }); done(); }} />,
    },
    {
      id: 'sets', group: printing, label: t('lblSets'),
      chip: f => (f.sets.length ? f.sets.join(', ') : null),
      clear: f => ({ ...f, sets: [] }),
      panel: (f, set, done) => {
        if (!model.cardSets) actions.askCardSets();
        return <SetsPanel sets={model.cardSets} value={f.sets} apply={sets => { set({ ...f, sets }); done(); }} />;
      },
    },
    {
      // Forge's formats under its own headings, blocks among them, as desktop's format filter lists them
      id: 'format', group: printing, label: t('lblFormat'),
      chip: f => f.format,
      clear: f => ({ ...f, format: null }),
      panel: (f, set, done) => (
        <div class="fopts" role="radiogroup">
          {model.cardPools.flatMap(group => [
            <span key={group.name} class="mhead">{group.name}</span>,
            ...group.formats.map(name => (
              <button key={name} role="radio" aria-checked={f.format === name} class={f.format === name ? 'fopt on' : 'fopt'}
                onClick={() => { set({ ...f, format: name }); done(); }}><i class="dot" />{name}</button>
            )),
          ])}
        </div>
      ),
    },
    {
      // The commander's colours are the deck's rule, so the chip is fixed, except on a collection's deck, which has no such rule
      id: 'identity', group: deck, label: t('lblColorIdentity'),
      chip: f => (state.collection ? f.identity?.split('').join(' ') ?? null : state.identity.split('').join(' ')),
      from: () => t('lblCommander'),
      fixed: () => !state.collection,
      clear: f => ({ ...f, identity: null }),
      panel: (f, set, done) => (state.collection
        ? <OneOf options={[['within', (state.identity || 'C').split('').join(' ')], ['any', t('lblWebCollectionAnyColours')]] as const}
            value={f.identity ? 'within' : 'any'} pick={v => { set({ ...f, identity: v === 'within' ? state.identity || 'C' : null }); done(); }} />
        : <p class="fnote">{t('lblWebFilterSetByCommander')}</p>),
    },
    {
      id: 'source', group: deck, label: t('lblCollection'),
      chip: () => (state.collection ? '' : null),
      from: () => '',
      fixed: () => true,
      clear: f => f,
      panel: () => <p class="fnote">{t('lblWebCollectionOwnedNote')}</p>,
    },
    {
      id: 'unusable', group: deck, label: t('lblWebFilterUnusable'),
      chip: f => (f.showAll ? t('lblWebFilterShown') : null),
      clear: f => ({ ...f, showAll: false }),
      panel: (f, set, done) => <OneOf options={[['show', t('lblWebFilterShown')], ['hide', t('lblWebFilterHidden')]] as const}
        value={f.showAll ? 'show' : 'hide'} pick={v => { set({ ...f, showAll: v === 'show' }); done(); }} />,
    },
  ];
  // A limited deck's catalogue is its pool, where nothing is unusable; only a chosen commander sets an identity
  return kinds.filter(k => (k.id !== 'unusable' || !(state.limited || state.collection)) && (k.id !== 'source' || !!state.collection)
    && (k.id !== 'identity' || !!state.collection || (!!state.identity && !state.commanderWanted)));
}

/** Every set, found by name or code. A ticked set stays listed whatever is typed; Apply applies them. */
function SetsPanel({ sets, value, apply }: { sets: readonly LimitedEdition[] | null; value: readonly string[]; apply: (codes: string[]) => void }) {
  const [find, setFind] = useState('');
  const [ticked, setTicked] = useState<Set<string>>(() => new Set(value));
  if (!sets) return <p class="fnote">{t('lblWebEventLoading')}</p>;
  const words = find.trim().toLowerCase();
  const shown = sets.filter(s => ticked.has(s.code) || !words || s.name.toLowerCase().includes(words) || s.code.toLowerCase() === words);
  return (
    <>
      <input type="search" autoFocus placeholder={t('lblWebFilterSetFind')} value={find} onInput={e => setFind(e.currentTarget.value)} />
      <div class="fopts">
        {shown.map(s => (
          <button key={s.code} role="checkbox" aria-checked={ticked.has(s.code)} class={ticked.has(s.code) ? 'fopt box on' : 'fopt box'} onClick={() => setTicked(toggled(ticked, s.code))}><i class="dot" /><span class="fset">{s.name}</span><span class="fcode">{s.code}</span></button>
        ))}
      </div>
      <div class="fapply"><button class="primary" onClick={() => apply([...ticked])}>{t('lblWebFilterApply')}</button></div>
    </>
  );
}

/** Lifts the card being added out of the catalogue, as a draft pick is lifted, to fly to its line in the deck. */
function liftCard(name: string, to: string): void {
  const at = document.querySelector<HTMLElement>(`[data-from="catalogue"][data-card="${CSS.escape(name)}"]`);
  const img = at?.querySelector<HTMLImageElement>('img:not(.sym)');
  if (img) lift(img, `${to}:${name}`);
  else if (at?.dataset.image) liftFromLine(imageUrl(at.dataset.image), at.getBoundingClientRect(), `${to}:${name}`);
}

function Tile({ row, count, top, limit, room, limited, commanderWanted, mainOnly, add, remove, makeCommander, handlers }: {
  row: CatalogueRow; count: number; top: boolean; limit: number; room: number; limited: boolean; commanderWanted: boolean; mainOnly: boolean;
  add: (name: string, to?: DeckSection) => void; remove: (name: string) => void; makeCommander: (name: string) => void;
  handlers: CardHandlers;
}) {
  const full = room <= 0;
  return (
    <div class={`slot${count ? ' indeck' : ''}${row.problem ? ' bad' : ''}${top ? ' top' : ''}`} data-card={row.name} data-from="catalogue">
      {top && <span class="enter">{commanderWanted ? t('lblWebCataloguePressEnterChoose') : t('lblWebCataloguePressEnterAdd')}</span>}
      <button class="tile" data-image={row.image} data-act={commanderWanted ? t('lblWebCardMenuMakeCommander') : t('lblAdd')}
        {...handlers(row.name, 'catalogue', row.image, 1)} onClick={() => (commanderWanted ? makeCommander(row.name) : add(row.name))}>
        <span class="tile-name">{row.name}</span>
        <img loading="lazy" alt="" draggable={false} src={imageUrl(row.image)} onError={hideBroken} />
        {count > 0 && <span class="badge">{count}</span>}
        {row.isNew && <span class="new-card">{t('lblNew')}</span>}
      </button>
      {row.problem && <span class="flag">! {row.problem}</span>}
      {commanderWanted
        ? <div class="under centred"><button class="side" onClick={() => makeCommander(row.name)}>{t('lblWebCatalogueMakeCommander')}</button></div>
        : (
          <div class="under">
            <button class="step" disabled={!count} aria-label={t('lblWebCatalogueRemoveOne', row.name)} onClick={() => remove(row.name)}>&minus;</button>
            <span class={count ? 'n' : 'n zero'}>{count}</span>
            <button class="step" disabled={full || !!row.problem} aria-label={t('lblWebCatalogueAddOne', row.name)} onClick={() => add(row.name)}>+</button>
            {limited
              ? <span class="why">{room ? t('lblWebCatalogueLeft', room) : t('lblWebCatalogueNoneLeft')}</span>
              : full && limit < Infinity
                ? <span class="why">{limit === row.count ? t('lblWebCatalogueNoneLeft') : limit === 1 ? t('lblWebCatalogueSingleton') : t('lblWebNOfM', count, limit)}</span>
                : mainOnly ? null : <button class="side" disabled={!!row.problem} onClick={() => add(row.name, 'Sideboard')}>{t('lblSide')}</button>}
          </div>
        )}
    </div>
  );
}

function Table({ rows, counts, room, add, remove, handlers }: {
  rows: CatalogueRow[]; counts: Map<string, number>; room: (row: CatalogueRow, count: number) => number;
  add: (name: string) => void; remove: (name: string) => void; handlers: CardHandlers;
}) {
  return (
    <table>
      <thead><tr><th>{t('lblWebCatalogueInDeck')}</th><th>{t('lblName')}</th><th>{t('lblCost')}</th><th>{t('lblType')}</th>
        <th>{t('lblWebCataloguePT')}</th><th>{t('lblWebCatalogueMV')}</th></tr></thead>
      <tbody>
        {rows.map(row => {
          const count = counts.get(row.name) ?? 0;
          return (
            <tr key={row.name} class={row.problem ? 'bad' : undefined} data-image={row.image} data-card={row.name} data-from="catalogue"
              {...handlers(row.name, 'catalogue', row.image, 1)}>
              <td class="under">
                <button class="step" disabled={!count} onClick={() => remove(row.name)}>&minus;</button>
                <span class={count ? 'n' : 'n zero'}>{count}</span>
                <button class="step" disabled={!!row.problem || room(row, count) <= 0} onClick={() => add(row.name)}>+</button>
              </td>
              <td>{row.name}{row.problem && <span class="flag"> ! {row.problem}</span>}</td>
              <td><SymbolText text={row.cost} /></td>
              <td class="muted">{row.type}</td>
              <td>{row.pt ?? ''}</td>
              <td>{row.mv}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function Empty({ text, identity, hidden, showThem }: { text: string; identity: string; hidden: number; showThem: () => void }) {
  if (!hidden) {
    return <p class="none-found">{t('lblWebCatalogueNoMatchHint')}</p>;
  }
  const within = identity.split('').join(' ');
  const none = identity
    ? (text ? t('lblWebCatalogueNoMatchWithinText', within, text) : t('lblWebCatalogueNoMatchWithin', within))
    : (text ? t('lblWebCatalogueNoMatchText', text) : t('lblWebCatalogueNoMatch'));
  return (
    <div class="none-found">
      <b>{none}</b>
      <span>{t(hidden === 1 ? 'lblWebCatalogueHiddenOne' : 'lblWebCatalogueHidden', hidden)}</span>
      <button onClick={showThem}>{t('lblWebEditorShowThem')}</button>
    </div>
  );
}

// A deck without its commander lists only cards that could be one, and the count line is where that is said
function shownLine(total: number | undefined, text: string, commandersOnly: boolean): string {
  if (total === undefined) return t('lblWebCatalogueReading');
  const one = total === 1;
  if (commandersOnly) {
    return text
      ? t(one ? 'lblWebCatalogueCommandersOneNamed' : 'lblWebCatalogueCommandersNamed', total, text)
      : t(one ? 'lblWebCatalogueCommandersOne' : 'lblWebCatalogueCommanders', total);
  }
  return text
    ? t(one ? 'lblWebCatalogueCardsOneNamed' : 'lblWebCatalogueCardsNamed', total, text)
    : t(one ? 'lblWebOneCard' : 'lblWebNCards', total);
}

function storedView(): 'cards' | 'table' {
  return stored(VIEW_KEY) === 'table' ? 'table' : 'cards';
}
