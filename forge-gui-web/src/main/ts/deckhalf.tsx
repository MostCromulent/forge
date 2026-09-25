// The editor's right half: the deck as the deck finder shows it, made editable. The head carries the verdict and the
// curve; below it the commander, the main deck in two columns, a row of basic lands, and the sideboard.

import { useState } from 'preact/hooks';
import { drawHand, regroup, type GroupBy } from './decklist';
import { imageUrl } from './images';
import { Pips, SymbolText } from './symbols';
import { showNotice } from './notices';
import type { Actions } from './actions';
import type { CardHandlers } from './drag';
import type { DeckSection, EditorCard, EditorState } from './protocol';
import { store, stored } from './storage';

const GROUP_KEY = 'forge.groupBy';
const HAND = 7;
const CURVE_PX = 34;

export function DeckHalf({ actions, state, handlers }: { actions: Actions; state: EditorState; handlers: CardHandlers }) {
  const [by, setBy] = useState<GroupBy>(storedGroup);
  const [hand, setHand] = useState<EditorCard[] | null>(null);
  const hasCommander = state.commanders.length > 0 || state.commanderWanted;
  const half = Math.ceil(state.sideboard.length / 2);
  const groups = regroup(state, by);
  return (
    <section class="deck-half">
      <div class="deck-head">
        <div>
          <h3>{state.name} <span class="pips"><Pips colors={state.identity} /></span></h3>
          <p class="sizes">{state.limited
            ? `${state.stats.main} cards · ${state.stats.lands} lands · ${state.stats.sideboard} left in the pool`
            : `${state.stats.main} cards · ${state.stats.sideboard} sideboard · ${state.stats.lands} lands`}</p>
          {state.verdict
            ? <p class="verdict no">{state.verdict} <button class="link" onClick={showProblems}>Show them</button></p>
            : <p class="verdict yes">Legal for {state.check}.</p>}
          <button class="small" disabled={!state.stats.main} onClick={() => setHand(drawHand(state, HAND))}>Sample hand</button>
        </div>
        <Curve curve={state.stats.curve} average={state.stats.averageMana} />
      </div>
      {hasCommander && <CommanderZone actions={actions} state={state} handlers={handlers} />}
      <div class="zone main-zone" data-zone="Main">
        <h4>
          <span class="zn">Main deck</span>
          <span class="count">{state.stats.main}</span>
          <select aria-label="Group by" value={by} onChange={e => {
            const next = e.currentTarget.value as GroupBy;
            setBy(next);
            store(GROUP_KEY, next);
          }}>
            <option value="type">Group: Type</option>
            <option value="mv">Group: Mana value</option>
            <option value="colour">Group: Colour</option>
          </select>
        </h4>
        <div class="zone-body cols">
          {state.main.length === 0 && <p class="none">{state.commanderWanted ? 'Choose a commander to start.' : 'Click a card to add it.'}</p>}
          {groups.map(g => (
            <div key={g.heading} class="group">
              <h4>{g.heading}<span>{g.cards.reduce((n, c) => n + c.count, 0)}</span></h4>
              {g.cards.map(c => <Line key={c.name} card={c} zone="Main" landed={state.landed === c.name} actions={actions} handlers={handlers} />)}
            </div>
          ))}
        </div>
      </div>
      <div class="land-row">
        <span class="band-lab">Basic lands</span>
        {state.lands.map(l => (
          <span key={l.name} class={l.allowed ? 'land' : 'land off'} title={l.allowed ? l.name : `${l.name} is outside the commander's colours`}>
            <i class={`pip pip-${l.letter}`}>{l.letter}</i>
            <button class="step" disabled={!l.count} aria-label={`One fewer ${l.name}`}
              onClick={() => actions.edit({ op: 'lands', count: 0, lands: [{ name: l.name, count: l.count - 1 }] })}>&minus;</button>
            <span class="n">{l.count}</span>
            <button class="step" disabled={!l.allowed} aria-label={`One more ${l.name}`}
              onClick={() => actions.edit({ op: 'lands', count: 0, lands: [{ name: l.name, count: l.count + 1 }] })}>+</button>
          </span>
        ))}
        {state.limited && <>
          <select class="land-set" aria-label="Basic lands from" value={state.landSet ?? ''}
            onChange={e => actions.edit({ op: 'landSet', name: e.currentTarget.value, count: 0 })}>
            {state.landSets.map(s => <option key={s.code} value={s.code}>{s.name}</option>)}
          </select>
          <button class="small" title="Basic lands for the rest of the deck, as Forge suggests them"
            onClick={() => actions.edit({ op: 'suggestLands', count: 0 })}>Suggest</button>
        </>}
      </div>
      {!state.limited && <div class="zone side-zone" data-zone="Sideboard">
        <h4><span class="zn">Sideboard</span><span class="count">{state.stats.sideboard}</span></h4>
        <div class="zone-body cols">
          {[state.sideboard.slice(0, half), state.sideboard.slice(half)].map((column, i) => (
            <div key={i}>{column.map(c => <Line key={c.name} card={c} zone="Sideboard" landed={state.landed === c.name} actions={actions} handlers={handlers} />)}</div>
          ))}
        </div>
      </div>}
      {hand && <SampleHand hand={hand} again={() => setHand(drawHand(state, HAND))}
        more={() => setHand(drawHand(state, hand.length + 1))} close={() => setHand(null)} />}
    </section>
  );
}

function CommanderZone({ actions, state, handlers }: { actions: Actions; state: EditorState; handlers: CardHandlers }) {
  return (
    <div class="zone commander-zone" data-zone="Commander">
      <h4><span class="zn">Commander</span><span class="count">{state.commanders.length}</span></h4>
      {state.commanders.length === 0
        ? (
          <div class="commander-empty">
            <span class="card-outline">Commander</span>
            <div>
              <b>Choose a commander to start</b>
              <p>Its colours decide which cards the deck may hold. Click Make commander under a card, or drag one here.</p>
            </div>
          </div>
        )
        : state.commanders.map(c => (
          <div key={c.name} class="commander" data-image={c.image} data-card={c.name} data-from="Commander"
            {...handlers(c.name, 'Commander', c.image, 1)}>
            <img alt="" src={imageUrl(c.image)} />
            <div>
              <div class="cname">{c.name} <span class="cost"><SymbolText text={c.cost} /></span></div>
              {c.problem && <div class="flag">! {c.problem}</div>}
              <p class="cnote">Colour identity {state.identity ? state.identity.split('').join(' ') : 'colourless'}. The catalogue follows it.</p>
            </div>
            <button class="small" onClick={() => actions.edit({ op: 'move', name: c.name, from: 'Commander', to: 'Main', count: 1 })}>Change…</button>
          </div>
        ))}
    </div>
  );
}

/** One card in a section: its count, name and cost, and while the pointer is on it, one fewer, one more, and a move to the other section. */
function Line({ card, zone, landed, actions, handlers }: {
  card: EditorCard; zone: 'Main' | 'Sideboard'; landed: boolean; actions: Actions; handlers: CardHandlers;
}) {
  const other: DeckSection = zone === 'Main' ? 'Sideboard' : 'Main';
  return (
    <div class={`dk-line ed-line${card.problem ? ' bad' : ''}${landed ? ' landed' : ''}`} data-image={card.image} data-card={card.name} data-from={zone}
      {...handlers(card.name, zone, card.image, card.count)}>
      <span class="n">{card.count}</span>
      <span class="nm">{card.name}</span>
      {card.printings > 1 && <span class="prints">{card.printings} printings</span>}
      {card.problem && <span class="flag">! {card.problem}</span>}
      <span class="cost"><SymbolText text={card.cost} /></span>
      <span class="ra">
        <button aria-label={`One fewer ${card.name}`} onClick={() => removeOne(actions, card.name, zone)}>&minus;</button>
        <button aria-label={`One more ${card.name}`} onClick={() => actions.edit({ op: 'add', name: card.name, to: zone, count: 1 })}>+</button>
        <button onClick={() => actions.edit({ op: 'move', name: card.name, from: zone, to: other, count: 1 })}>{other === 'Main' ? 'Main' : 'Side'}</button>
      </span>
    </div>
  );
}

/** Takes one copy out, and offers the removal back in a notice: a slip costs one click, so it needs no confirmation. */
export function removeOne(actions: Actions, name: string, zone: DeckSection): void {
  actions.edit({ op: 'remove', name, from: zone, count: 1 });
  showNotice({ t: 'notice', title: `Removed ${name} from ${zone === 'Main' ? 'the main deck' : zone.toLowerCase()}`, error: false },
    () => actions.editorUndo(), 'Undo');
}

function Curve({ curve, average }: { curve: number[]; average: number }) {
  const tallest = Math.max(1, ...curve);
  return (
    <div class="curve">
      <h4>Mana curve <span>avg {average}</span></h4>
      <div class="bars">
        {curve.map((n, i) => {
          const label = i === curve.length - 1 ? `${i}+` : `${i}`;
          return <span key={i} class="bar" title={`${n} at ${label}`}><i style={{ height: `${n ? Math.max(3, Math.round((n / tallest) * CURVE_PX)) : 2}px` }} /><em>{label}</em></span>;
        })}
      </div>
    </div>
  );
}

function SampleHand({ hand, again, more, close }: { hand: EditorCard[]; again: () => void; more: () => void; close: () => void }) {
  return (
    <div class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
      <div class="dialog sample-hand">
        <h3>Sample hand</h3>
        <p class="hint">A shuffle of the main deck. Nothing is saved.</p>
        <div class="hand-cards">{hand.map((c, i) => <img key={i} alt={c.name} title={c.name} src={imageUrl(c.image)} />)}</div>
        <div class="actions">
          <button onClick={more}>Draw one more</button>
          <button onClick={again}>New hand</button>
          <button class="primary" onClick={close}>Close</button>
        </div>
      </div>
    </div>
  );
}

function showProblems(): void {
  document.querySelector('.editor-page .ed-line.bad, .editor-page .commander .flag')?.scrollIntoView({ block: 'center', behavior: 'smooth' });
}

function storedGroup(): GroupBy {
  const saved = stored(GROUP_KEY);
  return saved === 'mv' || saved === 'colour' ? saved : 'type';
}
