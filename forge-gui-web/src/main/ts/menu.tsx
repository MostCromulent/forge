// The first screen: what you can do, and where each one stands. A mode says what it holds for you
// ("14 decks", "no decks yet") rather than repeating its own name, so the page is worth reading once.
//
// Only the browser holding the host's seat sees this. Nobody holds it by arriving: the seat is offered to
// whoever asks first, and every other browser goes straight to a seat in the host's game.

import { useEffect, useRef, useState } from 'preact/hooks';
import type { Actions } from './actions';
import type { Model } from './model';

/** Kept to the server's limit (WebSession.MAX_NAME_LENGTH), so the field stops where the server would refuse. */
const MAX_NAME_LENGTH = 24;

export function Menu({ model, actions }: { model: Model; actions: Actions }) {
  const [renaming, setRenaming] = useState(false);
  // A new name arriving means the change went through
  useEffect(() => setRenaming(false), [model.playerName]);
  if (renaming) {
    return <NamePrompt model={model} actions={actions} initial={model.playerName} cancel={() => setRenaming(false)} />;
  }
  // A browser without the host's seat has no menu: it is offered the seat, or told to wait for one
  if (!model.host) {
    return <Waiting model={model} actions={actions} />;
  }
  const decks = model.decks?.length ?? 0;
  // Only the ones that open something are built; the rest are shown so the shape of the product is honest,
  // and greyed so nothing looks broken
  return (
    <div class="menu-page">
      <h1 class="wordmark">Forge</h1>
      <div class="modes">
        <Mode id="play" name="Offline" blurb="A match against the computer"
          status={decks ? `${decks} decks ready` : 'no decks yet — a precon will do'} onClick={() => actions.openLobby(false)} />
        <Mode id="multiplayer" name="Multiplayer" blurb="Send a friend a link to your game"
          status="opens a seat and gives you a link" onClick={() => actions.openLobby(true)} />
        <Mode id="editor" name="Deck editor" blurb="Build and change decks" status="not built yet" />
      </div>
      <p class={model.error ? 'menu-note bad' : 'menu-note'}>{model.error ?? ''}</p>
      <div class="menu-foot">
        <span>{model.playerName ? `Playing as ${model.playerName}` : ''}</span>
        <button class="link" onClick={() => setRenaming(true)}>Change name</button>
        <button onClick={() => actions.quit()}>Quit</button>
      </div>
    </div>
  );
}

function Mode({ id, name, blurb, status, onClick }: { id: string; name: string; blurb: string; status: string; onClick?: () => void }) {
  return (
    <button class="mode" data-mode={id} disabled={!onClick} onClick={onClick}>
      <span class="mode-name">{name}</span>
      <span class="mode-blurb">{blurb}</span>
      <span class="mode-status">{status}</span>
    </button>
  );
}

/**
 * The name to play under, asked for before anything else. Every browser shares the server's one set of
 * preferences, so nobody can be named from them but the host; and two players of one name cannot share a game.
 */
export function NamePrompt({ model, actions, initial = '', cancel }: {
  model: Model; actions: Actions; initial?: string; cancel?: () => void;
}) {
  const [value, setValue] = useState(initial);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    input.current?.focus();
  }, []);
  // A name this browser used before is being offered already, so asking again would only flash past
  if (model.nameSent) {
    return <div class="menu-page"><h1 class="wordmark">Forge</h1></div>;
  }
  return (
    <div class="menu-page">
      <h1 class="wordmark">Forge</h1>
      <form class="name-form" onSubmit={e => {
        e.preventDefault();
        if (value.trim()) actions.setName(value.trim());
      }}>
        <label for="player-name">What should the other players call you?</label>
        <input id="player-name" ref={input} value={value} maxLength={MAX_NAME_LENGTH} autocomplete="nickname"
          onInput={e => setValue(e.currentTarget.value)} />
        <div class="name-buttons">
          {cancel && <button type="button" onClick={cancel}>Cancel</button>}
          <button type="submit" class="primary" disabled={!value.trim()}>{cancel ? 'Change' : 'Continue'}</button>
        </div>
      </form>
      <p class={model.error ? 'menu-note bad' : 'menu-note'}>{model.error ?? ''}</p>
    </div>
  );
}

const NAME_KEY = 'forge.playerName';

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

/** Remembered so the browser that runs this server does not have to say so on every launch. */
const HOSTED_KEY = 'forge.hostedBefore';

export function hostedBefore(): boolean {
  try {
    return localStorage.getItem(HOSTED_KEY) === '1';
  } catch {
    return false;
  }
}

/**
 * A browser with no seat. Nobody hosts by arriving, so while the host's seat is free this offers it. A browser
 * that has hosted this server before is given it back by the controller without being asked again.
 */
function Waiting({ model, actions }: { model: Model; actions: Actions }) {
  if (!model.canClaimHost) {
    return (
      <div class="menu-page">
        <h1 class="wordmark">Forge</h1>
        <p class="menu-note">Waiting for the host to open a game.</p>
      </div>
    );
  }
  const host = () => {
    try {
      localStorage.setItem(HOSTED_KEY, '1');
    } catch {
      // Storage can be unavailable; the choice then has to be made again next launch
    }
    actions.claimHost();
  };
  return (
    <div class="menu-page">
      <h1 class="wordmark">Forge</h1>
      <p class="menu-note">Nobody is running a game on this server yet.</p>
      <div class="connect">
        <section class="connect-card">
          <h2>Host the game</h2>
          <p>You set the table, pick the format and start the match. Everyone else joins you.</p>
          <button id="be-host" class="primary" onClick={host}>Host</button>
        </section>
        <section class="connect-card">
          <h2>Wait for a host</h2>
          <p>Someone else takes the seat. You are given one of your own as soon as they open a game.</p>
        </section>
      </div>
    </div>
  );
}
