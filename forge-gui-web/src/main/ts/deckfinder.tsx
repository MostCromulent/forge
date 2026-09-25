// Choosing a deck. One query runs across every source at once, so knowing a deck's name is enough and
// picking a source is optional. Colour and source narrow the list rather than hiding anything for good.
// The panel beside the results carries the deck's statistics and its whole card list, which is why there
// is no separate window for reading one.

import { useEffect, useRef, useState } from 'preact/hooks';
import { imageUrl } from './images';
import { Pips } from './symbols';
import type { Actions } from './actions';
import type { Model } from './model';
import type { DeckCard, DeckDetails, DeckSummary, Seat } from './protocol';

const SEARCH_DEBOUNCE_MS = 200;
const PEEK_W = 240;
const PEEK_H = 336;
// Colour identity, in the order Magic writes it, plus colourless
const COLOURS: [string, string][] = [['W', 'White'], ['U', 'Blue'], ['B', 'Black'], ['R', 'Red'], ['G', 'Green'], ['C', 'Colourless']];
/** Every net-deck category is its own source, so they answer to one facet and list their categories under it. */
export const NET = 'net';
const isNet = (source: string) => source.startsWith(`${NET} `);
const netName = (source: string) => source.slice(NET.length + 1);
export type SortKey = 'name' | 'colors' | 'formats' | 'size' | 'legal';
const SORTS: [SortKey, string][] = [['name', 'Name'], ['colors', 'Colour'], ['formats', 'Format'], ['size', 'Size'], ['legal', 'Legal first']];

export interface DeckFilter {
  /** Lower case; a deck's name must contain it. */
  query: string;
  /** A source's name, or 'all'. */
  source: string;
  /** Colour letters; a deck carrying any of them matches. None ticked matches every deck. */
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
    && (!f.query || d.name.toLowerCase().includes(f.query))
    && (!f.colours.size || [...f.colours].some(c => (d.colors ?? '').includes(c)))
    && (d.generated || !f.legalOnly || !d.problem)
    && (d.generated || f.cardFormat === 'any' || (d.legalIn ?? []).includes(f.cardFormat)));
  const byName = (a: DeckSummary, b: DeckSummary) => a.name.localeCompare(b.name);
  const by: Record<SortKey, (a: DeckSummary, b: DeckSummary) => number> = {
    name: byName,
    colors: (a, b) => (a.colors ?? '').localeCompare(b.colors ?? '') || byName(a, b),
    formats: (a, b) => (a.formats ?? '').localeCompare(b.formats ?? '') || byName(a, b),
    size: (a, b) => (b.main ?? 0) - (a.main ?? 0) || byName(a, b),
    legal: (a, b) => Number(!!a.problem) - Number(!!b.problem) || byName(a, b),
  };
  return list.sort(by[f.sort] ?? byName);
}

export function DeckFinder({ model, actions, index, seat, close }: {
  model: Model; actions: Actions; index: number; seat: Seat; close: () => void;
}) {
  const decks = model.decks ?? [];
  const [chosen, setChosen] = useState<string | null>(seat.deck ?? null);
  const [typed, setTyped] = useState('');
  const [filter, setFilter] = useState<DeckFilter>({ ...FINDER_DEFAULTS, colours: new Set() });
  const change = (part: Partial<DeckFilter>) => setFilter(f => ({ ...f, ...part }));
  const find = useRef<HTMLInputElement>(null);
  const [peek, setPeek] = useState<{ image: string; left: number; top: number } | null>(null);

  // A long list costs an image request per row, so typing waits for a pause
  useEffect(() => {
    const timer = setTimeout(() => change({ query: typed.trim().toLowerCase() }), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [typed]);
  useEffect(() => {
    find.current?.focus();
  }, []);
  // The chosen deck's card list is the server's to read
  useEffect(() => {
    if (chosen) actions.askDeckDetails(chosen);
  }, [chosen]);

  const use = (key = chosen) => {
    if (key) {
      actions.setSeat(index, { deck: key });
      close();
    }
  };
  const list = matchingDecks(decks, filter);
  const sources = new Map<string, number>([['all', decks.length]]);
  const categories = new Map<string, number>();
  for (const d of decks) {
    const group = isNet(d.source) ? NET : d.source;
    sources.set(group, (sources.get(group) ?? 0) + 1);
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
      <div class="finder">
        <header class="finder-head">
          <h2>Choose a deck</h2>
          <button class="dk-close" title="Close" onClick={close}>&times;</button>
        </header>
        <div class="finder-body">
          <nav class="rail" aria-label="Filters">
            <section>
              <h4>Source</h4>
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
                    <span>Every category</span>
                  </button>
                  {[...categories].map(([id, count]) => (
                    <button key={id} class="source" aria-pressed={id === filter.source} onClick={() => change({ source: id })}>
                      <span>{netName(id)}</span><span class="dk-count">{count}</span>
                    </button>
                  ))}
                </div>
              )}
              {/* Core asks which category through a dialog on the host's screen, so only the host can answer it */}
              <button class="get-net" hidden={!model.host} onClick={() => actions.fetchNetDecks()}>+ Download net decks</button>
            </section>
            <section>
              <h4>Colours</h4>
              <div class="colours" role="group" aria-label="Colours">
                {COLOURS.map(([letter, name]) => (
                  <button key={letter} class="colour" aria-label={name} aria-pressed={filter.colours.has(letter)}
                    title={`${name}: ${decks.filter(d => (d.colors ?? '').includes(letter)).length} decks`} onClick={() => {
                      const colours = new Set(filter.colours);
                      if (!colours.delete(letter)) colours.add(letter);
                      change({ colours });
                    }}>
                    <i class={`pip pip-${letter}`}>{letter}</i>
                  </button>
                ))}
              </div>
              <p class="rail-note">Decks with any ticked colour</p>
            </section>
            <section>
              <h4>Legal in</h4>
              {/* The lobby's Legality is the match's rule, so it is shown here but changed only there */}
              {model.deckLegality
                ? <p class="pinned">{model.deckLegality}<span>set in the lobby</span></p>
                : <select class="format-by" value={filter.cardFormat} onChange={e => change({ cardFormat: e.currentTarget.value })}>
                    <option value="any">Any format</option>
                    {model.cardFormats.map(f => <option key={f} value={f}>{f}</option>)}
                  </select>}
            </section>
            <label class="legal-only">
              <input type="checkbox" role="switch" checked={!filter.legalOnly} onChange={e => change({ legalOnly: !e.currentTarget.checked })} />
              Show illegal decks too
            </label>
            <button class="clear" hidden={!narrowed} onClick={() => {
              setTyped('');
              setFilter(f => ({ ...FINDER_DEFAULTS, colours: new Set(), sort: f.sort }));
            }}>Clear filters</button>
          </nav>
          <div class="results">
            <div class="find-row">
              <input ref={find} class="find" type="search" placeholder="Search deck names" autocomplete="off"
                value={typed} onInput={e => setTyped(e.currentTarget.value)} />
              <select class="sort-by" aria-label="Sort" value={filter.sort} onChange={e => change({ sort: e.currentTarget.value as SortKey })}>
                {SORTS.map(([id, name]) => <option key={id} value={id}>{`Sort: ${name}`}</option>)}
              </select>
              <button class="random" disabled={!list.length} title="Pick a deck from those shown" onClick={random}>
                <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="4" /><circle cx="8.5" cy="8.5" r="1.2" /><circle cx="15.5" cy="15.5" r="1.2" /><circle cx="12" cy="12" r="1.2" /><circle cx="15.5" cy="8.5" r="1.2" /><circle cx="8.5" cy="15.5" r="1.2" /></svg>
                Random
              </button>
            </div>
            <p class="shown">{list.length === decks.length ? `${decks.length} decks` : `${list.length} of ${decks.length} decks`}</p>
            <div class="dk-hits">
              {list.length
                ? list.map(d => <Hit key={d.key} deck={d} chosen={d.key === chosen} choose={() => setChosen(d.key)}
                  use={() => use(d.key)} source={filter.source === 'all'} />)
                : <p class="none">No deck matches. Clear a filter, or search a different name.</p>}
            </div>
          </div>
          <aside class="dk-chosen" onPointerOver={e => setPeek(peekAt(e) ?? peek)} onPointerLeave={() => setPeek(null)}>
            {!chosen ? <p class="none">Pick a deck on the left and its cards appear here.</p>
              : !details ? <p class="none">Reading the deck…</p>
                : <Chosen details={details} />}
          </aside>
        </div>
        <footer class="finder-foot">
          <button class="cancel" onClick={close}>Cancel</button>
          <button class="use primary" disabled={!chosen} onClick={() => use()}>Use this deck</button>
        </footer>
        {peek && <div class="deck-peek" style={{ left: `${peek.left}px`, top: `${peek.top}px` }}><img alt="" src={imageUrl(peek.image)} /></div>}
      </div>
    </div>
  );
}

const SOURCE_NAMES: Record<string, string> = {
  all: 'All decks', [NET]: 'Net decks', yours: 'Your decks', precons: 'Preconstructed', quest: 'Quest opponents', generated: 'Generated',
};
const sourceName = (id: string) => SOURCE_NAMES[id] ?? id.charAt(0).toUpperCase() + id.slice(1);

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
      <span class="deck-formats">{d.formats ?? ''}</span>
      {source && <span class="tag">{d.source}</span>}
      <span class={`legal ${d.problem ? 'no' : 'yes'}`}>{d.problem ? 'Illegal' : 'Legal'}</span>
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

// Bar heights are pixels because a percentage would resolve against an auto-sized row and collapse
const CURVE_PX = 42;

// Hovering a card in the list shows it, the way hovering one on the table does: beside the line being pointed at,
// pushed left of it so the cursor never covers the card
function peekAt(e: PointerEvent): { image: string; left: number; top: number } | null {
  const el = e.target instanceof Element ? e.target.closest<HTMLElement>('.dk-line') : null;
  const frame = el?.closest('.finder')?.getBoundingClientRect();
  if (!el || !frame) {
    return null;
  }
  const box = el.getBoundingClientRect();
  return {
    image: el.dataset.image ?? '',
    left: Math.max(12, box.left - frame.left - PEEK_W - 16),
    top: Math.min(frame.height - PEEK_H - 12, Math.max(12, box.top - frame.top - PEEK_H / 2)),
  };
}

function Chosen({ details }: { details: DeckDetails }) {
  const s = details.stats;
  const tallest = Math.max(1, ...s.curve);
  return (
    <>
      <div class="dk-chosen-head">
        <h3>{details.name} <span class="pips"><Pips colors={details.colors} /></span></h3>
        <p class="sizes">{s.main} cards{s.sideboard ? ` · ${s.sideboard} sideboard` : ''} · {s.lands} lands</p>
        <p class={details.problem ? 'verdict no' : 'verdict yes'}>{details.problem ?? 'Legal for this format.'}</p>
        <div class="stats">
          <div class="curve">
            <h4>Mana curve</h4>
            <div class="bars">
              {s.curve.map((n, i) => {
                // The last bucket holds everything at that mana value and above
                const label = i === s.curve.length - 1 ? `${i}+` : `${i}`;
                const h = n === 0 ? 2 : Math.max(3, Math.round((n / tallest) * CURVE_PX));
                return <span key={i} class="bar" title={`${n} at ${label}`}><i style={{ height: `${h}px` }} /><em>{label}</em></span>;
              })}
            </div>
          </div>
          <div class="types">
            {s.types.map(t => <div key={t.name} class="type"><span>{t.name}</span><b>{t.count}</b></div>)}
            <div class="type avg"><span>Average mana value</span><b>{s.averageMana}</b></div>
          </div>
        </div>
      </div>
      <div class="dk-cards">
        {details.main.map(g => <Group key={g.heading} heading={g.heading} cards={g.cards} />)}
        {details.sideboard.length > 0 && <Group heading="Sideboard" cards={details.sideboard} />}
      </div>
    </>
  );
}

function Group({ heading, cards }: { heading: string; cards: DeckCard[] }) {
  return (
    <div class="group">
      <h4>{heading}<span>{cards.reduce((n, c) => n + c.count, 0)}</span></h4>
      {cards.map((c, i) => <div key={i} class="dk-line" data-image={c.image}><span class="n">{c.count}</span>{c.name}</div>)}
    </div>
  );
}
