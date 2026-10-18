// The bar across the top of every page outside a match. Each page fills the middle and puts its exit button at the end

import type { ComponentChildren } from 'preact';
import { useEffect } from 'preact/hooks';
import { changeUi, redrawUi, ui } from './ui';
import { t } from './text';
import { isPortrait } from './form';
import { Dropdown } from './dialog';
import { ICONS, Icon } from './icons';

/** The Forge wordmark in Magic's five colours. This is the only place the colours are pure decoration, with no card art nearby and no meaning in the hue. */
export function Wordmark() {
  return (
    <div class="wordmark-block">
      {/* Five letters for Magic's five colours. Each letter has its own coloured dash under it */}
      <span class="wordmark" aria-label="Forge">
        {[...'Forge'].map(letter => <i key={letter} aria-hidden="true">{letter}</i>)}
        <span class="beta">{t('lblWebBeta')}</span>
      </span>
    </div>
  );
}

/** brand pages show the phone header on a phone, with the wordmark as its title and action as its one action. Other pages keep this header on a phone. */
export function PageHeader({ class: extra, brand, action, children }: { class?: string; brand?: boolean; action?: ComponentChildren; children?: ComponentChildren }) {
  if (brand && isPortrait()) return <PhoneHead title={<Wordmark />} action={action} />;
  return <header class={extra ? `page-head ${extra}` : 'page-head'}><Wordmark />{children}</header>;
}

/**
 * The header of a page on a phone: back to the previous step, the title and its line, one action of the page's own, the chat and a menu.
 * tabs, when given, are a second row. more adds the page's own rows to the menu, and dev adds dev mode tools at its end.
 */
export function PhoneHead({ back, title, line, action, more, dev, tabs, inert }: {
  back?: SetupStep; title: ComponentChildren; line?: ComponentChildren; action?: ComponentChildren;
  more?: (close: () => void) => ComponentChildren; dev?: ComponentChildren; tabs?: ComponentChildren; inert?: boolean;
}) {
  // The dock draws its folded bar into the chat slot (dock.tsx), so it must draw again once the slot exists
  useEffect(redrawUi, []);
  return (
    <header class="ph" inert={inert}>
      <div class="ph-row">
        {back && <button class="ph-icon ph-back" title={back.label} aria-label={back.label} onClick={back.go}><Icon d={ICONS.back} /></button>}
        <div class="ph-title">
          {typeof title === 'string' ? <h2>{title}</h2> : title}
          {line && <span class="ph-line">{line}</span>}
        </div>
        {action}
        <span class="ph-chat" />
        <Dropdown cls="ph-more" button={(open, toggle) => (
          <button class="ph-icon" title={t('lblWebHeadMore')} aria-label={t('lblWebHeadMore')} aria-expanded={open} onClick={toggle}><Icon d={ICONS.ellipsis} /></button>
        )}>
          {close => (
            <div class="ph-menu" role="menu">
              <button role="menuitem" onClick={() => { close(); changeUi(u => { u.volumeOpen = true; }); }}><Icon d={ICONS.volume} />{t('lblWebHeadVolume')}</button>
              {more?.(close)}
              <button role="menuitem" onClick={() => { close(); changeUi(u => { u.optionsOpen = true; }); }}><Icon d={ICONS.cog} />{t('lblWebHeadOptions')}</button>
              {dev}
            </div>
          )}
        </Dropdown>
      </div>
      {tabs}
    </header>
  );
}

/** The step a phone header's back button returns to: the last earlier step of the trail, if any. */
export const backStep = (trail: SetupStep[]): SetupStep | undefined => trail.findLast(step => step.go);

/** A step of game setup in the trail. go returns to that step, and is left out for the current step. */
export interface SetupStep {
  label: string;
  go?: () => void;
}

/**
 * The trail through game setup, above the page's heading. Each earlier step in the trail is a button that goes back to it.
 * On a phone a trail with an earlier step is drawn by the page's PhoneHead instead, so only the aside and the line under the heading are left here.
 */
export function SetupHead({ trail, title, aside, sub }: { trail: SetupStep[]; title: string; aside?: ComponentChildren; sub?: ComponentChildren }) {
  if (isPortrait() && backStep(trail)) {
    return aside || sub ? <div class="setup-head">{aside && <div class="setup-aside">{aside}</div>}{sub && <p class="setup-sub">{sub}</p>}</div> : null;
  }
  return (
    <div class="setup-head">
      <nav class="crumb" aria-label={t('lblGameSetup')}>
        {trail.map((step, i) => (
          <>
            {i > 0 && <span aria-hidden="true">›</span>}
            {step.go ? <button class="link" onClick={step.go}>{step.label}</button> : <span aria-current={i === trail.length - 1 ? 'step' : undefined}>{step.label}</span>}
          </>
        ))}
      </nav>
      {/* The aside is shown next to the heading. It holds your deck before a match, or the exit button of a form */}
      <div class="setup-title">
        <h2>{title}</h2>
        {aside && <div class="setup-aside">{aside}</div>}
      </div>
      {sub && <p class="setup-sub">{sub}</p>}
    </div>
  );
}

/** The name of each way to play, as shown in the trail. */
export const WAY_NAMES: Record<'play' | 'friends', string> = {
  get play() { return t('lblWebHeadVersusAi'); },
  get friends() { return t('lblWebHeadWithFriends'); },
};

/** The volume and options buttons. Each page places them just before its exit button. */
export function HeadControls() {
  return (
    <>
      <button class="icon-button volume" title={t('lblWebHeadVolume')} aria-label={t('lblWebHeadVolume')} aria-expanded={ui.volumeOpen}
        onClick={() => changeUi(u => { u.volumeOpen = !u.volumeOpen; })}>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z" />
          <path d="M16 9a5 5 0 0 1 0 6" /><path d="M19.364 18.364a9 9 0 0 0 0-12.728" /></svg>
      </button>
      <button class="icon-button" title={t('lblWebHeadOptions')} aria-label={t('lblWebHeadOptions')} onClick={() => changeUi(u => { u.optionsOpen = true; })}>⚙</button>
    </>
  );
}
