// Choosing a deck. One query runs across every source at once, so knowing a deck's name is enough and
// picking a source is optional. Colour and source narrow the list rather than hiding anything for good.
// The panel beside the results carries the deck's statistics and its whole card list, which is why there
// is no separate window for reading one.

import { useEffect, useRef, useState } from 'preact/hooks';
import { store, stored } from './storage';
import { imageUrl } from './images';
import { Curve } from './deckhalf';
import { CardGroup } from './importer';
import { Pip, Pips } from './symbols';
import { changeUi, ui } from './ui';
import { DECK_FORMATS } from './editor';
import { normalize, rankByName } from './search';
import type { Actions } from './actions';
import type { Model } from './model';
import type { Bracket, DeckDetails, DeckSummary, Seat } from './protocol';
import { t, type TextKey } from './text';

const SEARCH_DEBOUNCE_MS = 200;
// Colour identity, in the order Magic writes it, plus colourless
const COLOURS: [string, TextKey][] = [['W', 'lblWhite'], ['U', 'lblBlue'], ['B', 'lblBlack'], ['R', 'lblRed'], ['G', 'lblGreen'],
  ['C', 'lblWebEditorColourless']];
/** Every net-deck category is its own source, so they answer to one facet and list their categories under it. */
export const NET = 'net';
const isNet = (source: string) => source.startsWith(`${NET} `);
const netName = (source: string) => source.slice(NET.length + 1);
export type SortKey = 'name' | 'colors' | 'formats' | 'size' | 'legal' | 'bracket';
const SORTS: [SortKey, TextKey][] = [['name', 'lblWebEditorSortName'], ['colors', 'lblWebEditorSortColour'], ['formats', 'lblWebFinderSortFormat'],
  ['size', 'lblWebFinderSortSize'], ['legal', 'lblWebFinderSortLegalFirst'], ['bracket', 'lblWebFinderSortBracket']];

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
  sort: SortKey;
}

/** How the finder opens: every source, and only decks the lobby would accept. */
export const FINDER_DEFAULTS: DeckFilter = {
  query: '', source: 'all', colours: new Set(), cardFormat: 'any', legalOnly: true, sort: 'name',
};

/** The decks the filter lets through, in its order. A generator has built nothing yet, so legality cannot rule it out. */
export function matchingDecks(decks: readonly DeckSummary[], f: DeckFilter): DeckSummary[] {
  const list = decks.filter(d => (f.source === 'all' || d.source === f.source || (f.source === NET && isNet(d.source)))
    && (!f.colours.size || [...f.colours].every(c => (d.colors ?? '').includes(c)))
    && (d.generated || !f.legalOnly || !d.problem)
    && (d.generated || f.cardFormat === 'any' || (d.legalIn ?? []).includes(f.cardFormat)));
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

/**
 * The deck finder. With a seat it chooses that seat's deck; opened from the start page it has no seat, lists one format's
 * decks, and its main button opens the editor instead.
 */
export function DeckFinder({ model, actions, seat, close }: {
  model: Model; actions: Actions; seat?: { index: number; seat: Seat }; close: () => void;
}) {
  const decks = model.decks ?? [];
  const [chosen, setChosen] = useState<string | null>(seat?.seat.deck ?? null);
  const [dropping, setDropping] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [typed, setTyped] = useState('');
  const [filter, setFilter] = useState<DeckFilter>({ ...FINDER_DEFAULTS, colours: new Set() });
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
  const format = model.lobby?.format ?? ui.browse?.format ?? 'Constructed';
  // From a seat the importer takes the finder's place, since importing puts the deck on the seat; from the start page
  // the finder stays beneath it, to show the deck once it is saved
  const importer = (more: { text?: string; url?: string; sync?: boolean } = {}) => {
    if (seat) close();
    changeUi(u => { u.importer = { from: seat ? 'seat' : 'start', seat: seat?.index, ...more }; });
  };
  const list = matchingDecks(decks, filter);
  const sources = sourceCounts(decks);
  const categories = new Map<string, number>();
  for (const d of decks) {
    if (isNet(d.source)) categories.set(d.source, (categories.get(d.source) ?? 0) + 1);
  }
  const inNet = filter.source === NET || isNet(filter.source);
  const details = model.deckDetails?.key === chosen ? model.deckDetails : null;
  const narrowed = filter.source !== 'all' || filter.colours.size > 0 || filter.cardFormat !== 'any' || !filter.legalOnly || !!typed;
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
          const file = e.dataTransfer?.files[0];
          setDropping(false);
          if (!file) return;
          e.preventDefault();
          void file.text().then(text => importer({ text }));
        }}>
        <header class="finder-head">
          <h2>{seat ? t('lblWebFinderChooseDeck') : t('lblWebFinderDecks')}</h2>
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
            <button onClick={() => { actions.openEditor({ newFormat: format, seat: seat?.index }); close(); }}>{t('lblWebFinderNewDeck')}</button>
            <button onClick={() => importer()}>{t('lblImport')}</button>
            <button class="dk-close" title={t('lblClose')} onClick={close}>&times;</button>
          </span>
        </header>
        <div class="finder-body">
          <nav class="rail" aria-label={t('lblWebFinderFilters')}>
            <section>
              <h4>{t('lblWebFinderSource')}</h4>
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
            <section>
              <h4>{t('lblWebEditorColours')}</h4>
              <div class="colours" role="group" aria-label={t('lblWebEditorColours')}>
                {COLOURS.map(([letter, name]) => (
                  <button key={letter} class="colour" aria-label={t(name)} aria-pressed={filter.colours.has(letter)}
                    title={t('lblWebFinderColourDecks', t(name), decks.filter(d => (d.colors ?? '').includes(letter)).length)} onClick={() => {
                      const colours = new Set(filter.colours);
                      if (!colours.delete(letter)) colours.add(letter);
                      change({ colours });
                    }}>
                    <Pip letter={letter} />
                  </button>
                ))}
              </div>
              <p class="rail-note">{t('lblWebFinderEveryTickedColour')}</p>
            </section>
            <section>
              <h4>{t('lblWebFinderLegalIn')}</h4>
              {/* The lobby's card pool is the match's rule, so it is shown here but changed only there */}
              {model.deckCardPool
                ? <p class="pinned">{model.deckCardPool}<span>{t('lblWebFinderSetInLobby')}</span></p>
                : <select class="format-by" value={filter.cardFormat} onChange={e => change({ cardFormat: e.currentTarget.value })}>
                    <option value="any">{t('lblWebFinderAnyFormat')}</option>
                    {model.cardFormats.map(f => <option key={f} value={f}>{f}</option>)}
                  </select>}
            </section>
            <label class="legal-only">
              <input type="checkbox" role="switch" checked={!filter.legalOnly} onChange={e => change({ legalOnly: !e.currentTarget.checked })} />
              {t('lblWebFinderShowIllegal')}
            </label>
            <button class="clear" hidden={!narrowed} onClick={() => {
              setTyped('');
              setFilter(f => ({ ...FINDER_DEFAULTS, colours: new Set(), sort: f.sort }));
            }}>{t('lblWebEditorClearFilters')}</button>
          </nav>
          <div class="results">
            <div class="find-row">
              <input ref={find} class="find" type="search" placeholder={t('lblWebFinderSearch')} autocomplete="off"
                value={typed} onInput={e => setTyped(e.currentTarget.value)} />
              {/* A search orders the list by how well each name matches, so the sort waits until it is cleared */}
              {normalize(filter.query)
                ? <select class="sort-by" aria-label={t('lblWebEditorSort')} disabled><option>{t('lblWebEditorSortBestMatch')}</option></select>
                : <select class="sort-by" aria-label={t('lblWebEditorSort')} value={filter.sort} onChange={e => change({ sort: e.currentTarget.value as SortKey })}>
                    {SORTS.filter(([id]) => id !== 'bracket' || decks.some(d => d.bracket != null))
                      .map(([id, name]) => <option key={id} value={id}>{t(name)}</option>)}
                  </select>}
              <button class="random" disabled={!list.length} title={t('lblWebFinderRandomTip')} onClick={random}>
                <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="4" /><circle cx="8.5" cy="8.5" r="1.2" /><circle cx="15.5" cy="15.5" r="1.2" /><circle cx="12" cy="12" r="1.2" /><circle cx="15.5" cy="8.5" r="1.2" /><circle cx="8.5" cy="15.5" r="1.2" /></svg>
                {t('lblRandom')}
              </button>
            </div>
            <p class="shown">{list.length === decks.length ? t('lblWebFinderDeckCount', decks.length) : t('lblWebFinderDeckCountOf', list.length, decks.length)}</p>
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
        {dropping && <div class="drop-over-finder">{t('lblWebFinderDropToImport')}</div>}
        {peek && <div class="deck-peek" style={{ left: `${peek.left}px`, top: `${peek.top}px` }}><img alt="" src={imageUrl(peek.image)} /></div>}
      </div>
    </div>
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

// Hovering a card in the list shows it, the way hovering one on the table does: beside the line being pointed at,
// pushed left of it so the cursor never covers the card
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
  return (
    <>
      <div class="dk-chosen-head">
        <h3>{details.name} <span class="pips"><Pips colors={details.colors} /></span></h3>
        <p class="sizes">{s.sideboard ? t('lblWebEditorSizes', s.total, s.sideboard, s.lands) : t('lblWebFinderSizesNoSideboard', s.total, s.lands)}</p>
        <p class={details.problem ? 'verdict no' : 'verdict yes'}>{details.problem ?? t('lblWebFinderLegalForFormat')}</p>
        {details.bracket && <BracketPanel bracket={details.bracket} />}
        <div class="stats">
          <Curve curve={s.curve} creatures={s.creatures} px={42} />
          <div class="types">
            {s.types.map(ty => <div key={ty.name} class="type"><span>{ty.name}</span><b>{ty.count}</b></div>)}
            <div class="type avg"><span>{t('lblWebFinderAverageManaValue')}</span><b>{s.averageMana}</b></div>
          </div>
        </div>
      </div>
      <div class="dk-cards">
        {details.main.map(g => <CardGroup key={g.heading} heading={g.heading} cards={g.cards} marked={changers} />)}
        {details.sideboard.length > 0 && <CardGroup heading={t('lblSideboard')} cards={details.sideboard} marked={changers} />}
      </div>
    </>
  );
}

/** A deck's Commander bracket as a small mark: 4 in gold, 3 rimmed in gold, 1 and 2 plain. */
export function BracketMark({ level }: { level: number }) {
  return <span class={`bracket-mark b${level}`} title={t('lblWebBracketTip', level)}>{level}</span>;
}

const BRACKET_OPEN_KEY = 'forge.bracketOpen';

/**
 * A Commander deck's bracket in one row: the number, what raised it, and where it sits from 1 to 5. Opened, each
 * reason with the cards behind it, as desktop's bracket view lists them. Whether it was left open is remembered.
 */
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
