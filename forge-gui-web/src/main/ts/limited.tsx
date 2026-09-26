// The Limited pages, as desktop's Sealed screen lays them out: the saved pools with New event beside them, the setup
// form, and the opponents a pool's deck can be played against. The deck itself is built in the deck editor.

import { useEffect, useState } from 'preact/hooks';
import { StepForm, draftCombo, draftSentence, draftSteps, sealedSentence, sealedSteps, type DraftValue, type SealedValue } from './setup';
import { HeadControls, PageHeader } from './header';
import type { Actions } from './actions';
import type { Model } from './model';
import type { PoolRow } from './protocol';

/** Desktop's limited deck is at least forty cards. */
const DECK_SIZE = 40;
const GAMES = [1, 3, 5];

export function Limited({ model, actions }: { model: Model; actions: Actions }) {
  const [creating, setCreating] = useState(false);
  const draft = model.eventKind === 'draft';
  const pools = (draft ? model.limitedPools?.draft : model.limitedPools?.sealed) ?? [];
  const pool = model.eventPool ? pools.find(p => p.name === model.eventPool) : undefined;
  return (
    <div class="limited-page">
      <PageHeader class="limited-head">
        <span class="limited-title">{draft ? 'Booster draft' : 'Sealed'}</span>
        <span class="muted">Against the computer</span>
        <div class="head-right">
          <HeadControls />
          <button onClick={() => (pool ? actions.poolClose() : actions.limitedLeave())}>Back</button>
        </div>
      </PageHeader>
      {model.error && <p class="limited-error">{model.error}</p>}
      {pool
        ? <Opponents pool={pool} draft={draft} actions={actions} />
        : creating
          ? (draft ? <DraftSetup model={model} actions={actions} cancel={() => setCreating(false)} />
            : <SealedSetup model={model} actions={actions} cancel={() => setCreating(false)} />)
          : <Pools pools={pools} draft={draft} actions={actions} create={() => setCreating(true)} />}
    </div>
  );
}

function Pools({ pools, draft, actions, create }: { pools: PoolRow[]; draft: boolean; actions: Actions; create: () => void }) {
  const [deleting, setDeleting] = useState<string | null>(null);
  return (
    <div class="pools">
      <div class="pools-new">
        <button class="primary big" onClick={create}>New event</button>
        <p class="muted">{draft
          ? "Draft three packs against seven computer drafters, build a forty-card deck from your picks, and play their decks."
          : "Open sealed packs, build a forty-card deck from them, and play it against the computer's decks from the same packs."}</p>
      </div>
      <div class="pools-list">
        <h4>Your sealed pools <span>{pools.length}</span></h4>
        {pools.length === 0 && <p class="muted">No pools yet. New event opens one.</p>}
        {pools.map(p => (
          <div key={p.name} class="pool-row">
            <span class="pool-name"><b>{p.name}</b><span>{p.opponents.length} opponents</span></span>
            <span class={p.built ? 'pool-state ok' : 'pool-state'}>{p.built ? `Deck built · ${p.deckSize}` : 'No deck yet'}</span>
            <span class="pool-acts">
              {deleting === p.name
                ? <>
                    <span class="muted">Delete {p.name}?</span>
                    <button onClick={() => setDeleting(null)}>Keep</button>
                    <button class="danger" onClick={() => { setDeleting(null); actions.poolDelete(p.name); }}>Delete</button>
                  </>
                : <>
                    <button onClick={() => actions.poolEdit(p.name)}>{p.built ? 'Edit deck' : 'Build deck'}</button>
                    {p.built && <button class="primary" onClick={() => actions.poolOpen(p.name)}>Play</button>}
                    <button class="link" onClick={() => setDeleting(p.name)}>Delete</button>
                  </>}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

function SealedSetup({ model, actions, cancel }: { model: Model; actions: Actions; cancel: () => void }) {
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
      <StepForm title="New sealed event" steps={sealedSteps(options)} value={value} onChange={setValue}
        sentence={v => sealedSentence(options, v)} action="Open the packs" submit={() => send(false)} busy={busy}
        problem={taken ? (
          <span class="sentence taken">
            You already have a pool called <b>{value.name}</b>.
            <button class="danger" onClick={() => send(true)}>Replace it</button>
          </span>
        ) : null} />
      <button class="link setup-cancel" onClick={cancel}>Cancel</button>
    </div>
  );
}

function DraftSetup({ model, actions, cancel }: { model: Model; actions: Actions; cancel: () => void }) {
  const [value, setValue] = useState<DraftValue>({});
  const [busy, setBusy] = useState(false);
  useEffect(() => setBusy(false), [model.error]);
  const options = model.limitedOptions;
  if (!options) return <p class="muted pools-wait">Reading what Forge can draft…</p>;
  return (
    <div class="setup">
      <StepForm title="New booster draft" steps={draftSteps(options)} value={value} onChange={setValue}
        sentence={draftSentence} action="Start draft" busy={busy} submit={() => {
          setBusy(true);
          actions.draftStart({ product: value.product!, block: value.block, combo: draftCombo(value), cube: value.cube,
            theme: value.theme, cubeId: value.cubeId });
        }} />
      <button class="link setup-cancel" onClick={cancel}>Cancel</button>
    </div>
  );
}

/** How many opponents a free-for-all can seat: a match holds at most four players, and a pool has so many decks. */
export function severalCap(opponents: number): number {
  return Math.min(3, opponents);
}

type Mode = 'one' | 'several' | 'gauntlet';

/** Desktop's ways to play a pool: one opponent, several at once (a draft only), or every one in turn. */
function Opponents({ pool, draft, actions }: { pool: PoolRow; draft: boolean; actions: Actions }) {
  const [mode, setMode] = useState<Mode>('one');
  const [opponent, setOpponent] = useState(0);
  const cap = severalCap(pool.opponents.length);
  const [count, setCount] = useState(cap);
  const [games, setGames] = useState(3);
  const short = pool.deckSize < DECK_SIZE;
  const chosen = pool.opponents[opponent];
  const several = draft && cap >= 2;
  const play = mode === 'gauntlet' ? 'Start the gauntlet' : mode === 'several' ? `Play ${count} opponents` : `Play ${chosen?.name}`;
  const row = (id: Mode, title: string, line: string, extra?: preact.ComponentChildren) => (
    <div class={mode === id ? 'radio on' : 'radio'} data-mode={id} onClick={() => setMode(id)}>
      <i />
      <div><b>{title}</b><span>{line}</span>{extra}</div>
    </div>
  );
  return (
    <div class="opponents">
      <div class="opps">
        <h3>{pool.name} <span class="muted">Your deck: {pool.deckSize} {pool.deckSize === 1 ? 'card' : 'cards'}</span></h3>
        {row('one', 'One opponent', 'A match against one of the decks built from the same packs.', (
          <div class="opp-list">
            {pool.opponents.map((o, i) => (
              <button key={o.name} class={i === opponent ? 'opp on' : 'opp'} aria-pressed={i === opponent}
                onClick={() => { setMode('one'); setOpponent(i); }}>
                {o.name}<span class="opp-colours">{o.colors.split('').map(c => <i key={c} class={`pip sm pip-${c}`}>{c}</i>)}</span>
              </button>
            ))}
          </div>
        ))}
        {several && row('several', 'Several opponents', 'A free-for-all against decks chosen at random.', (
          <span class="stepper">
            <button class="step" disabled={count <= 2} aria-label="One opponent fewer" onClick={() => setCount(count - 1)}>&minus;</button>
            <span class="n">{count}</span>
            <button class="step" disabled={count >= cap} aria-label="One opponent more" onClick={() => setCount(count + 1)}>+</button>
          </span>
        ))}
        {row('gauntlet', 'Gauntlet', `All ${pool.opponents.length} opponents, one match at a time. Win a match to meet the next.`)}
      </div>
      <div class="opp-side">
        <span class="muted">Games in match</span>
        <span class="seg" role="group" aria-label="Games in match">
          {GAMES.map(n => <button key={n} aria-pressed={games === n} onClick={() => setGames(n)}>{n === 1 ? '1' : `Best of ${n}`}</button>)}
        </span>
        <button class="primary" disabled={mode === 'one' && !chosen} onClick={() => actions.poolPlay(pool.name, mode, opponent, count, games)}>
          {play}
        </button>
        {short && <span class="muted">Your deck has {pool.deckSize} {pool.deckSize === 1 ? 'card' : 'cards'}. Limited decks need {DECK_SIZE} when Forge enforces deck legality.</span>}
        <button onClick={() => actions.poolEdit(pool.name)}>Edit deck</button>
      </div>
    </div>
  );
}
