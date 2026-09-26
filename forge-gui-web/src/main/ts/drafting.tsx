// Drafting, laid out like the deck editor: the pack fills the left, the picks the right, and the pack dial heads the
// picks with where the next pack comes from. A click selects a card and a second click, or Enter, picks it, because a
// pick cannot be taken back and a timer can make a single click a slip.

import { useEffect, useState } from 'preact/hooks';
import { Dial } from './packdial';
import { nextFrom } from './dial';
import { imageUrl } from './images';
import { SymbolText } from './symbols';
import { changeUi } from './ui';
import { HeadControls, PageHeader } from './header';
import type { Actions } from './actions';
import type { Model } from './model';
import type { DraftCard, DraftState } from './protocol';

type GroupBy = 'colour' | 'type' | 'pick';

export function Drafting({ model, actions }: { model: Model; actions: Actions }) {
  const state = model.draft;
  const [leaving, setLeaving] = useState(false);
  const [log, setLog] = useState(false);
  // An online draft belongs to the table and goes on without this browser, so leaving only goes back to the table
  const online = model.inLobby;
  return (
    <div class="drafting-page">
      <PageHeader class="limited-head">
        <span class="limited-title">Booster draft</span>
        {state && <span class="muted">{state.product} · {state.seats.length} seats</span>}
        <div class="head-right">
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
          <Pack state={state} actions={actions} />
          <Picks state={state} />
        </div>
      )}
      {state?.done && <SaveDraft model={model} state={state} actions={actions} />}
      {log && state && <DraftLog lines={state.log} close={() => setLog(false)} />}
    </div>
  );
}

function Pack({ state, actions }: { state: DraftState; actions: Actions }) {
  const [selected, setSelected] = useState<number | null>(null);
  // A new state clears the selection, since its cards are not the ones selected
  useEffect(() => setSelected(null), [state.step]);
  const pick = (index: number) => actions.draftPick(state.step, index);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Enter' && selected !== null && !(e.target instanceof HTMLInputElement)) pick(selected);
    };
    addEventListener('keydown', onKey);
    return () => removeEventListener('keydown', onKey);
  }, [selected, state.step]);
  return (
    <section class="draft-pack">
      <div class="bar"><span class="band-lab">Pack {state.pack} · {state.cards.length} {state.cards.length === 1 ? 'card' : 'cards'}</span></div>
      <div class="cat-grid">
        {state.cards.map((card, i) => (
          <div key={`${card.image}-${i}`} class={i === selected ? 'slot draft-slot chosen' : 'slot draft-slot'} data-card={card.name}>
            <button class="tile" title={card.name} onClick={() => (i === selected ? pick(i) : setSelected(i))}>
              <span class="tile-name">{card.name}</span>
              <img loading="lazy" alt="" src={imageUrl(card.image)} onError={e => { e.currentTarget.hidden = true; }} />
              {card.rank !== undefined && <span class="rank" title="Draft ranking">{card.rank}</span>}
            </button>
            {i === selected && <span class="confirm">Pick · click again or Enter</span>}
          </div>
        ))}
        {state.cards.length === 0 && !state.done && <p class="muted">Waiting for a pack…</p>}
      </div>
    </section>
  );
}

function Picks({ state }: { state: DraftState }) {
  const [by, setBy] = useState<GroupBy>('colour');
  const direction = (state.direction < 0 ? -1 : 1) as 1 | -1;
  const depths = state.seats.map(s => s.packs);
  const next = nextFrom(depths, direction);
  const busiest = depths.reduce((best, d, i) => (d > depths[best] ? i : best), 0);
  const neighbour = state.seats[(state.seats.length + direction) % state.seats.length];
  return (
    <section class="draft-picks">
      <div class="draft-head">
        <Dial state={state} />
        <div class="draft-where">
          <b>Pack {state.pack} <span class="muted">· pick {state.pick} of {state.packSize}</span></b>
          <span class="muted">Passing {direction > 0 ? 'right' : 'left'}, to {neighbour?.name}</span>
          {next !== null && <span class="muted">Next pack from <b>{state.seats[next].name}</b></span>}
          {depths[busiest] > 2 && <span class="muted">{busiest === 0 ? 'You are' : `${state.seats[busiest].name} is`} holding {depths[busiest]} packs</span>}
        </div>
      </div>
      <div class="draft-picks-head">
        <h3>Your picks <span class="muted">{state.picks.length}</span></h3>
        <span class="seg" role="group" aria-label="Group picks by">
          {(['colour', 'type', 'pick'] as GroupBy[]).map(g => (
            <button key={g} aria-pressed={by === g} onClick={() => setBy(g)}>{g === 'pick' ? 'Pick order' : g[0].toUpperCase() + g.slice(1)}</button>
          ))}
        </span>
      </div>
      <div class="zone-body cols">
        {grouped(state.picks, by).map(([heading, cards]) => (
          <div key={heading} class="group">
            <h4>{heading}<span>{cards.length}</span></h4>
            {cards.map((c, i) => (
              <div key={`${c.name}-${i}`} class="ed-line" data-card={c.name}>
                <span class="nm">{c.name}</span>
                <SymbolText text={c.cost} />
                <span class="muted pk" title={`Pack ${c.pack}, pick ${c.pick}`}>{c.pack}·{c.pick}</span>
              </div>
            ))}
          </div>
        ))}
      </div>
    </section>
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

function grouped(picks: DraftCard[], by: GroupBy): [string, DraftCard[]][] {
  if (by === 'pick') return picks.length ? [['In pick order', picks]] : [];
  const groups = new Map<string, DraftCard[]>();
  for (const c of picks) {
    const key = by === 'type' ? typeHeading(c.type)
      : c.colors.length === 0 || c.colors === 'C' ? 'Colourless' : c.colors.length > 1 ? 'Multicolour' : COLOURS[c.colors] ?? c.colors;
    groups.set(key, [...(groups.get(key) ?? []), c]);
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
