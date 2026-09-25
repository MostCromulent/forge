// A table's draft or sealed event, as desktop's lobby runs one: the host switches the table to Limited, sets the
// event up one question at a time, and deals the packs once every seat is ready. Everyone else reads it as it goes.

import { useState } from 'preact/hooks';
import { StepForm, draftCombo, draftSteps, rulesLine, sealedSentence, sealedSteps, type DraftValue, type SealedValue } from './setup';
import { changeUi, ui } from './ui';
import type { Actions } from './actions';
import type { Model } from './model';
import type { LimitedTable, LobbyTable } from './protocol';

const KINDS: [string, 'sealed' | 'draft' | null][] = [['Constructed', null], ['Draft', 'draft'], ['Sealed', 'sealed']];

/**
 * Constructed · Draft · Sealed, at a table others can join; a table against the computer drafts from the start page.
 * A new kind of event waits until the one begun is over; Constructed waits out a draft.
 */
export function LimitedSwitch({ lobby, actions }: { lobby: LobbyTable; actions: Actions }) {
  const lim = lobby.limited;
  if (!lobby.shareable && !lim) return null;
  const drafting = lim?.phase === 'DRAFTING' && !lim.activeEventId;
  return (
    <div class="formats limited-switch">
      <span class="row-label">Play</span>
      {KINDS.map(([name, kind]) => {
        const pressed = (lim?.kind ?? null) === kind;
        const locked = kind === null ? drafting : !!lim?.started;
        return (
          <button key={name} class="event-kind" aria-pressed={pressed} disabled={!lobby.host || pressed || locked}
            onClick={() => actions.setLimited(kind)}>{name}</button>
        );
      })}
    </div>
  );
}

/** The event beside the seats: its setup form for the host until it is set up, then what it is and how to begin it. */
export function EventPanel({ model, lobby, actions }: { model: Model; lobby: LobbyTable; actions: Actions }) {
  const lim = lobby.limited!;
  const [editing, setEditing] = useState(false);
  const draft = lim.kind === 'draft';
  if (lobby.host && !lim.started && (!lim.product || editing)) {
    return (
      <section class="event-panel">
        {lim.pastEvents.length > 0 && !editing && <PastEvents lim={lim} actions={actions} />}
        {!model.limitedOptions ? <p class="muted">Reading what can be opened…</p>
          : draft ? <DraftForm model={model} lobby={lobby} actions={actions} done={() => setEditing(false)} />
          : <SealedForm model={model} actions={actions} done={() => setEditing(false)} />}
        {editing && <button class="link" onClick={() => setEditing(false)}>Keep the event as it was</button>}
      </section>
    );
  }
  return (
    <section class="event-panel summary">
      <h3>{draft ? 'Booster draft' : 'Sealed'}</h3>
      <p class="event-product">{lim.product ?? 'The host is setting the event up.'}</p>
      {draft && lim.product && (
        <p class="muted">{lim.podSize} seats · {pickName(lim.pickRule)} · {lim.timer ? `${lim.timer} s to pick` : 'no pick timer'}</p>
      )}
      {lim.activeEventId && <p class="muted">The pools are out. Choose your deck from them, then play.</p>}
      {!lim.activeEventId && lim.phase === 'DRAFTING' && <p class="muted">The draft is on.</p>}
      {model.drafting && ui.draftHidden && (
        <button class="primary" onClick={() => changeUi(u => { u.draftHidden = false; })}>Return to draft</button>
      )}
      {lobby.host && !lim.started && lim.product && (
        <div class="event-actions">
          <button onClick={() => setEditing(true)}>Edit event</button>
          <button class="primary" onClick={() => actions.eventStart()}>{draft ? 'Start draft' : 'Open packs'}</button>
        </div>
      )}
      {!lim.started && lim.product && <p class="hint">Everyone presses Ready on their seat before the {draft ? 'draft starts' : 'packs are opened'}.</p>}
    </section>
  );
}

function pickName(rule?: string): string {
  return rule === 'FIRST_PICK' ? 'two on the first pick' : rule === 'ALWAYS' ? 'two every pass' : 'one pick per pass';
}

function PastEvents({ lim, actions }: { lim: LimitedTable; actions: Actions }) {
  return (
    <div class="past-events">
      <h4>Play an earlier event again</h4>
      {lim.pastEvents.slice(0, 5).map(p => (
        <button key={p.id} class="share-row" onClick={() => actions.eventHostAgain(p.id)}>
          <span class="share-label">{p.label}</span><span class="share-copy">Host again</span>
        </button>
      ))}
    </div>
  );
}

function SealedForm({ model, actions, done }: { model: Model; actions: Actions; done: () => void }) {
  const options = model.limitedOptions!;
  const [value, setValue] = useState<SealedValue>({});
  // The pool is named after its event, as desktop names it, so there is no name to ask for
  const steps = sealedSteps(options).filter(s => s.id !== 'name');
  return (
    <StepForm title="Set up the sealed event" steps={steps} value={value} onChange={setValue}
      sentence={v => sealedSentence(options, v)} action="Save event" submit={() => {
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
      sentence={rulesLine} action="Save event" submit={() => {
        actions.eventSetup({ product: value.product!, block: value.block, combo: draftCombo(value), cube: value.cube,
          theme: value.theme, cubeId: value.cubeId, packs: 3, podSize: value.podSize ?? 0, pickRule: value.pickRule,
          timer: value.timer ?? 0, grace: value.grace ?? 0 });
        done();
      }} />
  );
}
