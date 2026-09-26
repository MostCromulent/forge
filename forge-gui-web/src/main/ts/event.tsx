// A table's draft or sealed event, as desktop's lobby runs one: the host switches the table to Limited, sets the
// event up one question at a time, and deals the packs once every seat is ready. Everyone else reads it as it goes.

import { useState } from 'preact/hooks';
import { StepForm, draftCombo, draftSentence, draftSteps, pickRuleName, sealedSentence, sealedSteps, type DraftValue, type SealedValue } from './setup';
import { changeUi, ui } from './ui';
import type { Actions } from './actions';
import type { Model } from './model';
import type { LimitedTable, LobbyTable } from './protocol';

/**
 * The event's row of the match bar: what it is, where it has got to, and the next step. The host sets it up in a
 * dialog over the table, which opens by itself on a table with no event yet.
 */
export function EventPanel({ model, lobby, actions }: { model: Model; lobby: LobbyTable; actions: Actions }) {
  const lim = lobby.limited!;
  const [setting, setSetting] = useState(!lim.product);
  const draft = lim.kind === 'draft';
  const unready = lobby.seats.filter(s => s.type !== 'OPEN' && !s.ready);
  const close = () => setSetting(false);
  const stages = ['Ready up', draft ? 'Draft' : 'Open packs', 'Build', 'Play'];
  const at = eventStage(lobby);
  return (
    <div class="event-row">
      {lobby.host && !lim.started && setting && (
        <div class="backdrop" onClick={e => { if (e.target === e.currentTarget) close(); }}>
          <div class="dialog event-setup" role="dialog" aria-label={draft ? 'Set up the draft' : 'Set up the sealed event'}>
            <button class="dk-close" title="Close" onClick={close}>&times;</button>
            {lim.pastEvents.length > 0 && !lim.product && <PastEvents lim={lim} actions={actions} />}
            {!model.limitedOptions ? <p class="muted">Loading…</p>
              : draft ? <DraftForm model={model} lobby={lobby} actions={actions} done={close} />
              : <SealedForm model={model} actions={actions} done={close} />}
          </div>
        </div>
      )}
      <span class="event-product">{lim.product ?? 'Not set up yet'}</span>
      {draft && lim.product && <>
        <span>{lim.podSize} seats</span>
        <span>{pickRuleName(lim.pickRule)}</span>
        <span>{lim.timer ? `${lim.timer} s to pick` : 'No pick timer'}</span>
      </>}
      {lobby.host && !lim.started && <button class="link" onClick={() => setSetting(true)}>{lim.product ? 'Edit' : 'Set up'}</button>}
      <span class="sp" />
      <ol class="event-trail" aria-label="Where the event is">
        {stages.map((name, i) => <li key={name} class={i < at ? 'done' : i === at ? 'now' : ''}>{name}</li>)}
      </ol>
      {lobby.host && !lim.started && lim.product && (
        <button class="primary" disabled={unready.length > 0} onClick={() => actions.eventStart()}>{draft ? 'Start draft' : 'Open packs'}</button>
      )}
      {model.drafting && ui.draftHidden && (
        <button class="primary" onClick={() => changeUi(u => { u.draftHidden = false; })}>Return to draft</button>
      )}
    </div>
  );
}

/** Where the event has got to: ready up, the draft or the opening, building, then playing once your deck is on your seat. */
function eventStage(lobby: LobbyTable): number {
  const lim = lobby.limited!;
  if (!lim.started) return 0;
  if (!lim.activeEventId) return 1;
  const mine = lobby.seats[lobby.mySeat];
  return mine && (mine.deck != null || mine.deckName != null) ? 3 : 2;
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

function PastEvents({ lim, actions }: { lim: LimitedTable; actions: Actions }) {
  return (
    <div class="past-events">
      <h4>Earlier events</h4>
      {lim.pastEvents.slice(0, 5).map(p => (
        <button key={p.id} class="share-row" onClick={() => actions.eventHostAgain(p.id)}>
          <span class="share-label">{p.label}</span><span class="share-copy">Play again</span>
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
