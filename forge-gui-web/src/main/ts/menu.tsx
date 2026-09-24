// The first screen: the name and face you play under, and whether you are the one opening the game. Those used
// to be two pages, but the host seat is only ever offered to a browser holding the host's link while nobody
// holds the seat, so the offer costs one checkbox and everyone else never sees it.
//
// Past that, a browser holding the seat gets the menu; every other one is told to wait for a table.

import { useEffect, useRef, useState } from 'preact/hooks';
import { LookPicker } from './lookpicker';
import { avatarUrl } from './looks';
import type { Actions } from './actions';
import type { Model } from './model';

/** Kept to the server's limit (WebSession.MAX_NAME_LENGTH), so the field stops where the server would refuse. */
const MAX_NAME_LENGTH = 24;

/** Magic's five colours, the one place they are pure decoration: no card art nearby and nothing encoded by hue. */
function Wordmark() {
  return (
    <div class="wordmark-block">
      <span class="wordmark">Forge</span>
      <span class="stripes" aria-hidden="true"><i /><i /><i /><i /><i /></span>
    </div>
  );
}

export function Menu({ model, actions }: { model: Model; actions: Actions }) {
  const [renaming, setRenaming] = useState(false);
  // A new name arriving means the change went through
  useEffect(() => setRenaming(false), [model.playerName]);
  if (renaming) {
    return <NamePrompt model={model} actions={actions} initial={model.playerName} cancel={() => setRenaming(false)} />;
  }
  // A browser without the host's seat has no menu: it waits for somebody to open a table
  if (!model.host) {
    return <Waiting model={model} actions={actions} />;
  }
  const decks = model.decks?.length ?? 0;
  // Only the ones that open something are built; the rest are shown so the shape of the product is honest,
  // and greyed so nothing looks broken
  return (
    <div class="menu-page">
      <header class="menu-head">
        <Wordmark />
        <div class="menu-who">
          <img class="menu-face" alt="" src={avatarUrl(rememberedAvatar())} />
          <span class="menu-name">{model.playerName}</span>
          <button class="link" onClick={() => setRenaming(true)}>Change name</button>
          <button onClick={() => actions.quit()}>Quit Forge</button>
        </div>
      </header>
      <div class="modes">
        <Mode id="play" name="Play the computer" blurb="One match against Forge's AI. Nothing to set up."
          status={decks ? `${decks} decks ready` : 'no decks yet — a precon will do'} onClick={() => actions.openLobby(false)} />
        <Mode id="multiplayer" name="Play with friends" blurb="Open a table and send a link. Up to four seats."
          status="Gives you a link to share" onClick={() => actions.openLobby(true)} />
        <Mode id="editor" name="Decks" blurb="Build and change decks in the browser." status="Not built yet" />
      </div>
      <p class={model.error ? 'menu-note bad' : 'menu-note'}>{model.error ?? ''}</p>
    </div>
  );
}

function Mode({ id, name, blurb, status, onClick }: { id: string; name: string; blurb: string; status: string; onClick?: () => void }) {
  return (
    <button class="mode" data-mode={id} disabled={!onClick} onClick={onClick}>
      <span class="mode-art" aria-hidden="true" />
      <span class="mode-text">
        <span class="mode-name">{name}</span>
        <span class="mode-blurb">{blurb}</span>
        <span class="mode-status">{status}</span>
      </span>
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
          <label for="player-name">What should we call you?</label>
          <div class="name-row">
            <button type="button" class="face" title="Select avatar" aria-label="Select avatar" onClick={() => setPicking(true)}>
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
              <b>Host the game</b>
              <span>You choose the format and start the match. Everyone else joins the table you open.</span>
            </span>
          </label>
        )}
        <div class="start-buttons">
          {cancel && <button type="button" onClick={cancel}>Cancel</button>}
          <button type="submit" class="primary" disabled={!value.trim()}>{cancel ? 'Change' : 'Continue'}</button>
        </div>
      </form>
      <p class={model.error ? 'menu-note bad' : 'menu-note'}>{model.error ?? ''}</p>
      {picking && (
        <LookPicker title="Choose your avatar" count={model.looks?.avatarCount ?? 0} urlOf={avatarUrl} current={avatar}
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
  try {
    return localStorage.getItem(NAME_KEY);
  } catch {
    return null;
  }
}

export function rememberName(name: string | null): void {
  try {
    if (name) localStorage.setItem(NAME_KEY, name);
    else localStorage.removeItem(NAME_KEY);
  } catch {
    // Storage can be unavailable; the name is then asked for again next time
  }
}

/** The face this browser last played under. The first avatar is as good a default as any. */
export function rememberedAvatar(): number {
  try {
    const saved = Number(localStorage.getItem(AVATAR_KEY));
    return Number.isInteger(saved) && saved >= 0 ? saved : 0;
  } catch {
    return 0;
  }
}

export function rememberAvatar(index: number): void {
  try {
    localStorage.setItem(AVATAR_KEY, String(index));
  } catch {
    // Storage can be unavailable; the face is then chosen again next time
  }
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
          <Step name="Connected" done />
          <Step name="Named" done={!!model.playerName} />
          <Step name="Seated" state={state} />
        </ol>
        <div class="wait-head">
          <span class="wait-mark" aria-hidden="true" />
          <b>{model.joining ? 'Taking your seat' : failed ? 'Could not take a seat' : 'No table open yet'}</b>
        </div>
        <p class="wait-body">
          {model.joining ? 'A table is making room for you.'
            : failed ? model.error
              : model.canClaimHost ? 'Nobody is hosting. Host the table yourself, or wait here to be seated when someone else opens one.'
                : 'You are seated as soon as one opens.'}
        </p>
        <div class="wait-buttons">
          {failed && <button onClick={() => actions.join()}>Try again</button>}
          {model.canClaimHost && <button class="primary" onClick={() => actions.claimHost()}>Host the table</button>}
        </div>
      </section>
    </div>
  );
}

function Step({ name, done = false, state }: { name: string; done?: boolean; state?: string }) {
  const mark = state === undefined ? (done ? 'done' : 'todo') : state === 'joining' ? 'now' : state === 'failed' ? 'failed' : 'todo';
  return <li class={`step ${mark}`}><span class="dot" aria-hidden="true" />{name}</li>;
}
