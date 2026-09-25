// The editor's left half: every card the deck could take, as card images or as a table. The filters are the deck
// finder's rail laid across the top. Cards the deck can't use are left out until the switch asks for them, and a
// search that finds nothing says which filter hid the match.

import { useEffect, useRef, useState } from 'preact/hooks';
import { imageUrl } from './images';
import { SymbolText } from './symbols';
import type { Actions } from './actions';
import type { CardHandlers } from './drag';
import type { Model } from './model';
import type { CatalogueRow, EditorState } from './protocol';
import { store, stored } from './storage';

const SEARCH_DEBOUNCE_MS = 200;
const COLOURS: [string, string][] = [['W', 'White'], ['U', 'Blue'], ['B', 'Black'], ['R', 'Red'], ['G', 'Green'], ['C', 'Colourless']];
const TYPES: [string, string][] = [['any', 'Any type'], ['creature', 'Creatures'], ['planeswalker', 'Planeswalkers'],
  ['instant', 'Instants'], ['sorcery', 'Sorceries'], ['artifact', 'Artifacts'], ['enchantment', 'Enchantments'],
  ['battle', 'Battles'], ['land', 'Lands']];
const MANA: string[] = ['any', '0', '1', '2', '3', '4', '5', '6', '7+'];
const SORTS: [string, string][] = [['name', 'Name'], ['mv', 'Mana value'], ['colour', 'Colour'], ['type', 'Type']];
const VIEW_KEY = 'forge.catalogueView';
// Forge's card search syntax, as desktop's card search reads it
const SEARCH_TIPS: [string, string][] = [
  ['bolt', 'Name contains bolt'],
  ['c:bg', 'Black and green'],
  ['c=bg', 'Only black and green'],
  ['t:creature', 'Creature'],
  ['o:"draw a card"', 'Rules text contains draw a card'],
  ['mv<=3', 'Mana value 3 or less'],
  ['kw:flying', 'Has flying'],
  ['r:rare', 'Rare'],
  ['s:mh2', 'From Modern Horizons 2'],
  ['-t:land', 'Not a land'],
  ['t:elf | t:goblin', 'Elf or goblin'],
  ['Enter', 'Add the highlighted card'],
];
const BASICS = new Set(['Plains', 'Island', 'Swamp', 'Mountain', 'Forest', 'Wastes']);
const COMMANDER_FORMATS = new Set(['Commander', 'Brawl', 'Oathbreaker', 'TinyLeaders']);

/** How many copies of each card the deck holds, by name, across every section. A limited deck's sideboard is its pool, not the deck. */
export function countsInDeck(state: EditorState): Map<string, number> {
  const counts = new Map<string, number>();
  for (const c of [...state.commanders, ...state.main.flatMap(g => g.cards), ...(state.limited ? [] : state.sideboard)]) {
    counts.set(c.name, (counts.get(c.name) ?? 0) + c.count);
  }
  return counts;
}

/** The copy limit the editor shows; the server enforces the exact one, which a few cards raise. */
export function copyLimit(state: EditorState, name: string): number {
  if (BASICS.has(name)) return Infinity;
  return !state.unrestricted && COMMANDER_FORMATS.has(state.format) ? 1 : 4;
}

/** How many more of a card the deck can take: what the pool has left in limited mode, otherwise the copy limit's room. */
export function roomFor(state: EditorState, row: CatalogueRow, inDeck: number): number {
  if (state.limited) return state.sideboard.find(c => c.name === row.name)?.count ?? 0;
  return copyLimit(state, row.name) - inDeck;
}

export function Catalogue({ model, actions, state, handlers }: {
  model: Model; actions: Actions; state: EditorState; handlers: CardHandlers;
}) {
  const [typed, setTyped] = useState('');
  const [text, setText] = useState('');
  const [colours, setColours] = useState<Set<string>>(() => new Set());
  const [type, setType] = useState('any');
  const [mv, setMv] = useState('any');
  const [sort, setSort] = useState('name');
  const [showAll, setShowAll] = useState(false);
  const [view, setView] = useState<'cards' | 'table'>(() => storedView());
  const asked = useRef(0);
  const query = { text, colours: [...colours].join(''), type, mv, sort, showAll };

  useEffect(() => {
    const timer = setTimeout(() => setText(typed), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [typed]);
  // The deck's rules decide which cards show, so a new commander or check asks again
  useEffect(() => {
    asked.current = 0;
    actions.queryCatalogue(0, { ...query, offset: 0 });
  }, [text, query.colours, type, mv, sort, showAll, state.check, state.identity, state.commanderWanted]);

  const page = model.catalogue;
  const rows = page?.rows ?? [];
  const counts = countsInDeck(state);
  const canAdd = (row: CatalogueRow) => !row.problem && roomFor(state, row, counts.get(row.name) ?? 0) > 0;
  const top = text.trim() ? rows.find(canAdd) : undefined;
  const searched = page?.ranked ? text.trim() : '';
  const add = (name: string, to: 'Main' | 'Sideboard' = 'Main') => actions.edit({ op: 'add', name, to, count: 1 });
  const remove = (name: string) => actions.edit({ op: 'remove', name, from: 'Main', count: 1 });
  const makeCommander = (name: string) => actions.edit({ op: 'commander', name, count: 1 });
  const more = (e: Event) => {
    const el = e.currentTarget as HTMLElement;
    if (page && rows.length < page.total && asked.current < rows.length && el.scrollTop + el.clientHeight > el.scrollHeight - 600) {
      asked.current = rows.length;
      actions.queryCatalogue(0, { ...query, offset: rows.length });
    }
  };
  const narrowed = !!typed || colours.size > 0 || type !== 'any' || mv !== 'any' || showAll;
  return (
    <section class="catalogue" data-zone="catalogue">
      <div class="find-row">
        <span class="search-wrap">
          <input class="find" type="search" placeholder="Search card names" autocomplete="off" value={typed}
            aria-describedby="search-tip"
            onInput={e => setTyped(e.currentTarget.value)}
            onKeyDown={e => { if (e.key === 'Enter' && top) (state.commanderWanted ? makeCommander : add)(top.name); }} />
          <table class="search-tip" id="search-tip" role="tooltip">
            <tbody>
              {SEARCH_TIPS.map(([key, what]) => <tr key={key}><th>{key}</th><td>{what}</td></tr>)}
            </tbody>
          </table>
        </span>
        {text.trim() && page?.ranked !== false
          ? <select class="sort-by" aria-label="Sort" disabled><option>Sort: Best match</option></select>
          : <select class="sort-by" aria-label="Sort" value={sort} onChange={e => setSort(e.currentTarget.value)}>
              {SORTS.map(([id, name]) => <option key={id} value={id}>{`Sort: ${name}`}</option>)}
            </select>}
        <span class="seg" role="group" aria-label="View">
          <button aria-pressed={view === 'cards'} onClick={() => { setView('cards'); store(VIEW_KEY, 'cards'); }}>Cards</button>
          <button aria-pressed={view === 'table'} onClick={() => { setView('table'); store(VIEW_KEY, 'table'); }}>Table</button>
        </span>
      </div>
      <div class="filter-band">
        <span class="band-lab">Colours</span>
        <div class="colours" role="group" aria-label="Colours">
          {COLOURS.map(([letter, name]) => (
            <button key={letter} class="colour" aria-label={name} aria-pressed={colours.has(letter)} onClick={() => {
              const next = new Set(colours);
              if (!next.delete(letter)) next.add(letter);
              setColours(next);
            }}><i class={`pip pip-${letter}`}>{letter}</i></button>
          ))}
        </div>
        {!state.commanderWanted && state.identity && <>
          <span class="band-lab">Identity</span>
          <p class="pinned">{state.identity.split('').join(' ')}<span>set by the commander</span></p>
        </>}
        <select aria-label="Card type" value={type} onChange={e => setType(e.currentTarget.value)}>
          {TYPES.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
        </select>
        <select aria-label="Mana value" value={mv} onChange={e => setMv(e.currentTarget.value)}>
          {MANA.map(v => <option key={v} value={v}>{v === 'any' ? 'Any mana value' : `Mana value ${v}`}</option>)}
        </select>
        {!state.limited && (
          <label class="legal-only">
            <input type="checkbox" role="switch" checked={showAll} onChange={e => setShowAll(e.currentTarget.checked)} />
            Show cards this deck can't use
          </label>
        )}
        <button class="clear" hidden={!narrowed} onClick={() => {
          setTyped('');
          setColours(new Set());
          setType('any');
          setMv('any');
          setShowAll(false);
        }}>Clear filters</button>
      </div>
      <p class={state.commanderWanted ? 'shown commanders-only' : 'shown'}>{shownLine(page?.total, searched, state.commanderWanted)}</p>
      <div class={view === 'cards' ? 'cat-grid' : 'cat-table'} onScroll={more}>
        {page && page.total === 0 && <Empty text={searched} identity={state.identity} hidden={page.hiddenBySwitch}
          showThem={() => setShowAll(true)} />}
        {view === 'cards'
          ? rows.map(row => <Tile key={row.name} row={row} count={counts.get(row.name) ?? 0} top={row === top}
              limit={copyLimit(state, row.name)} room={roomFor(state, row, counts.get(row.name) ?? 0)} limited={state.limited}
              commanderWanted={state.commanderWanted} add={add} remove={remove}
              makeCommander={makeCommander} handlers={handlers} />)
          : <Table rows={rows} counts={counts} state={state} add={add} remove={remove} handlers={handlers} />}
      </div>
    </section>
  );
}

function Tile({ row, count, top, limit, room, limited, commanderWanted, add, remove, makeCommander, handlers }: {
  row: CatalogueRow; count: number; top: boolean; limit: number; room: number; limited: boolean; commanderWanted: boolean;
  add: (name: string, to?: 'Main' | 'Sideboard') => void; remove: (name: string) => void; makeCommander: (name: string) => void;
  handlers: CardHandlers;
}) {
  const full = room <= 0;
  return (
    <div class={`slot${count ? ' indeck' : ''}${row.problem ? ' bad' : ''}${top ? ' top' : ''}`} data-card={row.name} data-from="catalogue">
      {top && <span class="enter">{commanderWanted ? 'Press Enter to choose' : 'Press Enter to add'}</span>}
      <button class="tile" title={row.name} {...handlers(row.name, 'catalogue', row.image, 1)}
        onClick={() => (commanderWanted ? makeCommander(row.name) : add(row.name))}>
        <span class="tile-name">{row.name}</span>
        <img loading="lazy" alt="" src={imageUrl(row.image)} onError={e => { e.currentTarget.hidden = true; }} />
        {count > 0 && <span class="badge">{count}</span>}
      </button>
      {row.problem && <span class="flag">! {row.problem}</span>}
      {commanderWanted
        ? <div class="under centred"><button class="side" onClick={() => makeCommander(row.name)}>Make commander</button></div>
        : (
          <div class="under">
            <button class="step" disabled={!count} aria-label={`Remove one ${row.name}`} onClick={() => remove(row.name)}>&minus;</button>
            <span class={count ? 'n' : 'n zero'}>{count}</span>
            <button class="step" disabled={full || !!row.problem} aria-label={`Add one ${row.name}`} onClick={() => add(row.name)}>+</button>
            {limited
              ? <span class="why">{room ? `${room} left` : 'none left'}</span>
              : full && limit < Infinity
                ? <span class="why">{limit === 1 ? '1 of 1, singleton' : `${count} of ${limit}`}</span>
                : <button class="side" disabled={!!row.problem} onClick={() => add(row.name, 'Sideboard')}>Side</button>}
          </div>
        )}
    </div>
  );
}

function Table({ rows, counts, state, add, remove, handlers }: {
  rows: CatalogueRow[]; counts: Map<string, number>; state: EditorState;
  add: (name: string) => void; remove: (name: string) => void; handlers: CardHandlers;
}) {
  return (
    <table>
      <thead><tr><th>In deck</th><th>Name</th><th>Cost</th><th>Type</th><th>P/T</th><th>MV</th></tr></thead>
      <tbody>
        {rows.map(row => {
          const count = counts.get(row.name) ?? 0;
          return (
            <tr key={row.name} class={row.problem ? 'bad' : undefined} data-image={row.image} data-card={row.name} data-from="catalogue"
              {...handlers(row.name, 'catalogue', row.image, 1)}>
              <td class="under">
                <button class="step" disabled={!count} onClick={() => remove(row.name)}>&minus;</button>
                <span class={count ? 'n' : 'n zero'}>{count}</span>
                <button class="step" disabled={!!row.problem || roomFor(state, row, count) <= 0} onClick={() => add(row.name)}>+</button>
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
    return <p class="none-found">No card matches. Clear a filter, or search a different name.</p>;
  }
  const within = identity ? ` within ${identity.split('').join(' ')}` : '';
  return (
    <div class="none-found">
      <b>No card{within} matches{text ? ` "${text}"` : ''}.</b>
      <span>{hidden} {hidden === 1 ? 'card matches' : 'cards match'} that this deck can't use.</span>
      <button onClick={showThem}>Show them</button>
    </div>
  );
}

// A deck without its commander lists only cards that could be one, and the count line is where that is said
function shownLine(total: number | undefined, text: string, commandersOnly: boolean): string {
  if (total === undefined) return 'Reading the cards…';
  const named = text ? ` named with "${text}"` : '';
  if (commandersOnly) return `${total} ${total === 1 ? 'card' : 'cards'}${named} that can be your commander. Choose one to see every card.`;
  return `${total} ${total === 1 ? 'card' : 'cards'}${named}`;
}

function storedView(): 'cards' | 'table' {
  return stored(VIEW_KEY) === 'table' ? 'table' : 'cards';
}
