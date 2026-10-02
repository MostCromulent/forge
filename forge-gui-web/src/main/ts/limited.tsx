// The Limited pages against the computer: your saved events as a shelf of cards, the setup form with the event it
// makes beside it, and the list of opponents a pool's deck plays. The deck itself is built in the deck editor.

import { useEffect, useState } from 'preact/hooks';
import { usePressOutside } from './hooks';
import { StepForm, draftCombo, draftSteps, draftTicket, sealedAction, sealedSteps, sealedTicket, type DraftValue, type SealedValue } from './setup';
import { HeadControls, PageHeader, SetupHead, WAY_NAMES } from './header';
import { Pips } from './symbols';
import { changeUi } from './ui';
import type { Actions } from './actions';
import type { Model } from './model';
import type { PoolRow } from './protocol';
import { t, tNodes, type TextKey } from './text';

/** Desktop's limited deck is at least forty cards. */
const DECK_SIZE = 40;
const GAMES = [1, 3, 5];

export function Limited({ model, actions }: { model: Model; actions: Actions }) {
  const [creating, setCreating] = useState(false);
  const draft = model.eventKind === 'draft';
  const pools = (draft ? model.limitedPools?.draft : model.limitedPools?.sealed) ?? [];
  const pool = model.eventPool ? pools.find(p => p.name === model.eventPool) : undefined;
  const kind = draft ? t('lblDraft') : t('lblSealed');
  const toEvents = pool ? () => actions.poolClose() : creating ? () => setCreating(false) : undefined;
  const trail = [
    { label: t('lblWebLimitedStart'), go: () => { changeUi(u => { u.menuChoice = null; }); actions.limitedLeave(); } },
    { label: WAY_NAMES.play, go: () => { changeUi(u => { u.menuChoice = 'play'; }); actions.limitedLeave(); } },
    { label: kind, go: toEvents },
    ...(pool ? [{ label: pool.name }] : creating ? [{ label: t('lblWebEventNewEvent') }] : []),
  ];
  return (
    <div class="limited-page">
      <PageHeader class="limited-head">
        <div class="head-right">
          <HeadControls />
          <button onClick={() => (pool ? actions.poolClose() : actions.limitedLeave())}>{t('lblBack')}</button>
        </div>
      </PageHeader>
      {pool
        ? <SetupHead trail={trail} title={pool.name} aside={<YourDeck pool={pool} actions={actions} />}
            sub={draft ? t('lblWebLimitedDraftSub', pool.opponents.length) : t('lblWebLimitedSealedSub', pool.opponents.length)} />
        : creating
          ? <SetupHead trail={trail} title={draft ? t('lblWebLimitedNewBoosterDraft') : t('lblWebEventNewSealedEvent')}
              aside={<button class="ghost" onClick={() => setCreating(false)}>{t('lblWebLimitedBackToEvents')}</button>} />
          : <SetupHead trail={trail} title={draft ? t('lblWebLimitedYourDrafts') : t('lblWebLimitedYourSealedEvents')}
              sub={draft ? t('lblWebLimitedDraftsIntro') : t('lblWebLimitedSealedIntro')} />}
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
  usePressOutside(menu !== null, '.ev-more', () => setMenu(null));
  return (
    <div class="event-shelf">
      <button class="ev new" onClick={create}>
        <span class="plus" aria-hidden="true">+</span>
        <b>{draft ? t('lblWebEventNewDraft') : t('lblWebEventNewSealedEvent')}</b>
        <span>{draft ? t('lblWebLimitedNewDraftLine') : t('lblWebLimitedNewSealedLine')}</span>
      </button>
      {pools.map(p => (
        <article key={p.name} class={p.built ? 'ev' : 'ev unbuilt'}>
          <div class="ev-top">
            <b>{p.name}</b>
            {p.changed && <span class="sub">{t('lblWebLimitedSaved', shortDay(p.changed))}</span>}
          </div>
          <div class="ev-mid">
            <span class="ev-line deck">
              {p.built ? <><span class="pips"><Pips colors={p.colors} /></span>{t('lblWebLimitedDeckBuilt', p.deckSize)}</> : t('lblWebLimitedNoDeckYet')}
            </span>
            <span class="ev-line dim">{t(draft ? 'lblWebLimitedOpponentsDraft' : 'lblWebLimitedOpponentsPacks', p.opponents.length)}</span>
          </div>
          <div class="ev-foot">
            {deleting === p.name
              ? <>
                  <span class="ev-ask">{t('lblWebLimitedDeletePoolAsk')}</span>
                  <span class="sp" />
                  <button onClick={() => setDeleting(null)}>{t('lblWebLimitedKeep')}</button>
                  <button class="danger" onClick={() => { setDeleting(null); actions.poolDelete(p.name); }}>{t('lblDelete')}</button>
                </>
              : <>
                  {p.built
                    ? <><button class="primary" onClick={() => actions.poolOpen(p.name)}>{t('lblPlay')}</button>
                        <button onClick={() => actions.poolEdit(p.name)}>{t('lblWebLimitedEditDeck')}</button></>
                    : <button class="primary" onClick={() => actions.poolEdit(p.name)}>{t('lblWebLimitedBuildDeck')}</button>}
                  <span class="sp" />
                  <span class="ev-more">
                    <button class="more" title={t('lblWebLimitedMore')} aria-label={t('lblWebLimitedMoreFor', p.name)} aria-expanded={menu === p.name}
                      onClick={() => setMenu(menu === p.name ? null : p.name)}>⋯</button>
                    {menu === p.name && (
                      <div class="ev-menu" role="menu">
                        <button role="menuitem" onClick={() => { setMenu(null); setDeleting(p.name); }}>{t('lblWebLimitedDeletePool')}</button>
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
  if (!options) return <p class="muted pools-wait">{t('lblWebLimitedReadingOpen')}</p>;
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
            {tNodes('lblWebLimitedPoolTaken', <b>{value.name}</b>)}
            <button class="danger" onClick={() => send(true)}>{t('lblWebDraftReplaceIt')}</button>
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
  if (!options) return <p class="muted pools-wait">{t('lblWebLimitedReadingDraft')}</p>;
  return (
    <div class="setup">
      <StepForm steps={draftSteps(options)} value={value} onChange={setValue} ticket={draftTicket()}
        action={t('lblWebLimitedStartDrafting')} busy={busy} submit={() => {
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

const COLOUR_NAMES: Record<string, TextKey> = { W: 'lblWhite', U: 'lblBlue', B: 'lblBlack', R: 'lblRed', G: 'lblGreen' };

/** A deck's colours in words: White–Green, or the count past three, or Colourless. */
export function colourName(colors: string): string {
  const names = [...colors].map(c => COLOUR_NAMES[c]).filter(Boolean).map(key => t(key));
  if (!names.length) return t('lblWebDraftColourless');
  if (names.length > 3) return names.length === 5 ? t('lblWebLimitedAllFiveColours') : t('lblWebLimitedFourColours');
  return names.join('–');
}

/** Your deck by the heading: its colours, its size, and the way back to the editor. */
function YourDeck({ pool, actions }: { pool: PoolRow; actions: Actions }) {
  return (
    <span class="your-deck">
      {pool.built && <span class="pips"><Pips colors={pool.colors} /></span>}
      <span><b>{t('lblWebSetupYourDeck')}</b> <span class="muted">{t(pool.deckSize === 1 ? 'lblWebLimitedDotOneCard' : 'lblWebLimitedDotCards', pool.deckSize)}</span></span>
      <button onClick={() => actions.poolEdit(pool.name)}>{t('lblWebLimitedEditDeck')}</button>
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
        <span class="kicker">{t('lblWebSetupOpponents')}</span>
        <span class="kicker">{t('lblWebLimitedGames')}</span>
        <span class="count" role="group" aria-label={t('lblWebLimitedGamesInMatch')}>
          {GAMES.map(n => <button key={n} aria-pressed={games === n} onClick={() => setGames(n)}>
            {n === 1 ? t('lblWebLimitedOneGame') : t('lblWebLimitedBestOf', n)}</button>)}
        </span>
      </div>
      {short && <p class="opp-short">{t(pool.deckSize === 1 ? 'lblWebLimitedShortOne' : 'lblWebLimitedShortMany', pool.deckSize, DECK_SIZE)}</p>}
      <ol class="opp-list">
        {pool.opponents.map((o, i) => (
          <li key={o.name} class="opp-row">
            <span class="n">{i + 1}</span>
            <span class="pips"><Pips colors={o.colors} /></span>
            <span class="opp-name">{o.name} <span class="muted">· {colourName(o.colors)}</span></span>
            <button class="opp-play" onClick={() => play('one', i)} aria-label={t('lblWebLimitedPlayOpponent', o.name)}>{t('lblPlay')}</button>
          </li>
        ))}
      </ol>
      <div class="opp-foot">
        <span class="say">{tNodes('lblWebLimitedGauntletSay', <b>{t('lblWebLimitedGauntlet')}</b>, pool.opponents.length)}</span>
        <button class="outline" onClick={() => play('gauntlet')}>{t('lblWebLimitedStartGauntlet')}</button>
      </div>
      {several && (
        <div class="opp-foot">
          <span class="say">{tNodes('lblWebLimitedFreeForAllSay', <b>{t('lblWebLimitedFreeForAll')}</b>)}</span>
          <span class="stepper">
            <button class="step" disabled={count <= 2} aria-label={t('lblWebLimitedOneOpponentFewer')} onClick={() => setCount(count - 1)}>&minus;</button>
            <span class="n">{count}</span>
            <button class="step" disabled={count >= cap} aria-label={t('lblWebLimitedOneOpponentMore')} onClick={() => setCount(count + 1)}>+</button>
          </span>
          <button class="outline" onClick={() => play('several')}>{t('lblWebLimitedPlayNOpponents', count)}</button>
        </div>
      )}
    </div>
  );
}
