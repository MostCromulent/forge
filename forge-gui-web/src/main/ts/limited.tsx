// The Limited pages against the computer: your saved events as a shelf of cards, the setup form with the event it
// makes beside it, and the list of opponents a pool's deck plays. The deck itself is built in the deck editor.

import { useEffect, useState } from 'preact/hooks';
import { StepForm, draftCombo, draftSteps, draftTicket, sealedAction, sealedSteps, sealedTicket, type DraftValue, type SealedValue } from './setup';
import { HeadControls, PageHeader, SetupHead, WAY_NAMES } from './header';
import { Pips } from './symbols';
import { changeUi } from './ui';
import type { Actions } from './actions';
import type { Model } from './model';
import type { PoolRow } from './protocol';

/** Desktop's limited deck is at least forty cards. */
const DECK_SIZE = 40;
const GAMES: [number, string][] = [[1, '1 game'], [3, 'Best of 3'], [5, 'Best of 5']];

export function Limited({ model, actions }: { model: Model; actions: Actions }) {
  const [creating, setCreating] = useState(false);
  const draft = model.eventKind === 'draft';
  const pools = (draft ? model.limitedPools?.draft : model.limitedPools?.sealed) ?? [];
  const pool = model.eventPool ? pools.find(p => p.name === model.eventPool) : undefined;
  const kind = draft ? 'Draft' : 'Sealed';
  const toEvents = pool ? () => actions.poolClose() : creating ? () => setCreating(false) : undefined;
  const trail = [
    { label: 'Start', go: () => { changeUi(u => { u.menuChoice = null; }); actions.limitedLeave(); } },
    { label: WAY_NAMES.play, go: () => { changeUi(u => { u.menuChoice = 'play'; }); actions.limitedLeave(); } },
    { label: kind, go: toEvents },
    ...(pool ? [{ label: pool.name }] : creating ? [{ label: 'New event' }] : []),
  ];
  return (
    <div class="limited-page">
      <PageHeader class="limited-head">
        <div class="head-right">
          <HeadControls />
          <button onClick={() => (pool ? actions.poolClose() : actions.limitedLeave())}>Back</button>
        </div>
      </PageHeader>
      {pool
        ? <SetupHead trail={trail} title={pool.name} aside={<YourDeck pool={pool} actions={actions} />}
            sub={`${draft ? 'Booster draft' : 'Sealed'} · ${pool.opponents.length} opponents built from the same ${draft ? 'draft' : 'packs'}`} />
        : creating
          ? <SetupHead trail={trail} title={draft ? 'New booster draft' : 'New sealed event'}
              aside={<button class="ghost" onClick={() => setCreating(false)}>Back to your events</button>} />
          : <SetupHead trail={trail} title={draft ? 'Your drafts' : 'Your sealed events'}
              sub={draft ? 'Draft against seven AI drafters, build forty cards, and play the decks they drafted.'
                : 'Open packs, build forty cards, and play the decks built from the same packs.'} />}
      {model.error && <p class="limited-error">{model.error}</p>}
      {pool
        ? <Opponents pool={pool} draft={draft} actions={actions} />
        : creating
          ? (draft ? <DraftSetup model={model} actions={actions} /> : <SealedSetup model={model} actions={actions} />)
          : <Events pools={pools} draft={draft} actions={actions} create={() => setCreating(true)} />}
    </div>
  );
}

/** A day as the cards say it: 28 Sept. */
export function shortDay(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
}

/** The saved pools as cards, each with the one thing to do next, after a card that starts a new event. */
function Events({ pools, draft, actions, create }: { pools: PoolRow[]; draft: boolean; actions: Actions; create: () => void }) {
  const [menu, setMenu] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  // The menu closes on any press outside it, as the table's menus do
  useEffect(() => {
    if (menu === null) return;
    const outside = (e: PointerEvent) => { if (!(e.target as Element).closest?.('.ev-more')) setMenu(null); };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [menu]);
  return (
    <div class="event-shelf">
      <button class="ev new" onClick={create}>
        <span class="plus" aria-hidden="true">+</span>
        <b>{draft ? 'New draft' : 'New sealed event'}</b>
        <span>{draft ? 'Choose the packs and draft against seven AI drafters.' : 'Choose the packs, open them, and build your deck.'}</span>
      </button>
      {pools.map(p => (
        <article key={p.name} class={p.built ? 'ev' : 'ev unbuilt'}>
          <div class="ev-top">
            <b>{p.name}</b>
            {p.changed && <span class="sub">Saved {shortDay(p.changed)}</span>}
          </div>
          <div class="ev-mid">
            <span class="ev-line deck">
              {p.built ? <><span class="pips"><Pips colors={p.colors} /></span>Deck built · {p.deckSize} cards</> : 'No deck yet'}
            </span>
            <span class="ev-line dim">{p.opponents.length} opponents from the same {draft ? 'draft' : 'packs'}</span>
          </div>
          <div class="ev-foot">
            {deleting === p.name
              ? <>
                  <span class="ev-ask">Delete this pool?</span>
                  <span class="sp" />
                  <button onClick={() => setDeleting(null)}>Keep</button>
                  <button class="danger" onClick={() => { setDeleting(null); actions.poolDelete(p.name); }}>Delete</button>
                </>
              : <>
                  {p.built
                    ? <><button class="primary" onClick={() => actions.poolOpen(p.name)}>Play</button>
                        <button onClick={() => actions.poolEdit(p.name)}>Edit deck</button></>
                    : <button class="primary" onClick={() => actions.poolEdit(p.name)}>Build deck</button>}
                  <span class="sp" />
                  <span class="ev-more">
                    <button class="more" title="More" aria-label={`More for ${p.name}`} aria-expanded={menu === p.name}
                      onClick={() => setMenu(menu === p.name ? null : p.name)}>⋯</button>
                    {menu === p.name && (
                      <div class="ev-menu" role="menu">
                        <button role="menuitem" onClick={() => { setMenu(null); setDeleting(p.name); }}>Delete pool</button>
                      </div>
                    )}
                  </span>
                </>}
          </div>
        </article>
      ))}
    </div>
  );
}

function SealedSetup({ model, actions }: { model: Model; actions: Actions }) {
  const [value, setValue] = useState<SealedValue>({});
  // Opening packs takes seconds; until the editor, an error or a taken name answers, a second click would open them again
  const [busy, setBusy] = useState(false);
  useEffect(() => setBusy(false), [model.error, model.nameTaken]);
  const options = model.limitedOptions;
  if (!options) return <p class="muted pools-wait">Reading what Forge can open…</p>;
  const send = (replace: boolean) => {
    setBusy(true);
    actions.sealedCreate({
      product: value.product!, block: value.block, combo: value.combo, edition: value.edition, template: value.template,
      cubeId: value.cubeId, packs: value.packs ?? 0, name: value.name!, replace,
    });
  };
  // The server names a pool it would replace, and waits for a yes, as desktop's sealed screen asks
  const taken = model.nameTaken !== null && model.nameTaken === value.name;
  return (
    <div class="setup">
      <StepForm steps={sealedSteps(options)} value={value} onChange={setValue} ticket={sealedTicket(options)}
        action={sealedAction} submit={() => send(false)} busy={busy}
        problem={taken ? (
          <span class="sentence taken">
            You already have a pool called <b>{value.name}</b>.
            <button class="danger" onClick={() => send(true)}>Replace it</button>
          </span>
        ) : null} />
    </div>
  );
}

function DraftSetup({ model, actions }: { model: Model; actions: Actions }) {
  const [value, setValue] = useState<DraftValue>({});
  const [busy, setBusy] = useState(false);
  useEffect(() => setBusy(false), [model.error]);
  const options = model.limitedOptions;
  if (!options) return <p class="muted pools-wait">Reading what Forge can draft…</p>;
  return (
    <div class="setup">
      <StepForm steps={draftSteps(options)} value={value} onChange={setValue} ticket={draftTicket()}
        action="Start drafting" busy={busy} submit={() => {
          setBusy(true);
          actions.draftStart({ product: value.product!, block: value.block, combo: draftCombo(value), cube: value.cube,
            theme: value.theme, cubeId: value.cubeId });
        }} />
    </div>
  );
}

/** How many opponents a free-for-all can seat: a match holds at most four players, and a pool has so many decks. */
export function severalCap(opponents: number): number {
  return Math.min(3, opponents);
}

const COLOUR_NAMES: Record<string, string> = { W: 'White', U: 'Blue', B: 'Black', R: 'Red', G: 'Green' };

/** A deck's colours in words: White–Green, or the count past three, or Colourless. */
export function colourName(colors: string): string {
  const names = [...colors].map(c => COLOUR_NAMES[c]).filter(Boolean);
  if (!names.length) return 'Colourless';
  if (names.length > 3) return names.length === 5 ? 'All five colours' : 'Four colours';
  return names.join('–');
}

/** Your deck by the heading: its colours, its size, and the way back to the editor. */
function YourDeck({ pool, actions }: { pool: PoolRow; actions: Actions }) {
  return (
    <span class="your-deck">
      {pool.built && <span class="pips"><Pips colors={pool.colors} /></span>}
      <span><b>Your deck</b> <span class="muted">· {pool.deckSize} {pool.deckSize === 1 ? 'card' : 'cards'}</span></span>
      <button onClick={() => actions.poolEdit(pool.name)}>Edit deck</button>
    </span>
  );
}

/**
 * The opponents as one numbered list, each row playing that one. The numbers are the gauntlet's order, so the gauntlet
 * is the list played through, and a draft's free-for-all is chosen at random from it.
 */
function Opponents({ pool, draft, actions }: { pool: PoolRow; draft: boolean; actions: Actions }) {
  const cap = severalCap(pool.opponents.length);
  const [count, setCount] = useState(cap);
  const [games, setGames] = useState(3);
  const short = pool.deckSize < DECK_SIZE;
  const several = draft && cap >= 2;
  const play = (mode: 'one' | 'several' | 'gauntlet', opponent = 0) => actions.poolPlay(pool.name, mode, opponent, count, games);
  return (
    <div class="opponents">
      <div class="opp-head">
        <span class="kicker">Opponents</span>
        <span class="kicker">Games</span>
        <span class="count" role="group" aria-label="Games in a match">
          {GAMES.map(([n, label]) => <button key={n} aria-pressed={games === n} onClick={() => setGames(n)}>{label}</button>)}
        </span>
      </div>
      {short && <p class="opp-short">Your deck has {pool.deckSize} {pool.deckSize === 1 ? 'card' : 'cards'}. Limited decks need {DECK_SIZE} when Forge enforces deck legality.</p>}
      <ol class="opp-list">
        {pool.opponents.map((o, i) => (
          <li key={o.name} class="opp-row">
            <span class="n">{i + 1}</span>
            <span class="pips"><Pips colors={o.colors} /></span>
            <span class="opp-name">{o.name} <span class="muted">· {colourName(o.colors)}</span></span>
            <button class="opp-play" onClick={() => play('one', i)} aria-label={`Play ${o.name}`}>Play</button>
          </li>
        ))}
      </ol>
      <div class="opp-foot">
        <span class="say"><b>Gauntlet:</b> all {pool.opponents.length} in this order, a match each. Lose one and it ends.</span>
        <button class="outline" onClick={() => play('gauntlet')}>Start the gauntlet</button>
      </div>
      {several && (
        <div class="opp-foot">
          <span class="say"><b>Free-for-all:</b> you against several decks at once, chosen at random.</span>
          <span class="stepper">
            <button class="step" disabled={count <= 2} aria-label="One opponent fewer" onClick={() => setCount(count - 1)}>&minus;</button>
            <span class="n">{count}</span>
            <button class="step" disabled={count >= cap} aria-label="One opponent more" onClick={() => setCount(count + 1)}>+</button>
          </span>
          <button class="outline" onClick={() => play('several')}>Play {count} opponents</button>
        </div>
      )}
    </div>
  );
}
