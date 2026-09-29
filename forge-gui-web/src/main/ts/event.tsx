// A table's draft or sealed event, as desktop's lobby runs one: the host switches the table to Limited, sets the
// event up one question at a time, and deals the packs once every seat is ready. Everyone else reads it as it goes.

import { useState } from 'preact/hooks';
import { StepForm, draftCombo, draftSentence, draftSteps, pickRuleName, sealedSentence, sealedSteps, type DraftValue, type SealedValue } from './setup';
import { changeUi, ui } from './ui';
import type { Actions } from './actions';
import type { Model } from './model';
import type { LobbyTable, PastEvent } from './protocol';
import { GameMenu, PlayerCount } from './matchbar';
import { shortDay } from './limited';

/** What the setup dialog shows: the choice between a new event and an earlier one, or either of those. */
type SetupView = 'choose' | 'new' | 'earlier';

/**
 * The event as the head of the table: its mode, product and rules with the players who will play it, then the stages
 * along a rail whose button is always the next thing to do. Once the packs are out the rules fold to one line, since
 * nobody needs them then. The host sets the event up in a dialog over the table, which opens by itself on a table with
 * no event yet.
 */
export function EventHead({ model, lobby, actions, preview, start }: {
  model: Model; lobby: LobbyTable; actions: Actions; preview: (count: number | null) => void; start: () => void;
}) {
  const lim = lobby.limited!;
  const [setting, setSetting] = useState(!lim.product);
  // With earlier events kept, the host first says whether to set up a new one or play one of those again
  const firstView = (): SetupView => (lim.pastEvents.length && !lim.product ? 'choose' : 'new');
  const [view, setView] = useState<SetupView>(firstView);
  const [leaving, setLeaving] = useState(false);
  const openSetup = () => { setView(firstView()); setSetting(true); };
  const draft = lim.kind === 'draft';
  const unready = lobby.seats.filter(s => s.type !== 'OPEN' && !s.ready);
  const close = () => setSetting(false);
  const stages = ['Ready up', draft ? 'Draft' : 'Open packs', 'Build', 'Play'];
  const at = eventStage(lobby);
  const players = lobby.seats.length;
  // Computers draft the seats the table's players do not fill; they draft but don't play
  const ai = draft ? Math.max(0, lim.podSize - players) : 0;
  const slim = lim.started;
  let button = null;
  let why = eventStatus(lobby);
  if (!lim.started) {
    if (lobby.host && lim.product) {
      button = <button class="primary big" disabled={unready.length > 0} onClick={() => actions.eventStart()}>{draft ? 'Start draft' : 'Open packs'}</button>;
    }
  } else if (model.drafting && ui.draftHidden) {
    button = <button class="primary big" onClick={() => changeUi(u => { u.draftHidden = false; })}>Return to draft</button>;
  } else if (lim.activeEventId) {
    const problem = lobby.problems?.[0];
    if (lobby.host) {
      button = <button id="play" class="primary big" disabled={!lobby.canStart} onClick={start} title="Enter starts the match">Play</button>;
      why = lobby.canStart ? 'Everyone has a deck.' : problem ?? why;
    } else {
      why = problem ?? 'Waiting for the host to start the match.';
    }
  }
  const facts = draft
    ? `${players} players and ${ai} AI · ${pickRuleName(lim.pickRule).toLowerCase()} · ${lim.timer ? `${lim.timer} s a pick` : 'no pick timer'}`
    : `${players} players · each opens their own pool`;
  return (
    <section class={slim ? 'event-head slim' : 'event-head'} aria-label={draft ? 'The draft' : 'The sealed event'}>
      {lobby.host && !lim.started && setting && (
        <div class="backdrop" onClick={e => { if (e.target === e.currentTarget) close(); }}>
          <div class="dialog event-setup" role="dialog" aria-label={draft ? 'Set up the draft' : 'Set up the sealed event'}>
            <button class="dk-close" title="Close" onClick={close}>&times;</button>
            {view === 'choose' && lim.pastEvents.length > 0
              ? <SetupChoice draft={draft} count={lim.pastEvents.length} choose={setView} />
              : view === 'earlier'
                ? <PastEvents events={lim.pastEvents} actions={actions} back={() => setView('choose')} />
                : <>
                    {lim.pastEvents.length > 0 && !lim.product && <button class="link setup-back" onClick={() => setView('choose')}>‹ Back</button>}
                    {!model.limitedOptions ? <p class="muted">Loading…</p>
                      : draft ? <DraftForm model={model} lobby={lobby} actions={actions} done={close} />
                      : <SealedForm model={model} actions={actions} done={close} />}
                  </>}
          </div>
        </div>
      )}
      {leaving && (
        <div class="backdrop" onClick={e => { if (e.target === e.currentTarget) setLeaving(false); }}>
          <div class="dialog" role="alertdialog" aria-label="Start a new event">
            <h3>Start a new event?</h3>
            <p class="hint">This one ends for everyone at the table. Its pools stay saved, so it can be played again from the earlier events.</p>
            <div class="actions">
              <button onClick={() => setLeaving(false)}>Keep this event</button>
              <button class="primary" onClick={() => { setLeaving(false); actions.eventNew(); setView('choose'); setSetting(true); }}>New event</button>
            </div>
          </div>
        </div>
      )}
      <div class="eh-main">
        <div class="eh-mode"><GameMenu lobby={lobby} actions={actions} /></div>
        <div class="eh-title">
          <span class="event-product">{lim.product ?? 'Not set up yet'}</span>
          {lobby.host && !lim.started && <button class="link" onClick={openSetup}>{lim.product ? 'Edit' : 'Set up'}</button>}
          {/* An event under way, or one played again, is left for a new one here; its pools stay among the earlier events */}
          {lobby.host && lim.started && !model.drafting && <button class="link" onClick={() => setLeaving(true)}>New event</button>}
          {slim && <span class="eh-facts">{facts}</span>}
        </div>
        {!slim && (
          <div class="eh-fields">
            <div class="eh-field"><span class="field-name">Players</span><PlayerCount lobby={lobby} actions={actions} preview={preview} /></div>
            {draft && lim.product && <>
              <div class="eh-field"><span class="field-name">AI drafters</span>
                <span>{ai ? <>{ai} <span class="muted">to make a pod of {lim.podSize}</span></> : 'None'}</span></div>
              <div class="eh-field"><span class="field-name">Picks</span><span>{pickRuleName(lim.pickRule)}</span></div>
              <div class="eh-field"><span class="field-name">Pick timer</span><span>{lim.timer ? `${lim.timer} s` : 'None'}</span></div>
            </>}
            {!draft && lim.product && <div class="eh-field"><span class="field-name">Pools</span><span>Each player opens their own</span></div>}
          </div>
        )}
        {!slim && <div class="eh-art" aria-hidden="true"><i class="pk" /><i class="pk" /><i class="pk" /></div>}
      </div>
      <div class="eh-rail">
        <ol class="eh-track" aria-label="Where the event is" style={{ '--at': at }}>
          {stages.map((name, i) => <li key={name} class={i < at ? 'done' : i === at ? 'now' : ''}><i />{name}</li>)}
        </ol>
        <div class="eh-go">
          {button}
          <span class="eh-why">{why}</span>
        </div>
      </div>
    </section>
  );
}

/** Where the event has got to: ready up, the draft or the opening, building, then playing once every seat that plays has a deck. */
export function eventStage(lobby: LobbyTable): number {
  const lim = lobby.limited!;
  if (!lim.started) return 0;
  if (!lim.activeEventId) return 1;
  const playing = lobby.seats.filter(s => s.type !== 'OPEN' && !s.benched);
  return playing.every(s => s.deck != null || s.deckName != null) ? 3 : 2;
}

/** The line under the bar at a Draft or Sealed table: who or what the table is waiting on. */
export function eventStatus(lobby: LobbyTable): string {
  const lim = lobby.limited!;
  if (!lim.product) return lobby.host ? 'Set the event up, then everyone presses Ready.' : 'The host is setting the event up.';
  if (!lim.started) {
    const unready = lobby.seats.filter(s => s.type !== 'OPEN' && !s.ready).map(s => (s.mine ? 'you' : s.name ?? 'a player'));
    if (unready.length) return `Waiting for ${unready.join(', ')} to press Ready.`;
    return lobby.host ? 'Everyone is ready.' : 'Everyone is ready. Waiting for the host.';
  }
  if (!lim.activeEventId) return lim.kind === 'draft' ? 'The draft is on.' : 'Opening the packs.';
  return 'Pools are out. Build your deck, then play.';
}

/** The first question when earlier events are kept: set up a new one, or play one of those again. */
function SetupChoice({ draft, count, choose }: { draft: boolean; count: number; choose: (view: SetupView) => void }) {
  return (
    <div class="setup-choice">
      <h3>{draft ? 'Draft' : 'Sealed'} at this table</h3>
      <div class="tiles">
        <button class="tile-choice" onClick={() => choose('new')}>
          <b>{draft ? 'New draft' : 'New sealed event'}</b><span>Choose the packs and the table's rules.</span>
        </button>
        <button class="tile-choice" onClick={() => choose('earlier')}>
          <b>An earlier event</b><span>Play one of your {count} saved {count === 1 ? 'event' : 'events'} again, with the pools it dealt.</span>
        </button>
      </div>
    </div>
  );
}

/** "26 Sept, 09:46" from the "yyyy-MM-dd HH:mm" an event is saved with. */
function playedOn(date: string): string {
  return date ? `${shortDay(date.slice(0, 10))}, ${date.slice(11, 16)}` : '';
}

/** The earlier events, newest first, each played again or deleted from here. Deleting asks first, in its row. */
function PastEvents({ events, actions, back }: { events: PastEvent[]; actions: Actions; back: () => void }) {
  const [deleting, setDeleting] = useState<string | null>(null);
  return (
    <div class="past-events">
      <button class="link setup-back" onClick={back}>‹ Back</button>
      <h3>Earlier events</h3>
      {events.length === 0 && <p class="muted">No earlier events are kept.</p>}
      <div class="past-list">
        {events.map(p => (
          <div key={p.id} class="past-row">
            <span class="past-name"><b>{p.product || (p.kind === 'draft' ? 'Draft' : 'Sealed')}</b>
              <span class="muted">{p.kind === 'draft' ? 'Draft' : 'Sealed'} · {playedOn(p.date)}</span></span>
            {deleting === p.id
              ? <>
                  <span class="past-ask">Delete its pools?</span>
                  <button onClick={() => setDeleting(null)}>Keep</button>
                  <button class="danger" onClick={() => { setDeleting(null); actions.eventForget(p.id); }}>Delete</button>
                </>
              : <>
                  <button class="outline" onClick={() => actions.eventHostAgain(p.id)}>Play again</button>
                  <button class="past-delete" title="Delete this event's pools" aria-label={`Delete ${p.product}`}
                    onClick={() => setDeleting(p.id)}>&times;</button>
                </>}
          </div>
        ))}
      </div>
    </div>
  );
}

function SealedForm({ model, actions, done }: { model: Model; actions: Actions; done: () => void }) {
  const options = model.limitedOptions!;
  const [value, setValue] = useState<SealedValue>({});
  // The pool is named after its event, as desktop names it, so there is no name to ask for
  const steps = sealedSteps(options).filter(s => s.id !== 'name');
  return (
    <StepForm title="Set up sealed" steps={steps} value={value} onChange={setValue}
      sentence={v => sealedSentence(options, v)} action="Save" submit={() => {
        actions.eventSetup({ product: value.product!, block: value.block, combo: value.combo, edition: value.edition,
          template: value.template, cubeId: value.cubeId, packs: value.packs ?? 0, podSize: 0, timer: 0, grace: 0 });
        done();
      }} />
  );
}

function DraftForm({ model, lobby, actions, done }: { model: Model; lobby: LobbyTable; actions: Actions; done: () => void }) {
  const options = model.limitedOptions!;
  const [value, setValue] = useState<DraftValue>({});
  const seated = lobby.seats.filter(s => s.type !== 'OPEN').length;
  return (
    <StepForm title="Set up the draft" steps={draftSteps(options, { seated })} value={value} onChange={setValue}
      sentence={v => draftSentence(v, true)} action="Save" submit={() => {
        actions.eventSetup({ product: value.product!, block: value.block, combo: draftCombo(value), cube: value.cube,
          theme: value.theme, cubeId: value.cubeId, packs: 3, podSize: value.podSize ?? 0, pickRule: value.pickRule,
          timer: value.timer ?? 0, grace: value.grace ?? 0 });
        done();
      }} />
  );
}
