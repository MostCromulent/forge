// The game menu behind the prompt's ⋯ button: what a player does to the game as a whole rather than on the board,
// and how priority passes by itself, which is changed often enough mid-game to sit one click from the prompt

import { useLayoutEffect, useRef, useState } from 'preact/hooks';
import { CloseIcon, OnOff, OptionsDialog, Row } from './options';
import { SETTINGS, setting, type SettingDef } from './settings';
import { DevItems } from './devmenu';
import type { Actions } from './actions';
import type { AutoDecision } from './protocol';
import { deref, type Model } from './model';
import { t, type TextKey } from './text';

export function GameMenu({ model, actions, close, open }: {
  model: Model; actions: Actions; close: () => void; open: (dialog: 'stops' | 'decisions' | 'devSetup') => void;
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
      <div ref={menu} class={dev ? 'card-menu game-menu dev' : 'card-menu game-menu'} role="menu" aria-label={dev ? t('lblWebDevMode') : t('lblGame')}>
        {dev ? <DevItems model={model} actions={actions} back={() => setDev(false)} close={close} setUp={() => open('devSetup')} /> : <>
        <button type="button" role="menuitem" class="card-menu-item" disabled={!!offer || model.spectating}
          onClick={() => { actions.drawOffer('OFFER'); close(); }}>
          {offer?.mine ? t('lblWebGameMenuDrawOffered') : t('lblWebGameMenuOfferDraw')}
        </button>
        <button type="button" role="menuitem" class="card-menu-item" onClick={() => open('stops')}>{t('lblWebGameMenuInterrupts')}</button>
        <button type="button" role="menuitem" class="card-menu-item" disabled={model.spectating} onClick={() => {
          actions.autoDecisions('list');
          open('decisions');
        }}>{t('lblWebGameMenuDecisions')}</button>
        {/* The host's alone: its seat shares a process with the server, and a guest's does not */}
        {setting('devMode') && model.host && !model.spectating && (
          <button type="button" role="menuitem" class="card-menu-item" onClick={() => setDev(true)}>{t('lblWebDevMode')} ›</button>
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
          {armed ? t('lblWebGameMenuConcedeAgain') : t('lblConcede')}
        </button>
        </>}
      </div>
    </div>
  );
}

/** Where auto-passing stops by itself, as the options dialog would list them. */
export function AutoPassStops({ close }: { close: () => void }) {
  return (
    <OptionsDialog title={t('lblWebGameMenuStopsTitle')} label={t('lblWebGameMenuStopsLabel')} kind="stops-dialog" close={close}
      footer={<span class="hint">{t('lblWebGameMenuStopsHint')}</span>}>
      {SETTINGS.filter(def => def.menu === 'stops').map(def => <Row key={def.key} def={def} />)}
    </OptionsDialog>
  );
}

const YIELD_MODE = SETTINGS.find(def => def.key === 'autoYieldMode') as SettingDef;

const KIND_LABEL: Record<AutoDecision['kind'], TextKey> = {
  yield: 'lblWebGameMenuAlwaysYield', accept: 'lblWebGameMenuAlwaysAccept', decline: 'lblWebGameMenuAlwaysDecline',
};

/** The auto-yields and trigger answers set during play, each of which can be forgotten, as desktop's dialog lists them. */
export function AutoDecisionsDialog({ model, actions, close }: { model: Model; actions: Actions; close: () => void }) {
  const [armed, setArmed] = useState(false);
  const all = model.autoDecisions;
  const entries = all?.entries ?? [];
  return (
    <OptionsDialog title={t('lblWebGameMenuDecisionsTitle')} kind="decisions-dialog" close={close} footer={<>
      <span class="hint">{t('lblWebGameMenuDecisionsHint')}</span>
      <button class={armed ? 'clear-all armed' : 'clear-all'} disabled={!entries.length} onClick={() => {
        if (!armed) {
          setArmed(true);
          return;
        }
        setArmed(false);
        actions.autoDecisions('clear');
      }}>{armed ? t('lblWebGameMenuForgetAllAgain') : t('lblWebGameMenuForgetAll')}</button>
    </>}>
      {/* Each mode keeps its own list, so the list is read again after a change */}
      <Row def={YIELD_MODE} onChange={() => actions.autoDecisions('list')} />
      {!all ? <p class="hint">{t('lblWebGameMenuReading')}</p>
        : !entries.length ? <p class="hint">{t('lblWebGameMenuNoneSet')}</p>
          : entries.map(e => (
            <div key={`${e.kind} ${e.key}`} class="decision">
              <span class={`decision-kind ${e.kind}`}>{t(KIND_LABEL[e.kind])}</span>
              <span class="decision-key" title={e.key}>{e.key}</span>
              <button class="decision-forget" title={t('lblWebGameMenuForgetThis')} aria-label={t('lblWebGameMenuForgetX', e.key)}
                onClick={() => actions.autoDecisions('remove', e.key)}>
                <CloseIcon />
              </button>
            </div>
          ))}
      {all && (
        <>
          <div class="setting">
            <div>{t('lblWebGameMenuPauseYields')}</div>
            <OnOff on={all.yieldsOff} change={on => actions.autoDecisions('disableYields', undefined, on)} />
          </div>
          <div class="setting">
            <div>{t('lblWebGameMenuPauseTriggers')}</div>
            <OnOff on={all.triggersOff} change={on => actions.autoDecisions('disableTriggers', undefined, on)} />
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
      <div class="dialog" role="dialog" aria-label={t('lblWebGameMenuDrawOffer')}>
        <h3>{who != null ? t('lblWebGameMenuOffersDraw', who) : t('lblWebGameMenuOpponentOffersDraw')}</h3>
        <p class="hint">{t('lblWebGameMenuDrawOnlyIfAll')}</p>
        <div class="actions">
          <button onClick={() => actions.drawOffer('DECLINE')}>{t('lblDecline')}</button>
          <button class="primary" onClick={() => actions.drawOffer('ACCEPT')}>{t('lblAccept')}</button>
        </div>
      </div>
    </div>
  );
}
