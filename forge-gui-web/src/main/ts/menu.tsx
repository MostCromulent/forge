// The first screen: what you can do, and where each one stands. A mode says what it holds for you
// ("14 decks", "no decks yet") rather than repeating its own name, so the page is worth reading once.
//
// Only the browser holding the host's seat sees this. Nobody holds it by arriving: the seat is offered to
// whoever asks first, and every other browser goes straight to a seat in the host's game.

import type { Actions } from './actions';
import type { Model } from './model';

export function Menu({ model, actions }: { model: Model; actions: Actions }) {
  // A browser without the host's seat has no menu: it is offered the seat, or told to wait for one
  if (model.host === false) {
    return <Waiting model={model} actions={actions} />;
  }
  const decks = model.decks?.length ?? 0;
  // Only the ones that open something are built; the rest are shown so the shape of the product is honest,
  // and greyed so nothing looks broken
  return (
    <div class="menu-page">
      <h1 class="wordmark">Forge</h1>
      <div class="modes" id="modes">
        <Mode id="play" name="Offline" blurb="A match against the computer"
          status={decks ? `${decks} decks ready` : 'no decks yet — a precon will do'} onClick={() => actions.openLobby(false)} />
        <Mode id="multiplayer" name="Multiplayer" blurb="Send a friend a link to your game"
          status="opens a seat and gives you a link" onClick={() => actions.openLobby(true)} />
        <Mode id="editor" name="Deck editor" blurb="Build and change decks" status="not built yet" />
      </div>
      <p class={model.error ? 'menu-note bad' : 'menu-note'} id="menu-note">{model.error ?? ''}</p>
      <div class="menu-foot">
        <span id="menu-who">{model.playerName ? `Playing as ${model.playerName}` : ''}</span>
        <button id="menu-quit" onClick={() => actions.quit()}>Quit</button>
      </div>
    </div>
  );
}

function Mode({ id, name, blurb, status, onClick }: { id: string; name: string; blurb: string; status: string; onClick?: () => void }) {
  return (
    <button class="mode" data-mode={id} disabled={!onClick} onClick={onClick}>
      <span class="mode-name">{name}</span>
      <span class="mode-blurb">{blurb}</span>
      <span class="mode-status" data-status={id}>{status}</span>
    </button>
  );
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
