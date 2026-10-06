// The editor's right half: the deck as the deck finder shows it, made editable

import type { ComponentChildren } from 'preact';
import { useLayoutEffect, useState } from 'preact/hooks';
import { isPortrait } from '../form';
import { flyingFor, land } from '../flight';
import { drawHand, regroup, type GroupBy } from './decklist';
import { imageUrl } from '../images';
import { Pip, Pips, SymbolText } from '../symbols';
import { showNotice } from '../notices';
import type { Actions } from '../actions';
import type { CardHandlers } from './drag';
import type { DeckSection, EditorCard, EditorState } from '../protocol';
import { store, stored } from '../storage';
import { t, type TextKey } from '../text';

const GROUP_KEY = 'forge.groupBy';
const VIEW_KEY = 'forge.deckView';
const HAND = 7;

export function DeckHalf({ actions, state, handlers, check }: {
  actions: Actions; state: EditorState; handlers: CardHandlers; check?: ComponentChildren;
}) {
  const [by, setBy] = useState<GroupBy>(storedGroup);
  // Stacked cards are too small to read on a phone, so there the deck opens as a list until the player says otherwise
  const [cards, setCards] = useState(() => (stored(VIEW_KEY) ?? (isPortrait() ? 'list' : 'cards')) !== 'list');
  const view = (asCards: boolean) => {
    setCards(asCards);
    store(VIEW_KEY, asCards ? 'cards' : 'list');
  };
  const [hand, setHand] = useState<EditorCard[] | null>(null);
  const hasCommander = state.commanders.length > 0 || state.commanderWanted;
  const half = Math.ceil(state.sideboard.length / 2);
  const groups = regroup(state, by);
  // A card added from the catalogue flies to its line once the deck shows it there
  useLayoutEffect(() => {
    const key = flyingFor();
    if (!key || !state.landed || key.slice(key.indexOf(':') + 1) !== state.landed) return;
    const zone = key.slice(0, key.indexOf(':'));
    const place = document.querySelector<HTMLElement>(`.deck-half [data-from="${zone}"][data-card="${CSS.escape(state.landed)}"]`);
    land(place, place?.closest('.zone-body'), place?.closest('.zone')?.querySelector('h4'));
  }, [state]);
  return (
    <section class="deck-half">
      <div class="deck-head">
        <div>
          <h3>{state.name} <span class="pips"><Pips colors={state.identity} /></span></h3>
          <p class="sizes">{state.limited
            ? t('lblWebEditorSizesLimited', state.stats.total, state.stats.lands, state.stats.sideboard)
            : state.mainOnly ? t('lblWebFinderSizesNoSideboard', state.stats.main, state.stats.lands)
            : t('lblWebEditorSizes', state.stats.total, state.stats.sideboard, state.stats.lands)}</p>
          {state.verdict
            ? <p class="verdict no">{state.verdict} <button class="link" onClick={showProblems}>{t('lblWebEditorShowThem')}</button></p>
            : <p class="verdict yes">{t('lblWebEditorLegalFor', state.check)}</p>}
          {check && <div class="deck-check">{check}</div>}
          <button class="small" disabled={!state.stats.main} onClick={() => setHand(drawHand(state, HAND))}>{t('lblWebEditorSampleHand')}</button>
        </div>
        <Curve curve={state.stats.curve} creatures={state.stats.creatures} average={state.stats.averageMana} px={34} />
      </div>
      {hasCommander && <CommanderZone actions={actions} state={state} handlers={handlers} />}
      <div class="zone main-zone" data-zone="Main">
        <h4>
          <span class="zn">{t('lblWebDraftMainDeck')}</span>
          <span class="count">{state.stats.main}</span>
          <span class="seg view-seg" role="group" aria-label={t('lblWebEditorShowDeckAs')}>
            <button aria-pressed={cards} onClick={() => view(true)}>{t('lblCards')}</button>
            <button aria-pressed={!cards} onClick={() => view(false)}>{t('lblWebDraftList')}</button>
          </span>
          <select aria-label={t('lblWebDraftGroupBy')} value={by} onChange={e => {
            const next = e.currentTarget.value as GroupBy;
            setBy(next);
            store(GROUP_KEY, next);
          }}>
            <option value="type">{t('lblWebEditorGroupType')}</option>
            <option value="mv">{t('lblWebEditorGroupManaValue')}</option>
            <option value="colour">{t('lblWebEditorGroupColour')}</option>
          </select>
        </h4>
        <div class={cards ? 'zone-body deck-cols' : 'zone-body cols'}>
          {state.main.length === 0 && <p class="none">{state.commanderWanted ? t('lblWebEditorChooseCommanderFirst') : t('lblWebEditorClickToAdd')}</p>}
          {groups.map(g => (
            <div key={g.heading} class="group">
              <h4>{g.heading}<span>{g.cards.reduce((n, c) => n + c.count, 0)}</span></h4>
              {g.cards.map(c => cards
                ? <Stack key={c.name} card={c} zone="Main" landed={state.landed === c.name} handlers={handlers} />
                : <Line key={c.name} card={c} zone="Main" landed={state.landed === c.name} actions={actions} handlers={handlers} mainOnly={state.mainOnly} />)}
            </div>
          ))}
        </div>
      </div>
      <div class="land-row">
        <span class="band-lab">{t('lblWebEditorBasicLands')}</span>
        {state.lands.map(l => (
          <span key={l.name} class={l.allowed ? 'land' : 'land off'} title={l.allowed ? l.name : t('lblWebEditorLandOutside', l.name)}>
            <Pip letter={l.letter} />
            <button class="step" disabled={!l.count} aria-label={t('lblWebEditorOneFewer', l.name)}
              onClick={() => actions.edit({ op: 'lands', count: 0, lands: [{ name: l.name, count: l.count - 1 }] })}>&minus;</button>
            <span class="n">{l.count}</span>
            <button class="step" disabled={!l.allowed} aria-label={t('lblWebEditorOneMore', l.name)}
              onClick={() => actions.edit({ op: 'lands', count: 0, lands: [{ name: l.name, count: l.count + 1 }] })}>+</button>
          </span>
        ))}
        {state.landSets.length > 0 && <>
          <select class="land-set" aria-label={t('lblWebEditorBasicLandsFrom')} value={state.landSet ?? ''}
            onChange={e => actions.edit({ op: 'landSet', name: e.currentTarget.value, count: 0 })}>
            {state.landSets.map(s => <option key={s.code} value={s.code}>{s.name}</option>)}
          </select>
          <button class="small" title={t('lblWebEditorSuggestLandsTip')}
            onClick={() => actions.edit({ op: 'suggestLands', count: 0 })}>{t('lblWebEditorSuggestLands')}</button>
        </>}
      </div>
      {!state.limited && !state.mainOnly && <div class="zone side-zone" data-zone="Sideboard">
        <h4><span class="zn">{t('lblSideboard')}</span><span class="count">{state.stats.sideboard}</span></h4>
        {cards
          ? (
            <div class="zone-body deck-cols">
              <div class="group">{state.sideboard.map(c => <Stack key={c.name} card={c} zone="Sideboard" landed={state.landed === c.name} handlers={handlers} />)}</div>
            </div>
          )
          : (
            <div class="zone-body cols">
              {[state.sideboard.slice(0, half), state.sideboard.slice(half)].map((column, i) => (
                <div key={i}>{column.map(c => <Line key={c.name} card={c} zone="Sideboard" landed={state.landed === c.name} actions={actions} handlers={handlers} />)}</div>
              ))}
            </div>
          )}
      </div>}
      {hand && <SampleHand hand={hand} again={() => setHand(drawHand(state, HAND))}
        more={() => setHand(drawHand(state, hand.length + 1))} close={() => setHand(null)} />}
    </section>
  );
}

function CommanderZone({ actions, state, handlers }: { actions: Actions; state: EditorState; handlers: CardHandlers }) {
  // Conquest's deck keeps the commander it was made for: nothing is dropped here, and nothing is taken away
  const kept = state.mainOnly;
  return (
    <div class="zone commander-zone" data-zone={kept ? undefined : 'Commander'}>
      <h4><span class="zn">{t('lblCommander')}</span><span class="count">{state.commanders.length}</span></h4>
      {state.commanders.length === 0
        ? (
          <div class="commander-empty">
            <span class="card-outline">{t('lblCommander')}</span>
            <div>
              <b>{t('lblWebEditorChooseCommander')}</b>
              <p>{t('lblWebEditorChooseCommanderHint')}</p>
            </div>
          </div>
        )
        : state.commanders.map(c => (
          <div key={c.name} class="commander" data-image={c.image} data-card={c.name} data-from="Commander"
            {...(kept ? {} : handlers(c.name, 'Commander', c.image, 1))}>
            <img alt="" src={imageUrl(c.image)} />
            <div>
              <div class="cname">{c.name} <span class="cost"><SymbolText text={c.cost} /></span></div>
              {c.problem && <div class="flag">! {c.problem}</div>}
              {!kept && <p class="cnote">{state.identity
                ? t('lblWebEditorIdentityNote', state.identity.split('').join(' '))
                : t('lblWebEditorIdentityColourlessNote')}</p>}
            </div>
            {!kept && <button class="small" onClick={() => actions.edit({ op: 'move', name: c.name, from: 'Commander', to: 'Main', count: 1 })}>{t('lblWebEditorChangeCommander')}</button>}
          </div>
        ))}
    </div>
  );
}

/** One card in a section drawn as the card, the copies counted on it; cards in a column overlap to show their names. */
function Stack({ card, zone, landed, handlers }: { card: EditorCard; zone: 'Main' | 'Sideboard'; landed: boolean; handlers: CardHandlers }) {
  return (
    <div class={`deck-stack${card.problem ? ' bad' : ''}${landed ? ' landed' : ''}`} data-image={card.image} data-card={card.name} data-from={zone}
      title={card.problem ? `${card.name}: ${card.problem}` : card.name} {...handlers(card.name, zone, card.image, card.count)}>
      <img alt={card.name} src={imageUrl(card.image)} draggable={false} />
      {card.count > 1 && <span class="deck-count">×{card.count}</span>}
    </div>
  );
}

/** One card in a section: its count, name and cost, and while the pointer is on it, one fewer, one more, and a move to the other section. */
function Line({ card, zone, landed, actions, handlers, mainOnly }: {
  card: EditorCard; zone: 'Main' | 'Sideboard'; landed: boolean; actions: Actions; handlers: CardHandlers; mainOnly?: boolean;
}) {
  const other: DeckSection = zone === 'Main' ? 'Sideboard' : 'Main';
  return (
    <div class={`dk-line ed-line${card.problem ? ' bad' : ''}${landed ? ' landed' : ''}`} data-image={card.image} data-card={card.name} data-from={zone}
      {...handlers(card.name, zone, card.image, card.count)}>
      <span class="n">{card.count}</span>
      <span class="nm">{card.name}</span>
      {card.printings > 1 && <span class="prints">{t('lblWebEditorPrintings', card.printings)}</span>}
      {card.problem && <span class="flag">! {card.problem}</span>}
      <span class="cost"><SymbolText text={card.cost} /></span>
      <span class="ra">
        <button aria-label={t('lblWebEditorOneFewer', card.name)} onClick={() => removeOne(actions, card.name, zone)}>&minus;</button>
        <button aria-label={t('lblWebEditorOneMore', card.name)} onClick={() => actions.edit({ op: 'add', name: card.name, to: zone, count: 1 })}>+</button>
        {!mainOnly && <button onClick={() => actions.edit({ op: 'move', name: card.name, from: zone, to: other, count: 1 })}>{other === 'Main' ? t('lblMain') : t('lblSide')}</button>}
      </span>
    </div>
  );
}

/** Takes one copy out, and offers the removal back in a notice: a slip costs one click, so it needs no confirmation. */
export function removeOne(actions: Actions, name: string, zone: DeckSection): void {
  actions.edit({ op: 'remove', name, from: zone, count: 1 });
  const removed: Partial<Record<DeckSection, TextKey>> = {
    Main: 'lblWebEditorRemovedMain', Sideboard: 'lblWebEditorRemovedSideboard', Commander: 'lblWebEditorRemovedCommander',
  };
  const key = removed[zone];
  const title = key ? t(key, name) : t('lblWebEditorRemovedFrom', name, zone.toLowerCase());
  showNotice({ t: 'notice', title, error: false }, () => actions.editorUndo(), t('lblUndo'));
}

/** A deck's mana curve as bars px tall at most, with the average beside the heading when it is given. */
// Bar heights are pixels because a percentage would resolve against an auto-sized row and collapse
export function Curve({ curve, creatures, average, px }: { curve: number[]; creatures: number[]; average?: number; px: number }) {
  const tallest = Math.max(1, ...curve);
  return (
    <div class="curve">
      <h4>{t('lblWebEditorManaCurve')}{average !== undefined && <> <span>{t('lblWebEditorAverage', average)}</span></>}</h4>
      <div class="bars">
        {curve.map((n, i) => {
          // The last bucket holds everything at that mana value and above
          const label = i === curve.length - 1 ? `${i}+` : `${i}`;
          const beasts = creatures[i] ?? 0;
          return (
            <span key={i} class="bar" title={t(beasts === 1 ? 'lblWebEditorCurveBarOne' : 'lblWebEditorCurveBar', label, beasts, n - beasts)}>
              <span class={n ? 'stack' : 'stack empty'} style={{ height: `${n ? Math.max(3, Math.round((n / tallest) * px)) : 2}px` }}>
                {n > beasts && <i class="other" style={{ flexGrow: n - beasts }} />}
                {beasts > 0 && <i class="creature" style={{ flexGrow: beasts }} />}
              </span>
              <em>{label}</em>
            </span>
          );
        })}
      </div>
      <div class="curve-key"><span><i class="creature" />{t('lblCreatures')}</span><span><i class="other" />{t('lblWebEditorOtherSpells')}</span></div>
    </div>
  );
}

function SampleHand({ hand, again, more, close }: { hand: EditorCard[]; again: () => void; more: () => void; close: () => void }) {
  return (
    <div class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
      <div class="dialog sample-hand">
        <h3>{t('lblWebEditorSampleHand')}</h3>
        <p class="hint">{t('lblWebEditorSampleHandHint')}</p>
        <div class="hand-cards">{hand.map((c, i) => <img key={i} alt={c.name} title={c.name} src={imageUrl(c.image)} />)}</div>
        <div class="actions">
          <button onClick={more}>{t('lblWebEditorDrawOneMore')}</button>
          <button onClick={again}>{t('lblWebEditorNewHand')}</button>
          <button class="primary" onClick={close}>{t('lblClose')}</button>
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
