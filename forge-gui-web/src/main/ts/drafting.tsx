// Drafting: the pack and the picks side by side in the middle of the page, as the lobby's seats are, with the table
// behind a button in the header. A click selects a card and a second click, or Enter, picks it into the main deck,
// because a pick cannot be taken back and a timer can make a single click a slip. Dragging a card picks it into
// whichever of the main deck and the sideboard it is dropped on, and a pick can be moved between them after.

import { useEffect, useRef, useState } from 'preact/hooks';
import { Dial } from './packdial';
import { nextFrom } from './dial';
import { imageUrl } from './images';
import { avatarUrl } from './looks';
import { rememberedAvatar } from './menu';
import { SymbolText } from './symbols';
import { changeUi } from './ui';
import { HeadControls, PageHeader } from './header';
import { peekAt } from './deckfinder';
import type { Actions } from './actions';
import type { Model } from './model';
import type { DraftCard, DraftSeat, DraftState } from './protocol';

type GroupBy = 'colour' | 'type' | 'mv' | 'pick';
type Drag = { from: 'pack' | 'pick'; index: number };
/** A pick with its place in the order picked, which is how the server names it. */
type Held = { card: DraftCard; index: number };

const DRAG_TYPE = 'application/x-forge-draft';
/** Under this much time the clock turns amber. */
const CLOCK_LOW_MS = 15_000;

export function Drafting({ model, actions }: { model: Model; actions: Actions }) {
  const state = model.draft;
  const [leaving, setLeaving] = useState(false);
  const [log, setLog] = useState(false);
  const [peek, setPeek] = useState<{ image: string; left: number; top: number } | null>(null);
  // An online draft belongs to the table and goes on without this browser, so leaving only goes back to the table
  const online = model.inLobby;
  const faces = state ? seatFaces(model, state.seats) : [];
  return (
    <div class="drafting-page" onPointerOver={e => setPeek(peekAt(e, '.drafting-page'))} onPointerLeave={() => setPeek(null)}>
      <PageHeader class="limited-head">
        <span class="limited-title">Booster draft</span>
        {state && <span class="muted">{state.product} · {state.seats.length} seats</span>}
        <div class="head-right">
          {state && <TableMenu state={state} faces={faces} />}
          <HeadControls />
          {online && state && state.log.length > 0 && <button onClick={() => setLog(!log)}>Draft log</button>}
          {online
            ? <button onClick={() => changeUi(u => { u.draftHidden = true; })}>Back to the table</button>
            : leaving
            ? <>
                <span class="muted">Leave the draft? It will not be saved.</span>
                <button onClick={() => setLeaving(false)}>Keep drafting</button>
                <button class="danger" onClick={() => actions.draftDiscard()}>Leave</button>
              </>
            : <button onClick={() => setLeaving(true)}>Leave draft</button>}
        </div>
      </PageHeader>
      {model.error && <p class="limited-error">{model.error}</p>}
      {/* Opening packs can wait on a web site, so leaving stays possible while it does */}
      {!state && <p class="muted drafting-wait">Opening the packs…</p>}
      {state && (
        <div class="drafting-shell">
          <Pack state={state} faces={faces} actions={actions} />
          <Picks state={state} actions={actions} />
        </div>
      )}
      {state?.done && <SaveDraft model={model} state={state} actions={actions} />}
      {log && state && <DraftLog lines={state.log} close={() => setLog(false)} />}
      {peek && <div class="deck-peek" style={{ left: `${peek.left}px`, top: `${peek.top}px` }}><img alt="" src={imageUrl(peek.image)} /></div>}
    </div>
  );
}

/**
 * Each seat's face: your own avatar, a lobby player's, or for a computer drafter one picked from its name, so it keeps
 * the same face all draft.
 */
function seatFaces(model: Model, seats: DraftSeat[]): string[] {
  const count = model.looks?.avatarCount ?? 0;
  return seats.map((seat, i) => {
    const index = i === 0 ? rememberedAvatar()
      : model.lobby?.seats.find(s => s.name === seat.name)?.avatar ?? (count ? hash(seat.name) % count : -1);
    return index >= 0 ? avatarUrl(index) : '';
  });
}

function hash(text: string): number {
  let h = 0;
  for (const c of text) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return h;
}

/** Show table: the seat feeding you, you and the seat you pass to on the button, and the whole table under it. */
function TableMenu({ state, faces }: { state: DraftState; faces: string[] }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (!open) return;
    const away = (e: Event) => { if (!root.current?.contains(e.target as Node)) setOpen(false); };
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    addEventListener('pointerdown', away);
    addEventListener('keydown', key);
    return () => { removeEventListener('pointerdown', away); removeEventListener('keydown', key); };
  }, [open]);
  const n = state.seats.length;
  const direction = state.direction < 0 ? -1 : 1;
  const shown = [(n - direction) % n, 0, (n + direction) % n];
  return (
    <span class="table-menu" ref={root}>
      <button class={open ? 'table-btn open' : 'table-btn'} aria-expanded={open} onClick={() => setOpen(!open)}>
        <span class="faces">{shown.map(i => faces[i] ? <img key={i} alt="" src={faces[i]} /> : null)}</span>
        Show table <span class="caret" aria-hidden="true">{open ? '▴' : '▾'}</span>
      </button>
      {open && <div class="table-pop"><Dial state={state} faces={faces} /></div>}
    </span>
  );
}

/** What is left of the pick clock, counted down from what the state said when it arrived. */
function useClock(state: DraftState): number {
  const stamp = useRef(Date.now());
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    stamp.current = Date.now();
    setNow(stamp.current);
    if (!state.clockSeconds || !state.clockLeftMillis) return;
    const tick = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(tick);
  }, [state]);
  return state.clockSeconds ? Math.max(0, state.clockLeftMillis - (now - stamp.current)) : 0;
}

function Pack({ state, faces, actions }: { state: DraftState; faces: string[]; actions: Actions }) {
  const [selected, setSelected] = useState<number | null>(null);
  // A new state clears the selection, since its cards are not the ones selected
  useEffect(() => setSelected(null), [state.step]);
  const pick = (index: number) => actions.draftPick(state.step, index, false);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Enter' && selected !== null && !(e.target instanceof HTMLInputElement)) pick(selected);
    };
    addEventListener('keydown', onKey);
    return () => removeEventListener('keydown', onKey);
  }, [selected, state.step]);
  const left = useClock(state);
  const n = state.seats.length;
  const direction = (state.direction < 0 ? -1 : 1) as 1 | -1;
  const depths = state.seats.map(s => s.packs);
  const next = nextFrom(depths, direction);
  const busiest = depths.reduce((best, d, i) => (d > depths[best] ? i : best), 0);
  const to = (n + direction) % n;
  const seat = (i: number) => <>{faces[i] && <img alt="" src={faces[i]} />}<strong>{state.seats[i].name}</strong></>;
  return (
    <section class="draft-panel draft-pack">
      <div class="draft-panel-head">
        <b>Pack {state.pack} · pick {state.pick} of {state.packSize}</b>
        <span class="chip">Passing {direction > 0 ? 'right' : 'left'} to {seat(to)}</span>
        {next !== null && <span class="chip">Next from {seat(next)}</span>}
        {depths[busiest] > 2 && <span class="chip">{busiest === 0 ? 'You are' : `${state.seats[busiest].name} is`} holding {depths[busiest]} packs</span>}
        {left > 0 && <span class={left < CLOCK_LOW_MS ? 'chip clock low' : 'chip clock'}>{clockText(left)}</span>}
      </div>
      <div class="cat-grid">
        {state.cards.map((card, i) => (
          <div key={`${card.image}-${i}`} class={i === selected ? 'slot draft-slot chosen' : 'slot draft-slot'} data-card={card.name} data-image={card.image}>
            <button class="tile" title={card.name} draggable
              onDragStart={e => startDrag(e, { from: 'pack', index: i })}
              onClick={() => (i === selected ? pick(i) : setSelected(i))}>
              <span class="tile-name">{card.name}</span>
              <img loading="lazy" alt="" draggable={false} src={imageUrl(card.image)} onError={e => { e.currentTarget.hidden = true; }} />
              {card.rank !== undefined && <RankShield rank={card.rank} />}
            </button>
            {i === selected && <span class="confirm">Pick · click again or Enter</span>}
          </div>
        ))}
        {state.cards.length === 0 && !state.done && <p class="muted">Waiting for a pack…</p>}
      </div>
    </section>
  );
}

function clockText(ms: number): string {
  const s = Math.ceil(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

function startDrag(e: DragEvent, drag: Drag): void {
  e.dataTransfer?.setData(DRAG_TYPE, JSON.stringify(drag));
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
}

/** Desktop's draft ranking overlay: the score on a shield in the middle of the card, the shield graded by the score. */
function RankShield({ rank }: { rank: number }) {
  const tier = rank >= 90 ? 's' : rank >= 80 ? 'a' : rank >= 60 ? 'b' : rank >= 25 ? 'c' : 'd';
  return (
    <span class={`rank tier-${tier}`} title="Draft ranking">
      <svg viewBox="0 0 34 42" aria-hidden="true">
        <defs>
          <linearGradient id={`rank-${tier}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" class="hi" /><stop offset=".45" class="mid" /><stop offset="1" class="lo" />
          </linearGradient>
        </defs>
        <path class="face" fill={`url(#rank-${tier})`} d="M17 1.5 C21.5 3.8 26.5 4.8 32 4.8 V18 C32 29 25.5 36.5 17 41 C8.5 36.5 2 29 2 18 V4.8 C7.5 4.8 12.5 3.8 17 1.5 Z" />
        <path class="inner" d="M17 4.6 C20.8 6.4 25 7.3 29.2 7.4 V18 C29.2 27.6 23.8 34 17 37.9 C10.2 34 4.8 27.6 4.8 18 V7.4 C9 7.3 13.2 6.4 17 4.6 Z" />
      </svg>
      <b>{rank}</b>
    </span>
  );
}

function Picks({ state, actions }: { state: DraftState; actions: Actions }) {
  const [by, setBy] = useState<GroupBy>('colour');
  const [cards, setCards] = useState(false);
  const held: Held[] = state.picks.map((card, index) => ({ card, index }));
  const main = held.filter(h => !h.card.sideboard);
  const side = held.filter(h => h.card.sideboard);
  // A card dropped from the pack is picked there; a pick dropped on the other section moves to it
  const drop = (sideboard: boolean) => (drag: Drag) => {
    if (drag.from === 'pack') actions.draftPick(state.step, drag.index, sideboard);
    else if (state.picks[drag.index]?.sideboard !== sideboard) actions.draftMove(drag.index, sideboard);
  };
  const move = (h: Held) => actions.draftMove(h.index, !h.card.sideboard);
  return (
    <section class="draft-panel draft-picks">
      <div class="draft-panel-head">
        <b>Your picks <span class="muted">{state.picks.length}</span></b>
        <span class="seg" role="group" aria-label="Show picks as">
          <button aria-pressed={!cards} onClick={() => setCards(false)}>List</button>
          <button aria-pressed={cards} onClick={() => setCards(true)}>Cards</button>
        </span>
      </div>
      <div class="draft-group-by">
        <span class="muted">Group by</span>
        <span class="seg" role="group" aria-label="Group picks by">
          {(['colour', 'type', 'mv', 'pick'] as GroupBy[]).map(g => (
            <button key={g} aria-pressed={by === g} onClick={() => setBy(g)}>{GROUP_NAMES[g]}</button>
          ))}
        </span>
      </div>
      <div class="draft-sections">
        <PickSection title="Main deck" held={main} by={by} cards={cards} onDrop={drop(false)} move={move} />
        <PickSection title="Sideboard" held={side} by={by} cards={cards} onDrop={drop(true)} move={move} />
      </div>
    </section>
  );
}

const GROUP_NAMES: Record<GroupBy, string> = { colour: 'Colour', type: 'Type', mv: 'Mana value', pick: 'Pick order' };

function PickSection({ title, held, by, cards, onDrop, move }: {
  title: string; held: Held[]; by: GroupBy; cards: boolean; onDrop: (drag: Drag) => void; move: (h: Held) => void;
}) {
  const [over, setOver] = useState(false);
  const accepts = (e: DragEvent) => !!e.dataTransfer?.types.includes(DRAG_TYPE);
  return (
    <div class={over ? 'pick-section over' : 'pick-section'}
      onDragOver={e => { if (accepts(e)) { e.preventDefault(); setOver(true); } }}
      onDragLeave={e => { if (!(e.currentTarget as HTMLElement).contains(e.relatedTarget as Node)) setOver(false); }}
      onDrop={e => {
        setOver(false);
        const raw = e.dataTransfer?.getData(DRAG_TYPE);
        if (!raw) return;
        e.preventDefault();
        onDrop(JSON.parse(raw) as Drag);
      }}>
      <h3>{title} <span class="muted">{held.length}</span></h3>
      {held.length === 0 && <p class="drop-hint">Drag cards here</p>}
      <div class={cards ? 'pick-cols' : 'zone-body cols'}>
        {grouped(held, by).map(([heading, group]) => (
          <div key={heading} class="group">
            <h4>{heading}<span>{group.length}</span></h4>
            {cards
              ? <div class="pick-stack">{group.map(h => (
                  <img key={h.index} alt={h.card.name} title={`${h.card.name} · double-click to move`} src={imageUrl(h.card.image)}
                    data-image={h.card.image} draggable onDragStart={e => startDrag(e, { from: 'pick', index: h.index })}
                    onDblClick={() => move(h)} />
                ))}</div>
              : group.map(h => (
                  <div key={h.index} class="ed-line" data-card={h.card.name} data-image={h.card.image} draggable
                    title="Double-click to move" onDragStart={e => startDrag(e, { from: 'pick', index: h.index })} onDblClick={() => move(h)}>
                    <span class="nm">{h.card.name}</span>
                    <span class="cost"><SymbolText text={h.card.cost} /></span>
                    <span class="muted pk" title={`Pack ${h.card.pack}, pick ${h.card.pick}`}>{h.card.pack}·{h.card.pick}</span>
                  </div>
                ))}
          </div>
        ))}
      </div>
    </div>
  );
}

/** Other seats' picks are most of the log, so they are shown only when asked for; the newest line comes first. */
function DraftLog({ lines, close }: { lines: string[]; close: () => void }) {
  const [everyone, setEveryone] = useState(false);
  const shown = lines.filter(l => everyone || !/ picked · \d+ waiting$/.test(l)).reverse();
  return (
    <aside class="draft-log" aria-label="Draft log">
      <header>
        <b>Draft log</b>
        <label><input type="checkbox" checked={everyone} onChange={e => setEveryone(e.currentTarget.checked)} /> Every seat's picks</label>
        <button class="dk-close" title="Close" onClick={close}>&times;</button>
      </header>
      {shown.map((line, i) => <p key={shown.length - i} class={line.startsWith('Pack ') ? 'head' : ''}>{line}</p>)}
    </aside>
  );
}

const COLOURS: Record<string, string> = { W: 'White', U: 'Blue', B: 'Black', R: 'Red', G: 'Green' };

function grouped(held: Held[], by: GroupBy): [string, Held[]][] {
  if (by === 'pick') return held.length ? [['In pick order', held]] : [];
  const groups = new Map<string, Held[]>();
  // Mana value runs low to high; the other groupings keep the order their first card was picked in
  const ordered = by === 'mv' ? [...held].sort((a, b) => a.card.mv - b.card.mv) : held;
  for (const h of ordered) {
    const c = h.card;
    const key = by === 'type' ? typeHeading(c.type)
      : by === 'mv' ? (c.mv >= 6 ? '6+' : String(c.mv))
      : c.colors.length === 0 || c.colors === 'C' ? 'Colourless' : c.colors.length > 1 ? 'Multicolour' : COLOURS[c.colors] ?? c.colors;
    groups.set(key, [...(groups.get(key) ?? []), h]);
  }
  return [...groups.entries()];
}

function typeHeading(type: string): string {
  for (const t of ['Creature', 'Planeswalker', 'Instant', 'Sorcery', 'Artifact', 'Enchantment', 'Land']) {
    if (type.includes(t)) return `${t}s`;
  }
  return 'Other';
}

/** The end of the draft, as desktop ends it: a name to save it under, or leaving without saving. */
function SaveDraft({ model, state, actions }: { model: Model; state: DraftState; actions: Actions }) {
  const [name, setName] = useState(() => `${state.product} ${new Date().toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })}`);
  const [discarding, setDiscarding] = useState(false);
  const taken = model.nameTaken !== null && model.nameTaken === name.trim();
  return (
    <div class="backdrop">
      <div class="dialog draft-save">
        <h3>Draft complete</h3>
        <p class="hint">{state.picks.length} cards drafted. Save them to build a deck and play the computer's decks.</p>
        <form onSubmit={e => { e.preventDefault(); if (name.trim()) actions.draftSave(name.trim(), false); }}>
          <input type="text" value={name} aria-label="Draft name" onInput={e => setName(e.currentTarget.value)} />
        </form>
        {taken && <p class="hint">You already have a draft called <b>{name.trim()}</b>.</p>}
        <div class="actions">
          {discarding
            ? <>
                <span class="muted">Throw these picks away?</span>
                <button onClick={() => setDiscarding(false)}>Keep them</button>
                <button class="danger" onClick={() => actions.draftDiscard()}>Discard</button>
              </>
            : <>
                <button onClick={() => setDiscarding(true)}>Discard</button>
                {taken
                  ? <button class="danger" onClick={() => actions.draftSave(name.trim(), true)}>Replace it</button>
                  : <button class="primary" disabled={!name.trim()} onClick={() => actions.draftSave(name.trim(), false)}>Save</button>}
              </>}
        </div>
      </div>
    </div>
  );
}
