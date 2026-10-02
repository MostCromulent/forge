// The game menu behind the prompt's ⋯ button, which keeps how priority passes by itself one click from the prompt

import { useLayoutEffect, useRef, useState } from 'preact/hooks';
import { CloseIcon, OnOff, OptionsDialog, Row } from './options';
import { PLAYMATS, SETTINGS, set, setting, type SettingDef } from './settings';
import { DevItems } from './devmenu';
import type { Actions } from './actions';
import type { AutoDecision } from './protocol';
import { deref, type Model } from './model';
import { t, type TextKey } from './text';

// Icons from Lucide (ISC, see web/licenses/lucide-license.txt)
const ICONS = {
  playmat: '<rect width="18" height="18" x="3" y="3" rx="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.09-3.09a2 2 0 0 0-2.82 0L6 21"/>',
  interrupts: '<circle cx="12" cy="12" r="10"/><path d="M10 15V9M14 15V9"/>',
  yields: '<path d="m6 17 5-5-5-5"/><path d="m13 17 5-5-5-5"/>',
  draw: '<path d="m11 17 2 2a1 1 0 1 0 3-3"/><path d="m14 14 2.5 2.5a1 1 0 1 0 3-3l-3.88-3.88a3 3 0 0 0-4.24 0l-.88.88a1 1 0 1 1-3-3l2.81-2.81a5.79 5.79 0 0 1 7.06-.87l.47.28a2 2 0 0 0 1.42.25L21 4"/><path d="m21 3 1 11h-2"/><path d="M3 3 2 14l6.5 6.5a1 1 0 1 0 3-3"/><path d="M3 4h8"/>',
  concede: '<path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z"/><path d="M4 22v-7"/>',
  dev: '<path d="m16 18 6-6-6-6"/><path d="m8 6-6 6 6 6"/>',
  more: '<path d="m9 18 6-6-6-6"/>',
};

function Icon({ d, end }: { d: string; end?: boolean }) {
  return <svg class={end ? 'menu-icon end' : 'menu-icon'} viewBox="0 0 24 24" aria-hidden="true" dangerouslySetInnerHTML={{ __html: d }} />;
}

export function GameMenu({ model, actions, close, open }: {
  model: Model; actions: Actions; close: () => void; open: (dialog: 'stops' | 'decisions' | 'devSetup') => void;
}) {
  const [armed, setArmed] = useState(false);
  const [dev, setDev] = useState(false);
  const [mats, setMats] = useState(false);
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
        <button type="button" role="menuitem" class={mats ? 'card-menu-item on' : 'card-menu-item'} aria-expanded={mats}
          onClick={() => setMats(!mats)}><Icon d={ICONS.playmat} />{t('lblWebPlaymat')}<Icon d={ICONS.more} end /></button>
        <div class="card-menu-sep" role="separator" />
        <button type="button" role="menuitem" class="card-menu-item" onClick={() => open('stops')}>
          <Icon d={ICONS.interrupts} />{t('lblWebGameMenuInterrupts')}
        </button>
        <button type="button" role="menuitem" class="card-menu-item" disabled={model.spectating} onClick={() => {
          actions.autoDecisions('list');
          open('decisions');
        }}><Icon d={ICONS.yields} />{t('lblWebGameMenuDecisions')}</button>
        <div class="card-menu-sep" role="separator" />
        <button type="button" role="menuitem" class="card-menu-item" disabled={!!offer || model.spectating}
          onClick={() => { actions.drawOffer('OFFER'); close(); }}>
          <Icon d={ICONS.draw} />{offer?.mine ? t('lblWebGameMenuDrawOffered') : t('lblWebGameMenuOfferDraw')}
        </button>
        <button type="button" role="menuitem" class={armed ? 'card-menu-item concede armed' : 'card-menu-item concede'}
          disabled={model.spectating} onClick={() => {
            if (!armed) {
              setArmed(true);
              return;
            }
            actions.concede();
            close();
          }}>
          {!armed && <Icon d={ICONS.concede} />}{armed ? t('lblWebGameMenuConcedeAgain') : t('lblConcede')}
        </button>
        {/* The host's alone: its seat shares a process with the server, and a guest's does not */}
        {setting('devMode') && model.host && !model.spectating && <>
          <div class="card-menu-sep" role="separator" />
          <button type="button" role="menuitem" class="card-menu-item dev-entry" onClick={() => setDev(true)}>
            <Icon d={ICONS.dev} />{t('lblWebDevMode')}<Icon d={ICONS.more} end />
          </button>
        </>}
        </>}
      </div>
      {mats && !dev && <PlaymatPicker beside={menu} />}
    </div>
  );
}

const BRIGHTNESS: [string, TextKey][] = [['dark', 'lblWebPlaymatDark'], ['dim', 'lblWebPlaymatDim'], ['light', 'lblWebPlaymatLight'],
  ['bright', 'lblWebPlaymatBright']];

/** The playmats beside the game menu. A choice changes the table at once, so the board behind it is the preview. */
function PlaymatPicker({ beside }: { beside: { current: HTMLDivElement | null } }) {
  const panel = useRef<HTMLDivElement>(null);
  // Placed from the button, as the menu is, since this runs before the menu has placed itself
  useLayoutEffect(() => {
    const button = document.querySelector('#prompt .more')?.getBoundingClientRect();
    const el = panel.current;
    if (!button || !el || !beside.current) return;
    el.style.right = `${Math.max(8, innerWidth - button.right) + beside.current.offsetWidth + 8}px`;
    el.style.bottom = `${innerHeight - button.top + 6}px`;
  }, []);
  const chosen = setting('playmat');
  const brightness = setting('playmatBrightness');
  return (
    <div ref={panel} class="playmat-picker" role="dialog" aria-label={t('lblWebPlaymat')}>
      <h3>{t('lblWebPlaymat')}</h3>
      <div class="mats">
        {PLAYMATS.map(m => (
          <button key={m.id} type="button" class={m.id === chosen ? 'mat on' : 'mat'} aria-pressed={m.id === chosen}
            onClick={() => set('playmat', m.id)}>
            {m.thumb ? <img alt="" src={m.thumb} /> : <span class="plain" />}
            <span class="nm">{t(m.name)}</span>
          </button>
        ))}
      </div>
      <div class="brightness">
        <span>{t('lblWebPlaymatBrightness')}</span>
        <span class="seg" role="group" aria-label={t('lblWebPlaymatBrightness')}>
          {BRIGHTNESS.map(([id, name]) => (
            <button key={id} type="button" aria-pressed={id === brightness} disabled={chosen === 'table'}
              onClick={() => set('playmatBrightness', id)}>{t(name)}</button>
          ))}
        </span>
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
            <OnOff on={all.yieldsOff} label={t('lblWebGameMenuPauseYields')} change={on => actions.autoDecisions('disableYields', undefined, on)} />
          </div>
          <div class="setting">
            <div>{t('lblWebGameMenuPauseTriggers')}</div>
            <OnOff on={all.triggersOff} label={t('lblWebGameMenuPauseTriggers')} change={on => actions.autoDecisions('disableTriggers', undefined, on)} />
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
