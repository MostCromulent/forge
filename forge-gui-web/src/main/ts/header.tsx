// The bar across the top of every page outside a match, so the wordmark, the sound and the options sit in one place
// whichever page is open. Each page fills the middle and its own buttons, and ends with its way out.

import type { ComponentChildren } from 'preact';
import { changeUi, ui } from './ui';
import { t } from './text';

/** Magic's five colours, the one place they are pure decoration: no card art nearby and nothing encoded by hue. */
export function Wordmark() {
  return (
    <div class="wordmark-block">
      <span class="wordmark">Forge</span>
      <span class="stripes" aria-hidden="true"><i /><i /><i /><i /><i /></span>
    </div>
  );
}

export function PageHeader({ class: extra, children }: { class?: string; children?: ComponentChildren }) {
  return <header class={extra ? `page-head ${extra}` : 'page-head'}><Wordmark />{children}</header>;
}

/** A step of game setup in the trail: a way back to it, or none for the step you are on. */
export interface SetupStep {
  label: string;
  go?: () => void;
}

/**
 * The trail through game setup and the question the page asks, in the same place on every setup page: the start page,
 * the kinds of game, and the table. Each earlier step in the trail goes back to it.
 */
export function SetupHead({ trail, title, aside, sub }: { trail: SetupStep[]; title: string; aside?: ComponentChildren; sub?: ComponentChildren }) {
  return (
    <div class="setup-head">
      <nav class="crumb" aria-label={t('lblWebHeadGameSetup')}>
        {trail.map((step, i) => (
          <>
            {i > 0 && <span aria-hidden="true">›</span>}
            {step.go ? <button class="link" onClick={step.go}>{step.label}</button> : <span aria-current={i === trail.length - 1 ? 'step' : undefined}>{step.label}</span>}
          </>
        ))}
      </nav>
      {/* What the page is about sits by the heading: your deck before a match, or the way back out of a form */}
      <div class="setup-title">
        <h2>{title}</h2>
        {aside && <div class="setup-aside">{aside}</div>}
      </div>
      {sub && <p class="setup-sub">{sub}</p>}
    </div>
  );
}

/** What each way to play is called in the trail. */
export const WAY_NAMES: Record<'play' | 'friends', string> = {
  get play() { return t('lblWebHeadVersusAi'); },
  get friends() { return t('lblWebHeadWithFriends'); },
};

/** The volume and options buttons, placed by each page just before its way out. */
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
