// Drafting: the pack and the picks side by side in the middle of the page, as the lobby's seats are, with the table
// behind a button in the header. A click selects a card and a second click, or Enter, picks it into the main deck,
// because a pick cannot be taken back and a timer can make a single click a slip. Dragging a card picks it into
// whichever of the main deck and the sideboard it is dropped on, and a pick can be moved between them after.

import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { Dial } from './packdial';
import { nextFrom } from './dial';
import { imageUrl } from './images';
import { cancelFlight, land, lift } from './flight';
import { avatarUrl } from './looks';
import { rememberedAvatar } from './menu';
import { SymbolText } from './symbols';
import { changeUi } from './ui';
import { HeadControls, PageHeader } from './header';
import { peekAt } from './deckfinder';
import { t, tNodes, type TextKey } from './text';
import type { ComponentChildren } from 'preact';
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

/** How long the rest of a pack takes to slide off, and a new pack to slide in, and how far each column follows the last. */
const PASS_MS = 300;
const ARRIVE_MS = 400;
const COLUMN_STAGGER_MS = 22;

/** When the last pack started leaving, so the next one can follow it out rather than cross it. */
let passedAt = 0;
/** When the pack stops moving. Until then a card sliding under the pointer shows no preview. */
let packStillAt = 0;
const packMoving = () => performance.now() < packStillAt;

/** The side of the screen a pack passes to: -1 for left, 1 for right. */
const passSide = (direction: number): -1 | 1 => (direction < 0 ? -1 : 1);

/** Each pack card's column, counted from one side of the grid, so a pack moves across it a column at a time. */
function columnsFrom(slots: HTMLElement[], edge: -1 | 1): number[] {
  const xs = slots.map(s => s.offsetLeft);
  const columns = [...new Set(xs)].sort((a, b) => edge * (b - a));
  return xs.map(x => columns.indexOf(x));
}

/**
 * Sends the rest of the pack on to the next player once a card is picked: each card is lifted off as it looks and
 * slides off the side the pack passes to. The cards themselves stay hidden until the next pack replaces them, and show
 * again if none comes, as when a pick is refused.
 */
function passPack(picked: number, side: -1 | 1): void {
  const grid = document.querySelector<HTMLElement>('.draft-pack .cat-grid');
  const slots = [...grid?.querySelectorAll<HTMLElement>('.draft-slot') ?? []].filter((_, i) => i !== picked);
  if (!grid || !slots.length) return;
  const reduced = document.documentElement.dataset.motion === 'reduced';
  const box = grid.getBoundingClientRect();
  // Clipped to the pack, and inside the same classes, so the copies are drawn as the cards are
  const layer = document.createElement('div');
  layer.className = 'draft-pack pack-leaving';
  Object.assign(layer.style, { left: `${box.left}px`, top: `${box.top}px`, width: `${box.width}px`, height: `${box.height}px` });
  const inner = document.createElement('div');
  inner.className = 'cat-grid';
  layer.append(inner);
  const columns = columnsFrom(slots, side);
  packStillAt = Math.max(packStillAt, performance.now() + (reduced ? 160 : PASS_MS + Math.max(...columns) * COLUMN_STAGGER_MS));
  const slides = slots.map((slot, i) => {
    const at = slot.getBoundingClientRect();
    const copy = slot.cloneNode(true) as HTMLElement;
    Object.assign(copy.style, { position: 'absolute', left: `${at.left - box.left}px`, top: `${at.top - box.top}px`,
      width: `${at.width}px`, height: `${at.height}px`, margin: '0' });
    inner.append(copy);
    slot.style.visibility = 'hidden';
    return copy.animate(reduced ? [{ opacity: 1 }, { opacity: 0 }]
      : [{ transform: 'none', opacity: 1 }, { transform: `translateX(${side * box.width * 0.8}px)`, opacity: 0 }],
    { duration: reduced ? 160 : PASS_MS, delay: reduced ? 0 : columns[i] * COLUMN_STAGGER_MS, easing: 'cubic-bezier(.4,0,1,1)', fill: 'forwards' });
  });
  document.body.append(layer);
  passedAt = performance.now();
  void Promise.all(slides.map(a => a.finished.catch(() => undefined))).then(() => layer.remove());
  setTimeout(() => slots.forEach(s => { s.style.visibility = ''; }), 2000);
}

/** Slides a new pack in from the side it comes from, the column with furthest to go first. */
function arrive(grid: HTMLElement, from: -1 | 1): void {
  const slots = [...grid.querySelectorAll<HTMLElement>('.draft-slot')];
  const reduced = document.documentElement.dataset.motion === 'reduced';
  const columns = columnsFrom(slots, -from as -1 | 1);
  // A pack the next player had waiting comes in behind the one leaving, once that one is mostly gone
  const behind = Math.max(0, passedAt + PASS_MS * 0.6 - performance.now());
  packStillAt = Math.max(packStillAt, performance.now() + behind + (reduced ? 200 : ARRIVE_MS + Math.max(0, ...columns) * COLUMN_STAGGER_MS));
  slots.forEach((slot, i) => {
    // A card the new pack shares with the old, in the same place, is the same element, still hidden from the pass
    slot.style.visibility = '';
    slot.animate(reduced ? [{ opacity: 0 }, { opacity: 1 }]
      : [{ transform: `translateX(${from * grid.clientWidth * 0.7}px)`, opacity: 0 }, { transform: 'none', opacity: 1 }],
    { duration: reduced ? 200 : ARRIVE_MS, delay: reduced ? behind : behind + columns[i] * COLUMN_STAGGER_MS, easing: 'cubic-bezier(.2,.7,.25,1)', fill: 'backwards' });
  });
}

/** Lifts the pack card about to be picked, so it can fly to where the pick lands. */
function launch(index: number): void {
  lift(document.querySelectorAll('.draft-pack .draft-slot')[index]?.querySelector<HTMLImageElement>('.tile img:not(.sym)'), 'pick');
}

export function Drafting({ model, actions }: { model: Model; actions: Actions }) {
  const state = model.draft;
  const [leaving, setLeaving] = useState(false);
  const [peek, setPeek] = useState<{ image: string; left: number; top: number } | null>(null);
  // An online draft belongs to the table and goes on without this browser, so leaving only goes back to the table
  const online = model.inLobby;
  const faces = state ? seatFaces(model, state.seats) : [];
  return (
    <div class="drafting-page" onPointerLeave={() => setPeek(null)}
      onPointerOver={e => setPeek(packMoving() && (e.target as Element).closest?.('.draft-pack') ? null : peekAt(e, '.drafting-page'))}>
      <PageHeader class="limited-head">
        <span class="limited-title">{t('lblWebDraftBoosterDraft')}</span>
        {state && <span class="muted">{t('lblWebDraftProductSeats', state.product, state.seats.length)}</span>}
        <div class="head-right">
          <HeadControls />
          {online
            ? <button onClick={() => changeUi(u => { u.draftHidden = true; })}>{t('lblWebDraftBackToTable')}</button>
            : <button onClick={() => setLeaving(true)}>{t('lblWebDraftLeaveDraft')}</button>}
        </div>
      </PageHeader>
      {model.error && <p class="limited-error">{model.error}</p>}
      {/* Opening packs can wait on a web site, so leaving stays possible while it does */}
      {!state && <p class="muted drafting-wait">{t('lblWebDraftOpeningPacks')}</p>}
      {state && (
        <div class="drafting-shell">
          <Pack state={state} faces={faces} actions={actions} log={online && state.log.length > 0} hidePeek={() => setPeek(null)} />
          <Picks state={state} actions={actions} />
        </div>
      )}
      {state?.done && <SaveDraft model={model} state={state} actions={actions} />}
      {leaving && (
        <div class="backdrop" onClick={e => { if (e.target === e.currentTarget) setLeaving(false); }}>
          <div class="dialog" role="alertdialog" aria-label={t('lblWebDraftLeaveTheDraft')}>
            <h3>{t('lblWebDraftLeaveTheDraftQ')}</h3>
            <p class="hint">{t('lblWebDraftPicksNotSaved')}</p>
            <div class="actions">
              <button onClick={() => setLeaving(false)}>{t('lblWebDraftKeepDrafting')}</button>
              <button class="danger" onClick={() => actions.draftDiscard()}>{t('lblLeave')}</button>
            </div>
          </div>
        </div>
      )}
      {peek && (
        <div class="deck-peek" style={{ left: `${peek.left}px`, top: `${peek.top}px` }}>
          <span class="peek-card"><CardFace card={cardFor(state, peek.image)} /></span>
        </div>
      )}
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
  const n = state.seats.length;
  const direction = state.direction < 0 ? -1 : 1;
  const shown = [(n - direction) % n, 0, (n + direction) % n];
  return (
    <Dropdown class="table-btn" label={<>
      <span class="faces">{shown.map(i => faces[i] ? <img key={i} alt="" src={faces[i]} /> : null)}</span>{t('lblWebDraftShowTable')}
    </>}>
      {() => <Dial state={state} faces={faces} />}
    </Dropdown>
  );
}

/** A button in the pack's head that opens a panel under it, closed again by Escape or a click elsewhere. */
function Dropdown({ label, children, class: cls }: { label: ComponentChildren; children: (close: () => void) => ComponentChildren; class?: string }) {
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
  return (
    <span class="table-menu" ref={root}>
      <button class={[cls ?? '', 'drop-btn', open ? 'open' : ''].join(' ')} aria-expanded={open} onClick={() => setOpen(!open)}>
        {label} <span class="caret" aria-hidden="true">{open ? '▴' : '▾'}</span>
      </button>
      {open && <div class="table-pop">{children(() => setOpen(false))}</div>}
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

function Pack({ state, faces, actions, log, hidePeek }: { state: DraftState; faces: string[]; actions: Actions; log: boolean;
  hidePeek: () => void }) {
  const [selected, setSelected] = useState<number | null>(null);
  // A new state clears the selection, since its cards are not the ones selected
  useEffect(() => setSelected(null), [state.step]);
  const pick = (index: number) => {
    // The card under the pointer is about to leave with the pack, so its preview goes now
    hidePeek();
    launch(index);
    passPack(index, passSide(state.direction));
    actions.draftPick(state.step, index, false);
  };
  // A new pack slides in from the player it came from, the side opposite the one packs pass to
  const grid = useRef<HTMLDivElement>(null);
  const shown = useRef('');
  const cards = state.cards.map(c => c.image).join('|');
  useLayoutEffect(() => {
    if (cards === shown.current) return;
    shown.current = cards;
    if (grid.current && state.cards.length) arrive(grid.current, -passSide(state.direction) as -1 | 1);
  }, [cards]);
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
      {/* The pick and its clock, which runs down a bar along the line's foot; under them, where the packs go */}
      <div class="draft-panel-head pick-line">
        <b class="pick-title">{t('lblWebDraftPackPickOf', state.pack, state.pick, state.packSize)}</b>
        {left > 0 && <span class={left < CLOCK_LOW_MS ? 'clock low' : 'clock'} title={t('ttWebDraftTimeLeft')}>{clockText(left)}</span>}
        {left > 0 && <span class={left < CLOCK_LOW_MS ? 'clock-bar low' : 'clock-bar'} style={{ width: `${(left / (state.clockSeconds * 1000)) * 100}%` }} />}
      </div>
      <div class="draft-panel-head tool-line">
        <span class="chip">{tNodes(direction > 0 ? 'lblWebDraftPassingRightTo' : 'lblWebDraftPassingLeftTo', seat(to))}</span>
        {next !== null && <span class="chip">{tNodes('lblWebDraftNextFrom', seat(next))}</span>}
        {depths[busiest] > 2 && <span class="chip">{busiest === 0 ? t('lblWebDraftYouHoldingPacks', depths[busiest]) : t('lblWebDraftSeatHoldingPacks', state.seats[busiest].name, depths[busiest])}</span>}
        <span class="head-end">
          {log && <Dropdown label={t('lblWebDraftLog')}>{() => <DraftLog lines={state.log} />}</Dropdown>}
          <TableMenu state={state} faces={faces} />
        </span>
      </div>
      <div class="cat-grid" ref={grid}>
        {state.cards.map((card, i) => (
          <div key={`${card.image}-${i}`} class={i === selected ? 'slot draft-slot chosen' : 'slot draft-slot'} data-card={card.name} data-image={card.image}>
            <button class="tile" title={card.name} draggable
              onDragStart={e => startDrag(e, { from: 'pack', index: i })}
              onClick={() => (i === selected ? pick(i) : setSelected(i))}>
              <span class="tile-name">{card.name}</span>
              <CardFace card={card} />
              {card.rank !== undefined && <RankShield rank={card.rank} />}
            </button>
            {i === selected && <span class="confirm">{t('lblWebDraftPickConfirm')}</span>}
          </div>
        ))}
        {state.cards.length === 0 && !state.done && <p class="muted">{t('lblWebDraftWaitingForPack')}</p>}
      </div>
    </section>
  );
}

/** The pack card or pick drawn from an image key, so its preview can fall back to its text as well. */
function cardFor(state: DraftState | null, image: string): DraftCard {
  return [...(state?.cards ?? []), ...(state?.picks ?? [])].find(c => c.image === image)
    ?? { name: '', image, cost: '', mv: 0, colors: '', type: '', text: '', rarity: '', pack: 0, pick: 0, sideboard: false };
}

/** A card's picture, or when none can be had, its name, cost, type and rules text in the card's own shape. */
function CardFace({ card }: { card: DraftCard }) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [card.image]);
  if (!failed) {
    return <img loading="lazy" alt="" draggable={false} src={imageUrl(card.image)} onError={() => setFailed(true)} />;
  }
  return (
    <span class="text-face">
      <span class="tf-head"><b>{card.name}</b><span class="cost"><SymbolText text={card.cost} /></span></span>
      <span class="tf-type">{card.type}</span>
      <span class="tf-text"><SymbolText text={card.text} /></span>
      {card.pt && <span class="tf-pt">{card.pt}</span>}
    </span>
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
    <span class={`rank tier-${tier}`} title={t('ttWebDraftRanking')}>
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
  const [by, setBy] = useState<GroupBy>('type');
  const [cards, setCards] = useState(true);
  const held: Held[] = state.picks.map((card, index) => ({ card, index }));
  const main = held.filter(h => !h.card.sideboard);
  const side = held.filter(h => h.card.sideboard);
  // A card dropped from the pack is picked there; a pick dropped on the other section moves to it
  const drop = (sideboard: boolean) => (drag: Drag) => {
    // A dragged card has been carried here already, so nothing flies
    cancelFlight();
    if (drag.from === 'pack') {
      passPack(drag.index, passSide(state.direction));
      actions.draftPick(state.step, drag.index, sideboard);
    }
    else if (state.picks[drag.index]?.sideboard !== sideboard) actions.draftMove(drag.index, sideboard);
  };
  const move = (h: Held) => actions.draftMove(h.index, !h.card.sideboard);
  const panel = useRef<HTMLElement>(null);
  const count = useRef(state.picks.length);
  // A pick made by a click in the pack flies here once it is drawn; the newest pick is the last in pick order
  useLayoutEffect(() => {
    if (state.picks.length > count.current) {
      const pick = panel.current?.querySelector<HTMLElement>(`[data-pick="${state.picks.length - 1}"]`) ?? null;
      land(pick, pick?.closest('.draft-sections'), pick?.closest('.pick-section')?.querySelector('h3'));
    }
    count.current = state.picks.length;
  }, [state.picks.length]);
  return (
    <section class="draft-panel draft-picks" ref={panel}>
      <div class="draft-panel-head">
        <b>{tNodes('lblWebDraftYourPicks', <span class="muted">{state.picks.length}</span>)}</b>
        <span class="seg" role="group" aria-label={t('lblWebDraftShowPicksAs')}>
          <button aria-pressed={cards} onClick={() => setCards(true)}>{t('lblCards')}</button>
          <button aria-pressed={!cards} onClick={() => setCards(false)}>{t('lblWebDraftList')}</button>
        </span>
      </div>
      <div class="draft-group-by">
        <span class="muted">{t('lblWebDraftGroupBy')}</span>
        <span class="seg" role="group" aria-label={t('lblWebDraftGroupPicksBy')}>
          {(['type', 'colour', 'mv', 'pick'] as GroupBy[]).map(g => (
            <button key={g} aria-pressed={by === g} onClick={() => setBy(g)}>{t(GROUP_NAMES[g])}</button>
          ))}
        </span>
      </div>
      <div class="draft-sections">
        <PickSection title={t('lblWebDraftMainDeck')} held={main} by={by} cards={cards} onDrop={drop(false)} move={move} />
        <PickSection title={t('lblSideboard')} held={side} by={by} cards={cards} onDrop={drop(true)} move={move} />
      </div>
    </section>
  );
}

const GROUP_NAMES: Record<GroupBy, TextKey> = { colour: 'lblWebDraftGroupColour', type: 'lblType', mv: 'lblWebDraftGroupManaValue', pick: 'lblWebDraftGroupPickOrder' };

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
      {held.length === 0 && <p class="drop-hint">{t('lblWebDraftDragCardsHere')}</p>}
      <div class={cards ? 'pick-cols' : 'zone-body cols'}>
        {grouped(held, by).map(([heading, group]) => (
          <div key={heading} class="group">
            <h4>{heading}<span>{group.length}</span></h4>
            {cards
              ? <div class="pick-stack">{group.map(h => (
                  <img key={h.index} alt={h.card.name} title={t('ttWebDraftDoubleClickToMoveCard', h.card.name)} src={imageUrl(h.card.image)}
                    data-image={h.card.image} data-pick={h.index} draggable onDragStart={e => startDrag(e, { from: 'pick', index: h.index })}
                    onDblClick={() => move(h)} />
                ))}</div>
              : group.map(h => (
                  <div key={h.index} class="ed-line" data-card={h.card.name} data-image={h.card.image} data-pick={h.index} draggable
                    title={t('ttWebDraftDoubleClickToMove')} onDragStart={e => startDrag(e, { from: 'pick', index: h.index })} onDblClick={() => move(h)}>
                    <span class="nm">{h.card.name}</span>
                    <span class="cost"><SymbolText text={h.card.cost} /></span>
                    <span class="muted pk" title={t('ttWebDraftPackPick', h.card.pack, h.card.pick)}>{h.card.pack}·{h.card.pick}</span>
                  </div>
                ))}
          </div>
        ))}
      </div>
    </div>
  );
}

/** Other seats' picks are most of the log, so they are shown only when asked for; the newest line comes first. */
function DraftLog({ lines }: { lines: string[] }) {
  const [everyone, setEveryone] = useState(false);
  const shown = lines.filter(l => everyone || !/ picked · \d+ waiting$/.test(l)).reverse();
  return (
    <aside class="draft-log" aria-label={t('lblWebDraftLog')}>
      <header>
        <b>{t('lblWebDraftLog')}</b>
        <label><input type="checkbox" checked={everyone} onChange={e => setEveryone(e.currentTarget.checked)} /> {t('lblWebDraftEverySeatsPicks')}</label>
      </header>
      {shown.map((line, i) => <p key={shown.length - i} class={line.startsWith('Pack ') ? 'head' : ''}>{line}</p>)}
    </aside>
  );
}

const COLOURS: Record<string, TextKey> = { W: 'lblWhite', U: 'lblBlue', B: 'lblBlack', R: 'lblRed', G: 'lblGreen' };

function grouped(held: Held[], by: GroupBy): [string, Held[]][] {
  if (by === 'pick') return held.length ? [[t('lblWebDraftInPickOrder'), held]] : [];
  const groups = new Map<string, Held[]>();
  // Mana value runs low to high; the other groupings keep the order their first card was picked in
  const ordered = by === 'mv' ? [...held].sort((a, b) => a.card.mv - b.card.mv) : held;
  for (const h of ordered) {
    const c = h.card;
    const key = by === 'type' ? typeHeading(c.type)
      : by === 'mv' ? (c.mv >= 6 ? '6+' : String(c.mv))
      : c.colors.length === 0 || c.colors === 'C' ? t('lblWebDraftColourless') : c.colors.length > 1 ? t('lblWebDraftMulticolour')
      : COLOURS[c.colors] ? t(COLOURS[c.colors]) : c.colors;
    groups.set(key, [...(groups.get(key) ?? []), h]);
  }
  return [...groups.entries()];
}

/** The card types the picks are grouped under, matched in the type line the server sends, with each group's heading. */
const TYPE_HEADINGS: [string, TextKey][] = [['Creature', 'lblCreatures'], ['Planeswalker', 'lblPlaneswalkers'],
  ['Instant', 'lblInstants'], ['Sorcery', 'lblSorceries'], ['Artifact', 'lblArtifacts'], ['Enchantment', 'lblEnchantments'],
  ['Land', 'lblLands']];

function typeHeading(type: string): string {
  for (const [name, heading] of TYPE_HEADINGS) {
    if (type.includes(name)) return t(heading);
  }
  return t('lblWebDraftGroupOther');
}

/** The end of the draft, as desktop ends it: a name to save it under, or leaving without saving. */
function SaveDraft({ model, state, actions }: { model: Model; state: DraftState; actions: Actions }) {
  const [name, setName] = useState(() => `${state.product} ${new Date().toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })}`);
  const [discarding, setDiscarding] = useState(false);
  const taken = model.nameTaken !== null && model.nameTaken === name.trim();
  return (
    <div class="backdrop">
      <div class="dialog draft-save">
        <h3>{t('lblWebDraftComplete')}</h3>
        <p class="hint">{t('lblWebDraftCardsDrafted', state.picks.length)}</p>
        <form onSubmit={e => { e.preventDefault(); if (name.trim()) actions.draftSave(name.trim(), false); }}>
          <input type="text" value={name} aria-label={t('lblWebDraftName')} onInput={e => setName(e.currentTarget.value)} />
        </form>
        {taken && <p class="hint">{tNodes('lblWebDraftNameTaken', <b>{name.trim()}</b>)}</p>}
        <div class="actions">
          {discarding
            ? <>
                <span class="muted">{t('lblWebDraftThrowAway')}</span>
                <button onClick={() => setDiscarding(false)}>{t('lblWebDraftKeepThem')}</button>
                <button class="danger" onClick={() => actions.draftDiscard()}>{t('lblWebDraftDiscard')}</button>
              </>
            : <>
                <button onClick={() => setDiscarding(true)}>{t('lblWebDraftDiscard')}</button>
                {taken
                  ? <button class="danger" onClick={() => actions.draftSave(name.trim(), true)}>{t('lblWebDraftReplaceIt')}</button>
                  : <button class="primary" disabled={!name.trim()} onClick={() => actions.draftSave(name.trim(), false)}>{t('lblSave')}</button>}
              </>}
        </div>
      </div>
    </div>
  );
}
