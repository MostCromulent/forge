// Choosing a deck: one query runs across every source at once, and a panel beside the results shows the chosen deck's cards

import { useEffect, useRef, useState } from 'preact/hooks';
import { store, stored } from '../storage';
import { imageUrl } from '../images';
import { Curve, useDeckView } from './deckhalf';
import { CardGroup, SITES } from './importer';
import { ColourToggles, Pips, toggled } from '../symbols';
import { changeUi, ui } from '../ui';
import { DECK_FORMATS } from './editor';
import { normalize, rankByName } from '../search';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { Bracket, DeckDetails, DeckMatches, DeckSummary, Seat } from '../protocol';
import { Between, FilterBar, type FilterKind, OneOf, rangeWords, Words } from './filters';
import { t, type TextKey } from '../text';

const SEARCH_DEBOUNCE_MS = 200;
/** Every net-deck category is its own source, so they answer to one facet and list their categories under it. */
export const NET = 'net';
const isNet = (source: string) => source.startsWith(`${NET} `);
const netName = (source: string) => source.slice(NET.length + 1);
export type SortKey = 'name' | 'colors' | 'formats' | 'size' | 'legal' | 'bracket';
const SORTS: [SortKey, TextKey][] = [['name', 'lblWebEditorSortName'], ['colors', 'lblWebEditorSortColour'], ['formats', 'lblWebFinderSortFormat'],
  ['size', 'lblWebFinderSortSize'], ['legal', 'lblWebFinderSortLegalFirst'], ['bracket', 'lblWebFinderSortBracket']];

/** Numbers from and to, either left open; null is no limit at all. */
type Range = { from: number | null; to: number | null } | null;
/** The filters asked of the server, which alone reads every deck's cards. */
export type DeckQueryKind = 'card' | 'sideboard' | 'set';

export interface DeckFilter {
  /** As typed. While there is one, it orders the list instead of the sort. */
  query: string;
  /** A source's name, or 'all'. */
  source: string;
  /** Colour letters; a deck carrying all of them matches. None ticked matches every deck. */
  colours: ReadonlySet<string>;
  /** A card format the deck must be legal in, or 'any'. */
  cardFormat: string;
  legalOnly: boolean;
  /** The highest Commander bracket let through, or null for any. */
  bracket: number | null;
  card: string | null;
  sideboard: string | null;
  set: string | null;
  /** Colour identity letters, matched exactly or as the most a deck may have. */
  identity: { letters: string; exactly: boolean } | null;
  colourCount: Range;
  main: Range;
  side: Range;
  mana: Range;
  folder: string | null;
  favourites: boolean;
  sort: SortKey;
}

/** How the finder opens: every source, and only decks the lobby would accept. */
export const FINDER_DEFAULTS: DeckFilter = {
  query: '', source: 'all', colours: new Set(), cardFormat: 'any', legalOnly: true, bracket: null, card: null, sideboard: null,
  set: null, identity: null, colourCount: null, main: null, side: null, mana: null, folder: null, favourites: false, sort: 'name',
};

/** The folder a deck's key places it in, "" for none. A key is source:folder/name. */
export const folderOf = (key: string) => key.slice(key.indexOf(':') + 1, Math.max(key.indexOf(':') + 1, key.lastIndexOf('/')));
const within = (r: Range, n: number | null | undefined) =>
  !r || (n != null && (r.from === null || n >= r.from) && (r.to === null || n <= r.to));
const identityLetters = (d: DeckSummary) => (d.colors ?? '').replace('C', '');

/** The decks the filter lets through, in its order, where a generated deck has no cards yet so legality cannot rule it out. */
export function matchingDecks(decks: readonly DeckSummary[], f: DeckFilter,
  found: (kind: DeckQueryKind, value: string) => ReadonlySet<string> | undefined = () => undefined): DeckSummary[] {
  const asked = ([['card', f.card], ['sideboard', f.sideboard], ['set', f.set]] as const)
    .filter(([, value]) => value !== null).map(([kind, value]) => found(kind, value!) ?? new Set<string>());
  const list = decks.filter(d => (f.source === 'all' || d.source === f.source || (f.source === NET && isNet(d.source)))
    && (!f.colours.size || [...f.colours].every(c => (d.colors ?? '').includes(c)))
    && (d.generated || !f.legalOnly || !d.problem)
    && (d.generated || f.cardFormat === 'any' || (d.legalIn ?? []).includes(f.cardFormat))
    && (d.generated || f.bracket === null || (d.bracket ?? 0) <= f.bracket)
    && asked.every(keys => keys.has(d.key))
    && (!f.identity || (f.identity.exactly
      ? [...'WUBRG'].every(c => f.identity!.letters.includes(c) === identityLetters(d).includes(c)) && !!d.colors
      : !!d.colors && [...identityLetters(d)].every(c => f.identity!.letters.includes(c))))
    && within(f.colourCount, d.colors ? identityLetters(d).length : null)
    && within(f.main, d.main) && within(f.side, d.sideboard) && within(f.mana, d.averageMana)
    && (f.folder === null || folderOf(d.key) === f.folder)
    && (!f.favourites || !!d.favourite));
  const byName = (a: DeckSummary, b: DeckSummary) => a.name.localeCompare(b.name);
  const by: Record<SortKey, (a: DeckSummary, b: DeckSummary) => number> = {
    name: byName,
    colors: (a, b) => (a.colors ?? '').localeCompare(b.colors ?? '') || byName(a, b),
    formats: (a, b) => (a.formats ?? '').localeCompare(b.formats ?? '') || byName(a, b),
    size: (a, b) => (b.main ?? 0) - (a.main ?? 0) || byName(a, b),
    legal: (a, b) => Number(!!a.problem) - Number(!!b.problem) || byName(a, b),
    bracket: (a, b) => (a.bracket ?? 9) - (b.bracket ?? 9) || byName(a, b),
  };
  if (normalize(f.query)) {
    return rankByName(list.map(d => d.name), f.query).map(i => list[i]);
  }
  return list.sort(by[f.sort] ?? byName);
}

/** How many decks each source holds, with every net-deck category counted as the one source. */
export function sourceCounts(decks: readonly DeckSummary[]): Map<string, number> {
  const sources = new Map<string, number>([['all', decks.length]]);
  for (const d of decks) {
    const group = isNet(d.source) ? NET : d.source;
    sources.set(group, (sources.get(group) ?? 0) + 1);
  }
  return sources;
}

/** The deck finder, which chooses a seat's deck, or with no seat lists one format's decks and opens the editor instead. */
export function DeckFinder({ model, actions, seat, close }: {
  model: Model; actions: Actions; seat?: { index: number; seat: Seat }; close: () => void;
}) {
  const decks = model.decks ?? [];
  const [chosen, setChosen] = useState<string | null>(seat?.seat.deck ?? null);
  const [dropping, setDropping] = useState(false);
  // A deck file on its way to being saved, kept until the list shows it, in case its name is taken
  const [file, setFile] = useState<string | null>(null);
  const before = useRef<ReadonlySet<string>>(new Set());
  const picker = useRef<HTMLInputElement>(null);
  const [adding, setAdding] = useState(false);
  const [link, setLink] = useState('');
  const linkField = useRef<HTMLInputElement>(null);
  const [deleting, setDeleting] = useState(false);
  const [typed, setTyped] = useState('');
  const format = model.lobby?.format ?? ui.browse?.format ?? 'Constructed';
  // A Commander table's bracket limit is where the list starts, and can be lifted as its warning can be ignored
  const tableBracket = model.lobby?.format === 'Commander' && model.lobby.maxBracket < 5 ? model.lobby.maxBracket : null;
  const [filter, setFilter] = useState<DeckFilter>(() => ({ ...FINDER_DEFAULTS, colours: new Set(), bracket: tableBracket }));
  const change = (part: Partial<DeckFilter>) => setFilter(f => ({ ...f, ...part }));
  const find = useRef<HTMLInputElement>(null);
  const [peek, setPeek] = useState<{ image: string; left: number; top: number } | null>(null);

  // A long list costs an image request per row, so typing waits for a pause
  useEffect(() => {
    const timer = setTimeout(() => change({ query: typed }), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [typed]);
  useEffect(() => {
    find.current?.focus();
  }, []);
  // Escape reaches the dialog before the finder only while the focus is inside it
  useEffect(() => {
    if (adding) linkField.current?.focus();
  }, [adding]);
  // Only the server reads every deck's cards, and asks again once the list is rebuilt, since that renews the keys
  useEffect(() => { if (filter.card) actions.deckQuery('card', filter.card); }, [filter.card, model.decks]);
  useEffect(() => { if (filter.sideboard) actions.deckQuery('sideboard', filter.sideboard); }, [filter.sideboard, model.decks]);
  useEffect(() => { if (filter.set) actions.deckQuery('set', filter.set); }, [filter.set, model.decks]);
  // The chosen deck's card list is the server's to read
  useEffect(() => {
    if (chosen) actions.askDeckDetails(chosen);
    setDeleting(false);
  }, [chosen]);

  const use = (key = chosen) => {
    if (key && seat) {
      actions.setSeat(seat.index, { deck: key });
      close();
    }
  };
  const summary = decks.find(d => d.key === chosen);
  // Only your own saved decks can go; a precon, a generator or a net deck is only ever copied
  const deletable = !!summary && !summary.readOnly && !summary.generated;
  const edit = () => {
    if (!chosen) return;
    actions.openEditor({ key: chosen, seat: seat?.index, copy: !!summary?.readOnly });
    close();
  };
  // From a seat the importer replaces the finder, but from the start page the finder stays beneath to show the saved deck
  const importer = (more: { text?: string; url?: string; sync?: boolean } = {}) => {
    if (seat) close();
    changeUi(u => { u.importer = { from: seat ? 'seat' : 'start', seat: seat?.index, ...more }; });
  };
  // A deck file is a deck already, so it is saved as it stands; any other text is a list for the importer to read
  const take = (dropped: File) => void dropped.text().then(text => {
    if (!/^\s*\[metadata\]/im.test(text)) return importer({ text });
    before.current = new Set(decks.map(d => d.key));
    setFile(text);
    actions.addDeckFile(text, format);
  });
  // The saved deck is the one the list did not have, or when it replaced a deck, the one of its name
  useEffect(() => {
    if (!file || model.nameTaken) return;
    const name = /^Name=(.*)$/im.exec(file)?.[1].trim();
    const added = decks.find(d => !before.current.has(d.key)) ?? decks.find(d => !d.readOnly && d.name === name);
    if (added) {
      setChosen(added.key);
      requestAnimationFrame(() => document.querySelector('.dk-hit[aria-pressed="true"]')?.scrollIntoView({ block: 'nearest' }));
    }
    setFile(null);
  }, [model.decks]);
  const fetchLink = (url: string) => {
    if (!url.trim()) return;
    setAdding(false);
    importer({ url: url.trim() });
  };
  const list = matchingDecks(decks, filter, (kind, value) => {
    const m = model.deckMatches[kind];
    return m?.value === value ? keySet(m) : undefined;
  });
  const kinds = finderKinds(model, decks, actions, format === 'Commander');
  const sources = sourceCounts(decks);
  const categories = new Map<string, number>();
  for (const d of decks) {
    if (isNet(d.source)) categories.set(d.source, (categories.get(d.source) ?? 0) + 1);
  }
  const inNet = filter.source === NET || isNet(filter.source);
  const details = model.deckDetails?.key === chosen ? model.deckDetails : null;
  const narrowed = filter.source !== 'all' || filter.colours.size > 0 || !!typed || kinds.some(k => k.chip(filter) !== null && !k.fixed?.(filter));
  const clearAll = () => {
    setTyped('');
    setFilter(f => ({ ...FINDER_DEFAULTS, colours: new Set(), sort: f.sort }));
  };
  // A deck from those the filters let through, and a different one each press while there is another to give
  const random = () => {
    const pool = list.length > 1 ? list.filter(d => d.key !== chosen) : list;
    if (!pool.length) return;
    setChosen(pool[Math.floor(Math.random() * pool.length)].key);
    requestAnimationFrame(() => document.querySelector('.dk-hit[aria-pressed="true"]')?.scrollIntoView({ block: 'nearest' }));
  };
  return (
    <div class="finder-back">
      <div class={dropping ? 'finder dropping' : 'finder'}
        onDragOver={e => {
          if (!e.dataTransfer?.types.includes('Files')) return;
          e.preventDefault();
          setDropping(true);
        }}
        onDragLeave={e => { if (e.currentTarget === e.target) setDropping(false); }}
        onDrop={e => {
          const dropped = e.dataTransfer?.files[0];
          setDropping(false);
          if (!dropped) return;
          e.preventDefault();
          take(dropped);
        }}>
        <header class="finder-head">
          <h2>{seat ? t('lblWebLobbyChooseDeck') : t('lblDecks')}</h2>
          {!seat && (
            <label class="legality set">
              {t('lblFormat')}
              <span class="pill-select"><select value={format} onChange={e => {
                const next = e.currentTarget.value;
                changeUi(u => { u.browse = { format: next }; });
                actions.browseFormat(next);
              }}>
                {DECK_FORMATS.map(([id, name]) => <option key={id} value={id}>{t(name)}</option>)}
              </select></span>
            </label>
          )}
          {/* A Limited table plays event pools; the host chooses whether other events' pools may be played too */}
          {model.lobby?.limited?.activeEventId && (
            <label class="legality set">
              <input type="checkbox" checked={model.lobby.limited.eventDecksOnly} disabled={!model.lobby.host}
                onChange={e => actions.eventDecksOnly(e.currentTarget.checked)} /> {t('lblWebFinderEventDecksOnly')}
            </label>
          )}
          <span class="head-tools">
            <button onClick={() => setAdding(true)}>{t('lblWebFinderNewDeck')}</button>
            <input ref={picker} type="file" accept=".dck" hidden onChange={e => {
              const picked = e.currentTarget.files?.[0];
              e.currentTarget.value = '';
              setAdding(false);
              if (picked) take(picked);
            }} />
            <button class="dk-close" title={t('lblClose')} onClick={close}>&times;</button>
          </span>
        </header>
        <div class="finder-body">
          <nav class="rail" aria-label={t('lblWebFinderFilters')}>
            <section>
              <h4>{t('lblSource')}</h4>
              {[...sources].map(([id, count]) => (
                <button key={id} class="source" aria-pressed={id === NET ? inNet : id === filter.source}
                  onClick={() => change({ source: id })}>
                  <span>{sourceName(id)}</span><span class="dk-count">{count}</span>
                </button>
              ))}
              {/* The categories only appear once net decks are the ones being looked through */}
              {inNet && categories.size > 1 && (
                <div class="net-cats">
                  <button class="source" aria-pressed={filter.source === NET} onClick={() => change({ source: NET })}>
                    <span>{t('lblWebFinderEveryCategory')}</span>
                  </button>
                  {[...categories].map(([id, count]) => (
                    <button key={id} class="source" aria-pressed={id === filter.source} onClick={() => change({ source: id })}>
                      <span>{netName(id)}</span><span class="dk-count">{count}</span>
                    </button>
                  ))}
                </div>
              )}
              {/* Core asks which category through a dialog on the host's screen, so only the host can answer it */}
              <button class="get-net" hidden={!model.host} onClick={() => actions.fetchNetDecks()}>{t('lblWebFinderDownloadNetDecks')}</button>
            </section>
          </nav>
          <div class="results">
            <FilterBar kinds={kinds} filter={filter} set={setFilter} clearAll={clearAll} narrowed={narrowed}>
              <input ref={find} class="find" type="search" placeholder={t('lblWebFinderSearch')} autocomplete="off"
                value={typed} onInput={e => setTyped(e.currentTarget.value)} />
              <ColourToggles label={t('lblColors')} colourless pressed={c => filter.colours.has(c)}
                toggle={c => change({ colours: toggled(filter.colours, c) })}
                title={(c, name) => t('lblWebFinderColourDecks', name, decks.filter(d => (d.colors ?? '').includes(c)).length)} />
            </FilterBar>
            <div class="count-row">
              <p class="shown">{list.length === decks.length ? t('lblWebMenuDecksCount', decks.length) : t('lblWebFinderDeckCountOf', list.length, decks.length)}</p>
              {/* A search orders the list by how well each name matches, so the sort waits until it is cleared */}
              {normalize(filter.query)
                ? <select class="sort-by" aria-label={t('lblSort')} disabled><option>{t('lblWebEditorSortBestMatch')}</option></select>
                : <select class="sort-by" aria-label={t('lblSort')} value={filter.sort} onChange={e => change({ sort: e.currentTarget.value as SortKey })}>
                    {SORTS.filter(([id]) => id !== 'bracket' || decks.some(d => d.bracket != null))
                      .map(([id, name]) => <option key={id} value={id}>{t(name)}</option>)}
                  </select>}
              <button class="random" disabled={!list.length} title={t('lblWebFinderRandomTip')} onClick={random}>
                <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="4" /><circle cx="8.5" cy="8.5" r="1.2" /><circle cx="15.5" cy="15.5" r="1.2" /><circle cx="12" cy="12" r="1.2" /><circle cx="15.5" cy="8.5" r="1.2" /><circle cx="8.5" cy="15.5" r="1.2" /></svg>
                {t('lblRandom')}
              </button>
            </div>
            <div class="dk-hits">
              {list.length
                ? list.map(d => <Hit key={d.key} deck={d} chosen={d.key === chosen} choose={() => setChosen(d.key)}
                  use={() => use(d.key)} source={filter.source === 'all'} />)
                : <p class="none">{t('lblWebFinderNoMatch')}</p>}
            </div>
          </div>
          <aside class="dk-chosen" onPointerOver={e => setPeek(peekAt(e, '.finder') ?? peek)} onPointerLeave={() => setPeek(null)}>
            {!chosen ? <p class="none">{t('lblWebFinderPickHint')}</p>
              : !details ? <p class="none">{t('lblWebFinderReading')}</p>
                : <Chosen details={details} />}
          </aside>
        </div>
        <footer class="finder-foot">
          {summary?.linked && summary.sourceUrl && (
            <span class="linked-line">
              {summary.synced ? t('lblWebFinderFromSynced', summary.linked, ago(summary.synced)) : t('lblWebFinderFrom', summary.linked)}
              <button class="small" onClick={() => importer({ url: summary.sourceUrl, sync: true })}>{t('lblWebFinderSyncNow')}</button>
            </span>
          )}
          {deleting && summary ? (
            <span class="delete-line">
              <b>{t('lblWebEditorDeleteDeck', summary.name)}</b> {t('lblWebEditorCannotUndo')}
              <button onClick={() => setDeleting(false)}>{t('lblCancel')}</button>
              <button class="danger" onClick={() => {
                actions.deleteDeck(summary.key);
                setChosen(null);
                setDeleting(false);
              }}>{t('lblDelete')}</button>
            </span>
          ) : deletable && <button class="delete-deck" onClick={() => setDeleting(true)}>{t('lblDelete')}</button>}
          <button class="cancel" onClick={close}>{t('lblCancel')}</button>
          {seat
            ? <>
                <button disabled={!chosen} onClick={edit}>{summary?.readOnly ? t('lblWebFinderEditCopy') : t('lblEdit')}</button>
                <button class="use primary" disabled={!chosen} onClick={() => use()}>{t('lblWebFinderUseDeck')}</button>
              </>
            : <button class="primary" disabled={!chosen} onClick={edit}>{summary?.readOnly ? t('lblWebFinderEditCopy') : t('lblEdit')}</button>}
        </footer>
        {adding && (
          <div class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) setAdding(false); }}
            onKeyDown={e => {
              if (e.key !== 'Escape') return;
              e.stopPropagation();
              setAdding(false);
            }}>
            <div class="dialog new-deck">
              <div class="dialog-head">
                <h3>{t('lblWebFinderNewDeckTitle')}</h3>
                <button class="dk-close" title={t('lblClose')} onClick={() => setAdding(false)}>&times;</button>
              </div>
              <section>
                <h4>{t('lblWebFinderFromSite')}</h4>
                <p>{t('lblWebFinderFromSiteHint', SITES)}</p>
                <div class="linkfield">
                  <input ref={linkField} class="find" placeholder="https://moxfield.com/decks/…" value={link}
                    onInput={e => setLink(e.currentTarget.value)}
                    onPaste={e => { fetchLink(e.clipboardData?.getData('text') ?? ''); e.preventDefault(); }}
                    onKeyDown={e => { if (e.key === 'Enter') fetchLink(link); }} />
                  <button class="primary" onClick={() => fetchLink(link)}>{t('lblWebImportFetch')}</button>
                </div>
              </section>
              <section>
                <h4>{t('lblWebFinderOtherWays')}</h4>
                <button class="way" onClick={() => { actions.openEditor({ newFormat: format, seat: seat?.index }); close(); }}>
                  <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="3" width="11" height="15" rx="2" /><path d="M9 21h9a2 2 0 0 0 2-2V8" /><path d="M9.5 8v5M7 10.5h5" /></svg>
                  <span><b>{t('lblWebFinderBuild')}</b><small>{t('lblWebFinderBuildHint')}</small></span>
                </button>
                <button class="way" onClick={() => { setAdding(false); importer(); }}>
                  <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 6h12M8 12h12M8 18h8" /><circle cx="4" cy="6" r=".8" /><circle cx="4" cy="12" r=".8" /><circle cx="4" cy="18" r=".8" /></svg>
                  <span><b>{t('lblWebFinderPasteList')}</b><small>{t('lblWebFinderPasteListHint')}</small></span>
                </button>
                <button class="way" onClick={() => picker.current?.click()}>
                  <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5" /><path d="M12 17v-6M9.5 13.5 12 11l2.500 2.500" /></svg>
                  <span><b>{t('lblWebFinderAddFile')}</b><small>{t('lblWebFinderAddFileHint')}</small></span>
                </button>
              </section>
            </div>
          </div>
        )}
        {file && model.nameTaken && (
          <div class="backdrop">
            <div class="dialog">
              <h3>{t('lblWebImportNameTaken', model.nameTaken)}</h3>
              <div class="actions">
                <button onClick={() => setFile(null)}>{t('lblCancel')}</button>
                <button onClick={() => actions.addDeckFile(file, format, 'replace')}>{t('lblWebDraftReplaceIt')}</button>
                <button class="primary" onClick={() => actions.addDeckFile(file, format, 'keep')}>{t('lblWebImportKeepBoth')}</button>
              </div>
            </div>
          </div>
        )}
        {dropping && <div class="drop-over-finder">{t('lblWebFinderDropToImport')}</div>}
        {peek && <div class="deck-peek" style={{ left: `${peek.left}px`, top: `${peek.top}px` }}><img alt="" src={imageUrl(peek.image)} /></div>}
      </div>
    </div>
  );
}

const KEY_SETS = new WeakMap<DeckMatches, ReadonlySet<string>>();
const keySet = (m: DeckMatches) => KEY_SETS.get(m) ?? KEY_SETS.set(m, new Set(m.keys)).get(m)!;

/** Everything the finder can be narrowed by beyond the bar, as desktop's deck filters have it. */
function finderKinds(model: Model, decks: readonly DeckSummary[], actions: Actions, commander: boolean): FilterKind<DeckFilter>[] {
  const legality = t('lblWebFilterGroupLegality');
  const contents = t('lblWebFilterGroupContents');
  const colourSize = t('lblWebFilterGroupColourSize');
  const yours = t('lblWebFilterGroupYours');
  const fromTable = t('lblWebFilterTable');
  const range = (id: string, group: string, label: string, get: (f: DeckFilter) => Range,
    put: (f: DeckFilter, r: Range) => DeckFilter): FilterKind<DeckFilter> => ({
    id, group, label,
    chip: f => rangeWords(get(f)?.from ?? null, get(f)?.to ?? null),
    clear: f => put(f, null),
    panel: (f, set, done) => <Between from={get(f)?.from ?? null} to={get(f)?.to ?? null} apply={(from, to) => {
      set(put(f, from === null && to === null ? null : { from, to }));
      done();
    }} />,
  });
  const cards = (id: DeckQueryKind, group: string, label: string, placeholder: string): FilterKind<DeckFilter> => ({
    id, group, label,
    chip: f => f[id],
    clear: f => ({ ...f, [id]: null }),
    panel: (f, set, done) => <Words value={f[id] ?? ''} placeholder={placeholder} apply={v => { set({ ...f, [id]: v }); done(); }}
      names={id === 'set' ? undefined : model.cardNames} typing={id === 'set' ? undefined : actions.searchCards} />,
  });
  const folders = new Map<string, number>();
  for (const d of decks) {
    const folder = d.generated ? '' : folderOf(d.key);
    if (folder) folders.set(folder, (folders.get(folder) ?? 0) + 1);
  }
  const kinds: FilterKind<DeckFilter>[] = [
    {
      id: 'legal', group: legality, label: t('lblWebFinderLegalIn'),
      chip: f => model.deckCardPool ?? (f.cardFormat === 'any' ? null : f.cardFormat),
      // The lobby's card pool is the match's rule, so it is shown here but changed only there
      from: () => (model.deckCardPool ? fromTable : null),
      fixed: () => !!model.deckCardPool,
      clear: f => ({ ...f, cardFormat: 'any' }),
      panel: (f, set, done) => (model.deckCardPool
        ? <p class="fnote">{t('lblWebFilterSetAtTable')}</p>
        : <OneOf options={model.cardFormats.map(n => [n, n] as const)} value={f.cardFormat} pick={v => { set({ ...f, cardFormat: v }); done(); }} />),
    },
    {
      id: 'illegal', group: legality, label: t('lblWebFilterIllegalDecks'),
      chip: f => (f.legalOnly ? null : t('lblWebFilterShown')),
      clear: f => ({ ...f, legalOnly: true }),
      panel: (f, set, done) => <OneOf options={[['show', t('lblWebFilterShown')], ['hide', t('lblWebFilterHidden')]] as const}
        value={f.legalOnly ? 'hide' : 'show'} pick={v => { set({ ...f, legalOnly: v === 'hide' }); done(); }} />,
    },
    {
      id: 'bracket', group: legality, label: t('lblBracket'),
      chip: f => (f.bracket === null ? null : `≤ ${f.bracket}`),
      from: f => (model.lobby && f.bracket === model.lobby.maxBracket ? fromTable : null),
      clear: f => ({ ...f, bracket: null }),
      panel: (f, set, done) => <>
        <p class="fnote">{t('lblWebFilterBracketNote')}</p>
        <OneOf options={[1, 2, 3, 4].map(n => [n, t('lblWebBracketLevel', n)] as const)} value={f.bracket}
          pick={v => { set({ ...f, bracket: v }); done(); }} />
      </>,
    },
    cards('card', contents, t('lblWebFilterHasCard'), t('lblWebFilterCardName')),
    cards('sideboard', contents, t('lblWebFilterSideboardHasCard'), t('lblWebFilterCardName')),
    cards('set', contents, t('lblWebFilterFromSet'), t('lblWebFilterSetName')),
    {
      id: 'identity', group: colourSize, label: t('lblColorIdentity'),
      chip: f => f.identity && (f.identity.exactly ? t('lblWebFilterExactly', f.identity.letters || 'C') : t('lblWebFilterWithin', f.identity.letters || 'C')),
      clear: f => ({ ...f, identity: null }),
      panel: (f, set, done) => <IdentityPanel value={f.identity} apply={identity => { set({ ...f, identity }); done(); }} />,
    },
    range('colourCount', colourSize, t('lblColorCount'), f => f.colourCount, (f, colourCount) => ({ ...f, colourCount })),
    range('main', colourSize, t('lblWebFilterMainSize'), f => f.main, (f, main) => ({ ...f, main })),
    range('side', colourSize, t('lblWebFilterSideSize'), f => f.side, (f, side) => ({ ...f, side })),
    range('mana', colourSize, t('lblWebFilterAverageMana'), f => f.mana, (f, mana) => ({ ...f, mana })),
    {
      id: 'folder', group: yours, label: t('lblFolder'),
      chip: f => f.folder,
      clear: f => ({ ...f, folder: null }),
      panel: (f, set, done) => (folders.size
        ? <OneOf options={[...folders].map(([folder, n]) => [folder, `${folder} (${n})`] as const)} value={f.folder}
            pick={v => { set({ ...f, folder: v }); done(); }} />
        : <p class="fnote">{t('lblWebFilterNoFolders')}</p>),
    },
    {
      id: 'favourites', group: yours, label: t('ttFavorite'),
      chip: f => (f.favourites ? t('lblWebFilterStarred') : null),
      clear: f => ({ ...f, favourites: false }),
      panel: (f, set, done) => <OneOf options={[['on', t('lblWebFilterStarredOnly')]] as const} value={f.favourites ? 'on' : null}
        pick={() => { set({ ...f, favourites: true }); done(); }} />,
    },
  ];
  // Brackets are Commander's alone
  return commander ? kinds : kinds.filter(k => k.id !== 'bracket');
}

/** Colour identity: the colours, and whether a deck must have exactly them or may have fewer. */
function IdentityPanel({ value, apply }: { value: DeckFilter['identity']; apply: (v: DeckFilter['identity']) => void }) {
  const [letters, setLetters] = useState(value?.letters ?? '');
  const [exactly, setExactly] = useState(value?.exactly ?? false);
  return (
    <>
      <ColourToggles label={t('lblColors')} pressed={c => letters.includes(c)}
        toggle={letter => setLetters([...'WUBRG'].filter(c => (c === letter) !== letters.includes(c)).join(''))} />
      <OneOf options={[['within', t('lblWebFilterWithinNote')], ['exactly', t('lblWebFilterExactlyNote')]] as const}
        value={exactly ? 'exactly' : 'within'} pick={v => setExactly(v === 'exactly')} />
      <div class="fapply"><button class="primary" onClick={() => apply({ letters, exactly })}>{t('lblWebFilterApply')}</button></div>
    </>
  );
}

const SOURCE_NAMES: Record<string, TextKey> = {
  all: 'lblWebFinderSourceAll', [NET]: 'lblWebFinderSourceNet', yours: 'lblWebFinderSourceYours', device: 'lblWebFinderSourceDevice',
  linked: 'lblWebFinderSourceLinked', precons: 'lblWebFinderSourcePrecons', quest: 'lblWebFinderSourceQuest', generated: 'lblWebFinderSourceGenerated',
};
const sourceName = (id: string) => (SOURCE_NAMES[id] ? t(SOURCE_NAMES[id]) : id.charAt(0).toUpperCase() + id.slice(1));

// The source is only worth a column while every source is listed; with one picked, the rail already says it
function Hit({ deck: d, chosen, choose, use, source }: {
  deck: DeckSummary; chosen: boolean; choose: () => void; use: () => void; source: boolean;
}) {
  // A generator has nothing to measure until it has built something, so it says what it is instead
  if (d.generated) {
    return (
      <button class="dk-hit generated" aria-pressed={chosen} onClick={choose} onDblClick={use}>
        <Title deck={d} />
        <span class="note">{d.note ?? ''}</span>
        {source && <span class="tag">{d.source}</span>}
      </button>
    );
  }
  // Shown only when asked for, and then marked, so a deck that is here is never hunted for elsewhere
  return (
    <button class="dk-hit" aria-pressed={chosen} title={d.problem ?? ''} onClick={choose} onDblClick={use}>
      <Title deck={d} />
      <span class="size">{d.main}{d.sideboard ? `+${d.sideboard}` : ''}</span>
      {/* Commander decks are all one format, so there the column says each deck's bracket instead */}
      {d.bracket != null
        ? <span class="deck-formats"><BracketMark level={d.bracket} /></span>
        : <span class="deck-formats">{d.formats ?? ''}</span>}
      {source && <span class="tag">{d.source}</span>}
      <span class={`legal ${d.problem ? 'no' : 'yes'}`}>{d.problem ? t('lblWebFinderIllegal') : t('lblWebFinderLegal')}</span>
    </button>
  );
}

/** The deck's name with its colours under it. */
function Title({ deck }: { deck: DeckSummary }) {
  return (
    <span class="dk-hit-title">
      <span class="dk-hit-name">{deck.name}</span>
      <span class="pips"><Pips colors={deck.colors} /></span>
    </span>
  );
}

// Shows a hovered card beside the line pointed at, pushed left of it so the cursor never covers the card
export function peekAt(e: PointerEvent, frameSelector: string): { image: string; left: number; top: number } | null {
  const el = e.target instanceof Element ? e.target.closest<HTMLElement>('[data-image]') : null;
  const frame = el?.closest(frameSelector)?.getBoundingClientRect();
  if (!el || !frame) {
    return null;
  }
  const box = el.getBoundingClientRect();
  // The width every preview shares, which grows with the window (theme.css)
  const peekW = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--preview-w')) || 280;
  const peekH = peekW * 88 / 63;
  const before = box.left - frame.left - peekW - 16;
  return {
    image: el.dataset.image ?? '',
    // With no room to its left, as for a card at the edge of a grid, it goes to the right instead
    left: before >= 12 ? before : Math.min(frame.width - peekW - 12, box.right - frame.left + 16),
    top: Math.min(frame.height - peekH - 12, Math.max(12, box.top - frame.top - peekH / 2)),
  };
}

function Chosen({ details }: { details: DeckDetails }) {
  const s = details.stats;
  // Game changers are marked where they sit in the list, whether or not the bracket's reasons are open
  const changers = new Set(details.bracket?.reasons.find(r => r.kind === 'gameChangers')?.cards ?? []);
  const [cards, view] = useDeckView();
  return (
    <>
      <div class="dk-chosen-head">
        <div class="dk-chosen-title">
          <h3>{details.name} <span class="pips"><Pips colors={details.colors} /></span></h3>
          <span class="seg view-seg" role="group" aria-label={t('lblWebEditorShowDeckAs')}>
            <button aria-pressed={cards} onClick={() => view(true)}>{t('lblCards')}</button>
            <button aria-pressed={!cards} onClick={() => view(false)}>{t('lblWebDraftList')}</button>
          </span>
        </div>
        <p class="sizes">{s.sideboard ? t('lblWebEditorSizes', s.total, s.sideboard, s.lands) : t('lblWebFinderSizesNoSideboard', s.total, s.lands)}</p>
        <p class={details.problem ? 'verdict no' : 'verdict yes'}>{details.problem ?? t('lblWebFinderLegalForFormat')}</p>
        {details.bracket && <BracketPanel bracket={details.bracket} />}
        <div class="stats">
          <Curve curve={s.curve} creatures={s.creatures} px={42} />
          <div class="types">
            {s.types.map(ty => <div key={ty.name} class="type"><span>{ty.name}</span><b>{ty.count}</b></div>)}
            <div class="type avg"><span>{t('lblWebFilterAverageMana')}</span><b>{s.averageMana}</b></div>
          </div>
        </div>
      </div>
      <div class={cards ? 'dk-cards deck-cols' : 'dk-cards'}>
        {details.main.map(g => <CardGroup key={g.heading} heading={g.heading} cards={g.cards} marked={changers} stacked={cards} />)}
        {details.sideboard.length > 0 && <CardGroup heading={t('lblSideboard')} cards={details.sideboard} marked={changers} stacked={cards} />}
      </div>
    </>
  );
}

/** A deck's Commander bracket as a small mark: 4 in gold, 3 rimmed in gold, 1 and 2 plain. */
export function BracketMark({ level }: { level: number }) {
  return <span class={`bracket-mark b${level}`} title={t('lblWebBracketTip', level)}>{level}</span>;
}

const BRACKET_OPEN_KEY = 'forge.bracketOpen';

/** A Commander deck's bracket in one row that opens to each reason and its cards, remembering whether it was left open. */
function BracketPanel({ bracket }: { bracket: Bracket }) {
  const [open, setOpen] = useState(() => stored(BRACKET_OPEN_KEY) === 'open');
  const toggle = () => {
    store(BRACKET_OPEN_KEY, open ? 'shut' : 'open');
    setOpen(!open);
  };
  const brief = bracket.reasons.filter(r => r.raises > 0).map(r => r.brief).join(' · ');
  return (
    <div class="bracket">
      <button class="bracket-sum" aria-expanded={open} onClick={toggle}>
        <span class="bnum">{bracket.level}</span>
        <span class="bmid">
          <span class="bline"><b>{t('lblWebBracketLevel', bracket.level)}</b><span>{t('lblWebBracketSuggestedMinimum')}</span></span>
          <span class="bwhy">{brief || t('lblWebBracketNothingRaises')}</span>
        </span>
        <span class="bsteps" aria-hidden="true">
          {[1, 2, 3, 4, 5].map(n => <i key={n} class={n < bracket.level ? 'on' : n === bracket.level ? 'mark' : ''} />)}
        </span>
        <svg class="chev" viewBox="0 0 24 24" aria-hidden="true"><path d="M6.5 9.5l5.5 5.5 5.5-5.5" /></svg>
      </button>
      {open && (
        <div class="bracket-more">
          {bracket.reasons.map(r => (
            <div key={r.kind} class="reason">
              <b>{r.title} · {r.cards.length}</b>
              {r.raises > 0 && <span class="to">{t('lblWebBracketRaisesTo', r.raises)}</span>}
              <span class="cards">{r.cards.join(', ')}</span>
              {r.why && <span class="because">{r.why}</span>}
            </div>
          ))}
          {bracket.clear.length > 0 && <p class="clearline"><b>{t('lblWebBracketClear')}</b> {bracket.clear.join(', ')}</p>}
        </div>
      )}
    </div>
  );
}

/** How long ago a time was, in the words a person uses: "just now", "3 days ago". */
function ago(millis: number): string {
  const minutes = Math.round((Date.now() - millis) / 60000);
  if (minutes < 2) return t('lblWebFinderJustNow');
  if (minutes < 60) return t('lblWebFinderMinutesAgo', minutes);
  const hours = Math.round(minutes / 60);
  if (hours < 24) return t(hours === 1 ? 'lblWebFinderHourAgo' : 'lblWebFinderHoursAgo', hours);
  const days = Math.round(hours / 24);
  return t(days === 1 ? 'lblWebFinderDayAgo' : 'lblWebFinderDaysAgo', days);
}
