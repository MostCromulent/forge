// The game menu behind the prompt's ⋯ button: what a player does to the game as a whole rather than on the board,
// and how priority passes by itself, which is changed often enough mid-game to sit one click from the prompt

import { useLayoutEffect, useRef, useState } from 'preact/hooks';
import { CloseIcon, OptionsDialog, Row } from './options';
import { SETTINGS, setting, type SettingDef } from './settings';
import { DevItems } from './devmenu';
import type { Actions } from './actions';
import type { AutoDecision } from './protocol';
import { deref, type Model } from './model';

export function GameMenu({ model, actions, close, open }: {
  model: Model; actions: Actions; close: () => void; open: (dialog: 'stops' | 'decisions' | 'keys' | 'devSetup') => void;
}) {
  const [armed, setArmed] = useState(false);
  const [dev, setDev] = useState(false);
  const menu = useRef<HTMLDivElement>(null);
  // Above the button that opened it, right edges aligned, since the prompt sits in the bottom-right corner
  useLayoutEffect(() => {
    const button = document.querySelector('#prompt .more')?.getBoundingClientRect();
    const el = menu.current;
    if (!button || !el) return;
    el.style.right = `${Math.max(8, innerWidth - button.right)}px`;
    el.style.bottom = `${innerHeight - button.top + 6}px`;
  }, []);
  const offer = model.drawOffer;
  return (
    <div class="backdrop anchored" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
      <div ref={menu} class={dev ? 'card-menu game-menu dev' : 'card-menu game-menu'} role="menu" aria-label={dev ? 'Dev mode' : 'Game'}>
        {dev ? <DevItems model={model} actions={actions} back={() => setDev(false)} close={close} setUp={() => open('devSetup')} /> : <>
        <button type="button" role="menuitem" class="card-menu-item" disabled={!!offer || model.spectating}
          onClick={() => { actions.drawOffer('OFFER'); close(); }}>
          {offer?.mine ? 'Draw offered, waiting for an answer' : 'Offer a draw'}
        </button>
        <button type="button" role="menuitem" class="card-menu-item" onClick={() => open('stops')}>Auto-pass stops…</button>
        <button type="button" role="menuitem" class="card-menu-item" disabled={model.spectating} onClick={() => {
          actions.autoDecisions('list');
          open('decisions');
        }}>Auto-yields and triggers…</button>
        <button type="button" role="menuitem" class="card-menu-item" onClick={() => open('keys')}>Keys…</button>
        {/* The host's alone: its seat shares a process with the server, and a guest's does not */}
        {setting('devMode') && model.host && !model.spectating && (
          <button type="button" role="menuitem" class="card-menu-item" onClick={() => setDev(true)}>Dev mode ›</button>
        )}
        <button type="button" role="menuitem" class={armed ? 'card-menu-item concede armed' : 'card-menu-item concede'}
          disabled={model.spectating} onClick={() => {
            if (!armed) {
              setArmed(true);
              return;
            }
            actions.concede();
            close();
          }}>
          {armed ? 'Click again to concede' : 'Concede'}
        </button>
        </>}
      </div>
    </div>
  );
}

/** Where auto-passing stops by itself, as the options dialog would list them. */
export function AutoPassStops({ close }: { close: () => void }) {
  return (
    <OptionsDialog title="Stop auto-passing when…" label="Auto-pass stops" kind="stops-dialog" close={close}
      footer={<span class="hint">Auto-passing gives you priority back at these moments. Changes apply at once.</span>}>
      {SETTINGS.filter(def => def.menu === 'stops').map(def => <Row key={def.key} def={def} />)}
    </OptionsDialog>
  );
}

const YIELD_MODE = SETTINGS.find(def => def.key === 'autoYieldMode') as SettingDef;

const KIND_LABEL: Record<AutoDecision['kind'], string> = { yield: 'Always yield', accept: 'Always accept', decline: 'Always decline' };

/** The auto-yields and trigger answers set during play, each of which can be forgotten, as desktop's dialog lists them. */
export function AutoDecisionsDialog({ model, actions, close }: { model: Model; actions: Actions; close: () => void }) {
  const [armed, setArmed] = useState(false);
  const all = model.autoDecisions;
  const entries = all?.entries ?? [];
  return (
    <OptionsDialog title="Auto-yields and triggers" kind="decisions-dialog" close={close} footer={<>
      <span class="hint">Paused ones are kept, and work again once switched back.</span>
      <button class={armed ? 'clear-all armed' : 'clear-all'} disabled={!entries.length} onClick={() => {
        if (!armed) {
          setArmed(true);
          return;
        }
        setArmed(false);
        actions.autoDecisions('clear');
      }}>{armed ? 'Click again to forget all' : 'Forget all'}</button>
    </>}>
      {/* Each mode keeps its own list, so the list is read again after a change */}
      <Row def={YIELD_MODE} onChange={() => actions.autoDecisions('list')} />
      {!all ? <p class="hint">Reading them…</p>
        : !entries.length ? <p class="hint">None set. Right-click an item on the stack to always yield to it, or answer a trigger with Always.</p>
          : entries.map(e => (
            <div key={`${e.kind} ${e.key}`} class="decision">
              <span class={`decision-kind ${e.kind}`}>{KIND_LABEL[e.kind]}</span>
              <span class="decision-key" title={e.key}>{e.key}</span>
              <button class="decision-forget" title="Forget this" aria-label={`Forget ${e.key}`}
                onClick={() => actions.autoDecisions('remove', e.key)}>
                <CloseIcon />
              </button>
            </div>
          ))}
      {all && (
        <>
          <div class="setting">
            <div>Pause every auto-yield</div>
            <button class={all.yieldsOff ? 'switch on' : 'switch'} role="switch" aria-checked={all.yieldsOff}
              onClick={() => actions.autoDecisions('disableYields', undefined, !all.yieldsOff)} />
          </div>
          <div class="setting">
            <div>Pause every trigger answer</div>
            <button class={all.triggersOff ? 'switch on' : 'switch'} role="switch" aria-checked={all.triggersOff}
              onClick={() => actions.autoDecisions('disableTriggers', undefined, !all.triggersOff)} />
          </div>
        </>
      )}
    </OptionsDialog>
  );
}

/** Another player's offer of a draw, which the game waits on this player to answer. */
export function DrawOfferQuestion({ model, actions }: { model: Model; actions: Actions }) {
  const offer = model.drawOffer;
  if (!offer?.waitingOnMe) return null;
  const who = deref(model, offer.offerer)?.Name;
  return (
    <div class="backdrop">
      <div class="dialog" role="dialog" aria-label="Draw offer">
        <h3>{`${who ?? 'An opponent'} offers a draw`}</h3>
        <p class="hint">The game ends in a draw only if every player accepts.</p>
        <div class="actions">
          <button onClick={() => actions.drawOffer('DECLINE')}>Decline</button>
          <button class="primary" onClick={() => actions.drawOffer('ACCEPT')}>Accept</button>
        </div>
      </div>
    </div>
  );
}
