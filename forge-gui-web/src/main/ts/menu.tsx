// The first screen: the name and face you play under, and whether you are the one opening the game. Those used
// to be two pages, but the host seat is only ever offered to a browser holding the host's link while nobody
// holds the seat, so the offer costs one checkbox and everyone else never sees it.
//
// Past that, a browser holding the seat gets the menu; every other one is told to wait for a table.

import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { LookPicker } from './lookpicker';
import { changeUi, ui } from './ui';
import { avatarUrl } from './looks';
import { HeadControls, PageHeader, SetupHead, WAY_NAMES, Wordmark } from './header';
import type { Actions } from './actions';
import type { Model } from './model';
import { store, stored } from './storage';
import { t } from './text';

/** Kept to the server's limit (WebSession.MAX_NAME_LENGTH), so the field stops where the server would refuse. */
const MAX_NAME_LENGTH = 24;

export function Menu({ model, actions }: { model: Model; actions: Actions }) {
  const choosing = ui.menuChoice;
  const setChoosing = (way: 'play' | 'friends' | null) => changeUi(u => { u.menuChoice = way; });
  // A new name arriving means the change went through
  useEffect(() => changeUi(u => { u.renaming = false; }), [model.playerName]);
  if (ui.renaming) {
    return <NamePrompt model={model} actions={actions} initial={model.playerName} cancel={() => changeUi(u => { u.renaming = false; })} />;
  }
  // A browser without the host's seat has no menu: it waits for somebody to open a table
  if (!model.host) {
    return <Waiting model={model} actions={actions} />;
  }
  const decks = model.decks?.length ?? 0;
  // Only the ones that open something are built; the rest are shown so the shape of the product is honest,
  // and greyed so nothing looks broken
  return (
    <div class="menu-shell">
      <PageHeader>
        <div class="head-right">
          <HeadControls />
          <button onClick={() => actions.quit()}>{t('lblWebMenuQuitForge')}</button>
        </div>
      </PageHeader>
      <div class="menu-page">
        {choosing && <Chooser who={choosing} model={model} actions={actions} back={() => setChoosing(null)} />}
        <div class="start-step" hidden={!!choosing}>
          <SetupHead trail={[{ label: t('lblWebHeadStart') }]} title={t('lblWebMenuHowToPlay')} />
          <div class="modes">
            <Mode id="play" name={t('lblWebMenuVersusAi')} blurb={t('lblWebMenuVersusAiBlurb')}
              status={[decks ? t('lblWebMenuDecksReady', decks) : t('lblWebMenuNoDecksYet'),
                model.sealedPools ? t(model.sealedPools === 1 ? 'lblWebMenuSealedPool' : 'lblWebMenuSealedPools', model.sealedPools) : ''].filter(Boolean).join(' · ')}
              onClick={() => setChoosing('play')} />
            <Mode id="multiplayer" name={t('lblWebMenuWithFriends')} blurb={t('lblWebMenuWithFriendsBlurb')}
              status={t('lblWebMenuGivesLink')} onClick={() => setChoosing('friends')} />
            <Mode id="editor" fan={DECK_FAN} name={t('lblDecks')} blurb={t('lblWebMenuDecksBlurb')} status={t('lblWebMenuDecksCount', decks)}
              onClick={() => {
                changeUi(u => { u.browse = { format: 'Constructed' }; });
                actions.browseFormat('Constructed');
              }} />
          </div>
        </div>
        <p class={model.error ? 'menu-note bad' : 'menu-note'}>{model.error ?? ''}</p>
      </div>
    </div>
  );
}

/** The kind of play, chosen before entering, the same way whoever the opponents are. Greyed ones are still to come. */
function Chooser({ who, model, actions, back }: { who: 'play' | 'friends'; model: Model; actions: Actions; back: () => void }) {
  const computer = who === 'play';
  return (
    <div class="chooser">
      <SetupHead trail={[{ label: t('lblWebHeadStart'), go: back }, { label: WAY_NAMES[who] }]} title={t('lblWebMenuWhatMode')} />
      <div class="chooser-kinds">
        <Kind id="constructed" name={t('lblConstructed')} onClick={() => actions.openLobby(!computer)}
          blurb={t(computer ? 'lblWebMenuConstructedVsAi' : 'lblWebMenuConstructedFriends')} />
        <Kind id="draft" name={t('lblDraft')} onClick={() => (computer ? actions.limitedOpen('draft', false) : actions.openLimitedTable('draft'))}
          blurb={t(computer ? 'lblWebMenuDraftVsAi' : 'lblWebMenuDraftFriends')}
          resume={computer && model.draftPools > 0 ? () => actions.limitedOpen('draft', true) : undefined} />
        <Kind id="sealed" name={t('lblSealed')} onClick={() => (computer ? actions.limitedOpen('sealed', false) : actions.openLimitedTable('sealed'))}
          blurb={t(computer ? 'lblWebMenuSealedVsAi' : 'lblWebMenuSealedFriends')}
          resume={computer && model.sealedPools > 0 ? () => actions.limitedOpen('sealed', true) : undefined} />
      </div>
      {computer && <>
        <h3 class="chooser-more">{t('lblWebConquestCampaigns')}</h3>
        <div class="chooser-kinds">
          <Kind id="conquest" name={t('lblPlanarConquest')} blurb={t('lblWebConquestBlurb')} onClick={() => actions.conquestOpen(false)}
            resume={model.currentConquest ? () => actions.conquestOpen(true) : undefined}
            resumeLabel={model.currentConquest ? t('lblWebConquestResume', model.currentConquest) : undefined} />
          <Kind id="quest" name={t('lblQuestMode')} blurb={t('lblWebConquestQuestSoon')} />
        </div>
      </>}
    </div>
  );
}

// A fanned deck for Constructed; two packs passing between players for Draft; an opened pack with its cards rising for Sealed
const KIND_ICONS: Record<string, ComponentChildren> = {
  constructed: <><rect x="16" y="11" width="21" height="29" rx="3" /><path d="M12 15v21M8 19v13" /></>,
  draft: <><rect x="7" y="16" width="14" height="22" rx="2" /><rect x="27" y="16" width="14" height="22" rx="2" /><path d="M17 9h13l-3-3M31 45H18l3 3" /></>,
  sealed: <><path d="M13 22v19h22V22" /><path d="M13 22l2.75-2.5 2.75 2.5 2.75-2.5 2.75 2.5 2.75-2.5 2.75 2.5 2.75-2.5 2.75 2.5" />
    <rect x="16" y="6" width="10" height="15" rx="1.5" transform="rotate(-12 21 13.5)" /><rect x="23" y="5" width="10" height="15" rx="1.5" transform="rotate(10 28 12.5)" /></>,
  // A ringed world for Conquest; a pennant on its staff for Quest
  conquest: <><circle cx="24" cy="24" r="11" /><ellipse cx="24" cy="24" rx="20" ry="6.5" transform="rotate(-18 24 24)" /></>,
  quest: <><path d="M13 42V7" /><path d="M13 9h22l-6 7 6 7H13" /></>,
};

/**
 * A kind of game, with a way back into the event of that kind saved last along its foot when there is one. The card
 * is two buttons in one frame, as a button cannot hold another. A kind with nothing to open is shown greyed.
 */
function Kind({ id, name, blurb, resume, resumeLabel, onClick }: {
  id: string; name: string; blurb: string; resume?: () => void; resumeLabel?: string; onClick?: () => void;
}) {
  return (
    <div class="mode kind-card">
      <button class="kind-main" data-kind={id} disabled={!onClick} onClick={onClick}>
        <span class="mode-art" aria-hidden="true"><svg viewBox="0 0 48 48">{KIND_ICONS[id]}</svg></span>
        <span class="mode-text">
          <span class="mode-name">{name}</span>
          <span class="mode-blurb">{blurb}</span>
        </span>
      </button>
      {resume && (
        <button class="kind-resume" onClick={resume}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 12a9 9 0 1 0 3-6.7" /><path d="M3 4v5h5" /><path d="M10 9l5 3-5 3z" /></svg>
          {resumeLabel ?? t('lblWebMenuResumeLastEvent')}
        </button>
      )}
    </div>
  );
}

// A robot for the computer, two people for friends, a fanned deck for the editor
const MODE_ICONS: Record<string, ComponentChildren> = {
  play: <><rect x="11" y="15" width="26" height="22" rx="4" /><path d="M24 15V9M19 25h2M27 25h2M19 31h10" /><circle cx="24" cy="7" r="2.4" /><path d="M6 22v8M42 22v8" /></>,
  multiplayer: <><circle cx="18" cy="17" r="6" /><path d="M8 38c0-5.5 4.5-10 10-10s10 4.5 10 10" /><circle cx="34" cy="20" r="4.6" /><path d="M28.5 34c1-4 4-6.6 8-6.6 3.2 0 5.5 1.6 5.5 1.6" /></>,
  editor: <><rect x="16" y="11" width="21" height="29" rx="3" /><path d="M12 15v21M8 19v13" /></>,
};

/** Blank cards in the five colours fanned on the Decks plaque, a deck's worth of variety at a glance. */
const DECK_FAN = ['W', 'U', 'B', 'R', 'G'];

/**
 * A way in from the start page: a brass-framed plaque with its icon on a brass medallion, over a ground drawn for it
 * (lobby.css) with the same icon large and faint. No card art: the page is Forge's, and card art is not ours to use.
 * Decks, given a fan of cards, runs the full width under the others.
 */
function Mode({ id, fan, name, blurb, status, onClick }: {
  id: string; fan?: string[]; name: string; blurb: string; status: string; onClick?: () => void;
}) {
  return (
    <button class={fan ? 'mode plaque wide' : 'mode plaque'} data-mode={id} disabled={!onClick} onClick={onClick}>
      <span class="plaque-rim" aria-hidden="true" />
      <span class="plaque-mark" aria-hidden="true"><svg viewBox="0 0 48 48">{MODE_ICONS[id]}</svg></span>
      <span class="mode-art" aria-hidden="true"><svg viewBox="0 0 48 48">{MODE_ICONS[id]}</svg></span>
      <span class="mode-text">
        <span class="mode-name">{name}</span>
        <span class="mode-blurb">{blurb}</span>
        <span class="mode-status">{status}</span>
      </span>
      {fan && (
        <span class="plaque-fan" aria-hidden="true">
          {fan.map((colour, i) => <i key={colour} data-frame={colour} style={{ '--turn': `${(i - (fan.length - 1) / 2) * 7}deg` }} />)}
        </span>
      )}
    </button>
  );
}

/**
 * The name and face to play under, asked for before anything else, along with the host seat while it is free.
 * Every browser shares the server's one set of preferences, so nobody can be named from them but the host;
 * and two players of one name cannot share a game.
 */
export function NamePrompt({ model, actions, initial = '', cancel }: {
  model: Model; actions: Actions; initial?: string; cancel?: () => void;
}) {
  const [value, setValue] = useState(initial);
  const [avatar, setAvatar] = useState(rememberedAvatar);
  const [picking, setPicking] = useState(false);
  const [takeHost, setTakeHost] = useState(true);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    input.current?.focus();
  }, []);
  // A name this browser used before is being offered already, so asking again would only flash past
  if (model.nameSent) {
    return <div class="menu-page start"><Wordmark /></div>;
  }
  // Changing your name later is not the moment to be offered a seat you may already hold
  const offerHost = !cancel && model.canClaimHost;
  return (
    <div class="menu-page start">
      <Wordmark />
      <form class="start-card" onSubmit={e => {
        e.preventDefault();
        const name = value.trim();
        if (!name) {
          return;
        }
        rememberAvatar(avatar);
        // The seat is taken first, so the name reaches a session that already knows it is the host and does
        // not set off a join as a guest
        if (offerHost && takeHost) {
          actions.claimHost();
        }
        actions.setName(name, avatar);
      }}>
        <div class="start-field">
          <label for="player-name">{t('lblWebMenuCallYou')}</label>
          <div class="name-row">
            <button type="button" class="face" title={t('lblWebMenuSelectAvatar')} aria-label={t('lblWebMenuSelectAvatar')} onClick={() => setPicking(true)}>
              <img alt="" src={avatarUrl(avatar)} />
              <span class="face-edit" aria-hidden="true">
                <svg viewBox="0 0 24 24"><path d="M4.5 19.5h5L20 9a2.6 2.6 0 0 0-3.7-3.7L5.8 15.8z" /></svg>
              </span>
            </button>
            <input id="player-name" ref={input} value={value} maxLength={MAX_NAME_LENGTH} autocomplete="nickname"
              onInput={e => setValue(e.currentTarget.value)} />
          </div>
        </div>
        {offerHost && (
          <label class="host-box">
            <input type="checkbox" checked={takeHost} onChange={e => setTakeHost(e.currentTarget.checked)} />
            <span class="host-text">
              <b>{t('lblWebMenuHostGame')}</b>
              <span>{t('lblWebMenuHostGameBlurb')}</span>
            </span>
          </label>
        )}
        <div class="start-buttons">
          {cancel && <button type="button" onClick={cancel}>{t('lblCancel')}</button>}
          <button type="submit" class="primary" disabled={!value.trim()}>{t(cancel ? 'lblWebMenuChange' : 'lblContinue')}</button>
        </div>
      </form>
      <p class={model.error ? 'menu-note bad' : 'menu-note'}>{model.error ?? ''}</p>
      {picking && (
        <LookPicker title={t('lblWebMenuChooseYourAvatar')} count={model.looks?.avatarCount ?? 0} urlOf={avatarUrl} current={avatar}
          close={chosen => {
            setPicking(false);
            if (chosen !== null) {
              setAvatar(chosen);
            }
          }} />
      )}
    </div>
  );
}

const NAME_KEY = 'forge.playerName';
const AVATAR_KEY = 'forge.avatar';

/** The name this browser last played under, offered for it when it arrives on a server that does not know it. */
export function rememberedName(): string | null {
  return stored(NAME_KEY);
}

export function rememberName(name: string | null): void {
  store(NAME_KEY, name || null);
}

/** The face this browser last played under. The first avatar is as good a default as any. */
export function rememberedAvatar(): number {
  const saved = Number(stored(AVATAR_KEY));
  return Number.isInteger(saved) && saved >= 0 ? saved : 0;
}

export function rememberAvatar(index: number): void {
  store(AVATAR_KEY, String(index));
}

/**
 * A browser with no seat. Three things can be true here and they used to read as one sentence: a seat is being
 * taken, the last attempt at one failed, or nobody has opened a table at all. The trail says how far the browser
 * got, which is the difference between nothing happening yet and something unfinished.
 */
function Waiting({ model, actions }: { model: Model; actions: Actions }) {
  const failed = !model.joining && !!model.error;
  const state = model.joining ? 'joining' : failed ? 'failed' : 'idle';
  return (
    <div class="menu-page">
      <Wordmark />
      <section class={`wait-card ${state}`}>
        <ol class="trail">
          <Step name={t('lblWebMenuStepConnected')} done />
          <Step name={t('lblWebMenuStepNamed')} done={!!model.playerName} />
          <Step name={t('lblWebMenuStepSeated')} state={state} />
        </ol>
        <div class="wait-head">
          <span class="wait-mark" aria-hidden="true" />
          <b>{t(model.joining ? 'lblWebMenuTakingSeat' : failed ? 'lblWebMenuCouldNotTakeSeat' : 'lblWebMenuNoTableYet')}</b>
        </div>
        <p class="wait-body">
          {model.joining ? t('lblWebMenuMakingRoom')
            : failed ? model.error
              : t(model.canClaimHost ? 'lblWebMenuNobodyHosting' : 'lblWebMenuSeatedWhenOpens')}
        </p>
        <div class="wait-buttons">
          {failed && <button onClick={() => actions.join()}>{t('lblWebMenuTryAgain')}</button>}
          {model.canClaimHost && <button class="primary" onClick={() => actions.claimHost()}>{t('lblWebMenuHostTable')}</button>}
        </div>
      </section>
    </div>
  );
}

function Step({ name, done = false, state }: { name: string; done?: boolean; state?: string }) {
  const mark = state === undefined ? (done ? 'done' : 'todo') : state === 'joining' ? 'now' : state === 'failed' ? 'failed' : 'todo';
  return <li class={`step ${mark}`}><span class="dot" aria-hidden="true" />{name}</li>;
}
