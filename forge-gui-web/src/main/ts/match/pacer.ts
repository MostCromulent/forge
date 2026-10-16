// Holds the game back while the page shows something the player should see first: a turn's banner, or another player's play

import { announceComing } from './turnbanner';
import { asksPlayer, passingFor, setBoardBehind, worthSeeing } from './pace';
import { setting } from '../settings';
import type { Model } from '../model';
import type { ServerMessage } from '../protocol';

/** What is held back for the player to see in its turn: the game as it goes. Anything else, such as chat or a card's details, is shown as it comes. */
const HELD_MESSAGES = new Set(['state', 'prompt', 'controls', 'request', 'zones', 'playable', 'sound', 'log', 'gameOver', 'drawOffer', 'flash']);
/** Longer than any banner, so it only matters if the banner's own release is lost. */
const HOLD_LIMIT_MS = 4000;
/** The sound of a turn ending, which the host sends with the next turn's first state. */
const TURN_SOUND = 'end_of_turn';

/** Takes the server's messages in order, holding the game's back while a banner or another player's play is on screen. apply draws one into the model. */
export function createPacer(model: Model, apply: (msg: ServerMessage) => void): { receive: (msg: ServerMessage) => void; holding: () => boolean } {
  /** What has arrived since a new turn began, while its banner shows, or behind a board that is still being shown. */
  let held: ServerMessage[] | null = null;
  /** Until when the board on screen stays up, because it shows another player's play. */
  let showUntil = 0;
  /** Whether what is held waits behind a turn's banner, and not behind a board still being shown. */
  let announcing = false;

  function receive(msg: ServerMessage): void {
    if (!held) {
      // Each release is for this turn's hold only, so an earlier banner ending late cannot free a later turn early
      const releaseThis = () => { if (held?.[0] === msg) release(); };
      // A sound or a log line comes behind its own board, so it waits with that board when the board does, and not for it
      if (msg.t === 'state' && !msg.full && Date.now() < showUntil) {
        held = [msg];
        setBoardBehind(true);
        setTimeout(releaseThis, showUntil - Date.now());
      } else if (msg.t === 'state' && announceComing(model, msg, releaseThis)) {
        held = [msg];
        setBoardBehind(true);
        announcing = true;
        // The banner's end releases the turn; this only guards the page against a release that never comes
        setTimeout(releaseThis, HOLD_LIMIT_MS);
      } else {
        apply(msg);
        if (msg.t === 'state' && worthSeeing(msg.events, msg.localPlayers)) {
          showUntil = Date.now() + Number(setting('pace'));
        } else if (msg.t === 'request' || (msg.t === 'prompt' && asksPlayer(msg, passingFor(model.controls)))) {
          // Asked something, the player is looking at the board as it stands, and what they do next is shown at once
          showUntil = 0;
        }
      }
    } else if (msg.t === 'hello') {
      // A new table or a reconnection starts over: what waited is taken as it stands, with none of it holding the rest back
      const waiting = held;
      held = null;
      setBoardBehind(false);
      announcing = false;
      showUntil = 0;
      // The sounds that waited are for moments already past, so they are not all played at once
      waiting.filter(m => m.t !== 'sound').forEach(apply);
      apply(msg);
    } else if (!HELD_MESSAGES.has(msg.t)) {
      apply(msg);
    } else if (msg.t === 'sound' && msg.name === TURN_SOUND && announcing && !held.slice(1).some(m => m.t === 'state')) {
      // The turn's sound plays as its banner goes up. A later turn's sound waits behind that turn's own state
      apply(msg);
    } else {
      held.push(msg);
    }
  }

  function release(): void {
    const waiting = held ?? [];
    held = null;
    setBoardBehind(false);
    announcing = false;
    waiting.forEach(receive);
  }
  return { receive, holding: () => held !== null };
}
