// The game menu behind the prompt's ⋯ button: what a player does to the game as a whole rather than on the board,
// and the auto-pass stops, which are set often enough mid-game to sit one click from the prompt

import { useLayoutEffect, useRef, useState } from 'preact/hooks';
import { Row } from './options';
import { SETTINGS } from './settings';
import type { Actions } from './actions';
import { deref, type Model } from './model';

export function GameMenu({ model, actions, close, openStops }: {
  model: Model; actions: Actions; close: () => void; openStops: () => void;
}) {
  const [armed, setArmed] = useState(false);
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
      <div ref={menu} class="card-menu game-menu" role="menu" aria-label="Game">
        <button type="button" role="menuitem" class="card-menu-item" disabled={!!offer || model.spectating}
          onClick={() => { actions.drawOffer('OFFER'); close(); }}>
          {offer?.mine ? 'Draw offered, waiting for an answer' : 'Offer a draw'}
        </button>
        <button type="button" role="menuitem" class="card-menu-item" onClick={openStops}>Auto-pass stops…</button>
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
      </div>
    </div>
  );
}

/** Where auto-passing stops by itself, as the options dialog would list them. */
export function AutoPassStops({ close }: { close: () => void }) {
  return (
    <div id="options" class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
      <div class="options-dialog stops-dialog" role="dialog" aria-label="Auto-pass stops">
        <header>
          <b>Stop auto-passing when…</b>
          <span class="spacer" />
          <button class="close" title="Close (Esc)" onClick={close}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18" /><path d="m6 6 12 12" /></svg>
          </button>
        </header>
        <div class="rows">
          {SETTINGS.filter(def => def.stops).map(def => <Row key={def.key} def={def} />)}
        </div>
        <footer>
          <span class="hint">Auto-passing gives you priority back at these moments. Changes apply at once.</span>
        </footer>
      </div>
    </div>
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
