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
  const [filter, setFilter] = useState<DeckFilter>({
    query: '', source: 'all', colours: new Set(), cardFormat: 'any', legalOnly: false, sort: 'name',
  });
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
  return (
    <div class="finder-back">
      <div class="finder">
        <header class="finder-head">
          <h2>Choose a deck</h2>
          <button class="dk-close" title="Close" onClick={close}>&times;</button>
        </header>
        <div class="finder-body">
          <div class="results">
            <div class="find-row">
              <input ref={find} class="find" type="search" placeholder="Search every deck by name" autocomplete="off"
                value={typed} onInput={e => setTyped(e.currentTarget.value)} />
              <label class="sort">Format
                <select class="format-by" value={filter.cardFormat} onChange={e => change({ cardFormat: e.currentTarget.value })}>
                  <option value="any">Any</option>
                  {model.cardFormats.map(f => <option key={f} value={f}>{f}</option>)}
                </select>
              </label>
              <label class="sort">Sort
                <select class="sort-by" value={filter.sort} onChange={e => change({ sort: e.currentTarget.value as SortKey })}>
                  {SORTS.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
                </select>
              </label>
            </div>
            <div class="facets">
              {[...sources].map(([id, count]) => (
                <button key={id} class="facet" aria-pressed={id === NET ? inNet : id === filter.source}
                  onClick={() => change({ source: id })}>
                  {id === 'all' ? 'All sources' : id === NET ? 'Net decks' : id}<span class="dk-count">{count}</span>
                </button>
              ))}
            </div>
            {/* The categories only appear once net decks are the ones being looked through */}
            {inNet && categories.size > 1 && (
              <div class="facets net">
                <button class="facet" aria-pressed={filter.source === NET} onClick={() => change({ source: NET })}>
                  Every category
                </button>
                {[...categories].map(([id, count]) => (
                  <button key={id} class="facet" aria-pressed={id === filter.source} onClick={() => change({ source: id })}>
                    {netName(id)}<span class="dk-count">{count}</span>
                  </button>
                ))}
              </div>
            )}
            {/* Core asks which category through a dialog on the host's screen, so only the host can answer it */}
            <button class="get-net" hidden={!model.host} onClick={() => actions.fetchNetDecks()}>Get net decks…</button>
            <div class="colours">
              {COLOURS.map(([letter, name]) => (
                <button key={letter} class="colour" title={name} aria-pressed={filter.colours.has(letter)} onClick={() => {
                  const colours = new Set(filter.colours);
                  if (!colours.delete(letter)) colours.add(letter);
                  change({ colours });
                }}>
                  <i class={`pip pip-${letter}`}>{letter}</i>
                  <span class="dk-count">{decks.filter(d => (d.colors ?? '').includes(letter)).length}</span>
                </button>
              ))}
            </div>
            <label class="legal-only">
              <input type="checkbox" checked={filter.legalOnly} onChange={e => change({ legalOnly: e.currentTarget.checked })} /> Playable decks only
            </label>
            <p class="shown">{list.length === decks.length ? `${decks.length} decks` : `${list.length} of ${decks.length} decks`}</p>
            <div class="dk-hits">
              {list.length
                ? list.map(d => <Hit key={d.key} deck={d} chosen={d.key === chosen} choose={() => setChosen(d.key)}
                  use={() => use(d.key)} />)
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

function Hit({ deck: d, chosen, choose, use }: { deck: DeckSummary; chosen: boolean; choose: () => void; use: () => void }) {
  // A generator has nothing to measure until it has built something, so it says what it is instead
  if (d.generated) {
    return (
      <button class="dk-hit generated" aria-pressed={chosen} onClick={choose} onDblClick={use}>
        <span class="dk-hit-name">{d.name}</span>
        <span class="pips"><Pips colors={d.colors} /></span>
        <span class="note">{d.note ?? ''}</span>
        <span class="tag">{d.source}</span>
      </button>
    );
  }
  // An illegal deck is marked rather than hidden, so nobody hunts the editor for a deck that is here
  return (
    <button class="dk-hit" aria-pressed={chosen} title={d.problem ?? ''} onClick={choose} onDblClick={use}>
      <span class="dk-hit-name">{d.name}</span>
      <span class="pips"><Pips colors={d.colors} /></span>
      <span class="size">{d.main}{d.sideboard ? `+${d.sideboard}` : ''}</span>
      <span class="deck-formats">{d.formats ?? ''}</span>
      <span class="tag">{d.source}</span>
      <span class={`legal ${d.problem ? 'no' : 'yes'}`}>{d.problem ? 'Illegal' : 'Legal'}</span>
    </button>
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
