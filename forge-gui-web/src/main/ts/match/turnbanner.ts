// The banner that names whose turn it is as the turn starts

import { deref, game, isLocal, players, type Model } from '../model';
import { byId, make } from '../dom';
import { t } from '../text';
import type { GameView, PlayerView, Ref, StateMessage } from '../protocol';

// The turn last announced, null on a new table so one first seen part way through says nothing until the turn changes
let announced: string | null = null;

export function resetTurnBanner(): void {
  announced = null;
}

/** Announces the turn a state message starts before it is shown, and runs `then` only if a banner went up, as it leaves. */
export function announceComing(model: Model, msg: StateMessage, then: () => void): boolean {
  const g = game(model);
  const delta = msg.deltas[model.root] as { Turn?: number; PlayerTurn?: Ref } | undefined;
  // Only the turn's number starts a turn: its player is named in the cleanup step, which can come a message earlier
  if (msg.full || !g || !delta || delta.Turn === undefined) {
    return false;
  }
  return announce(model, delta.Turn, deref(model, delta.PlayerTurn ?? g.PlayerTurn), then);
}

// Only catches the turn a table was first drawn in, as every later turn is announced when its message arrives
export function announceTurn(model: Model, g: GameView): void {
  if (announced === null) announce(model, g.Turn, deref(model, g.PlayerTurn), () => {});
}

/** How long before the turn banner has gone that the turn's own actions start. */
const TURN_OVERLAP_MS = 300;

function announce(model: Model, turnNumber: number | undefined, active: PlayerView | undefined, then: () => void): boolean {
  const turn = `${turnNumber ?? 0}/${active?.$key ?? ''}`;
  if (!active || announced === turn) {
    return false;
  }
  const seenBefore = announced !== null;
  announced = turn;
  if (!seenBefore && turnNumber !== 1) {
    return false;
  }
  const mine = isLocal(model, active);
  // With several opponents most turns are someone else's, and the phase pill already names whose it is
  if (!mine && players(model).length > 2) {
    return false;
  }
  const banner = make('div', `turn-banner${mine ? ' mine' : ''}`);
  banner.textContent = mine ? t('lblWebPhaseYourTurn') : t('lblWebBoardPlayersTurn', active.Name ?? '');
  // The banner takes the pill's place instead of covering it, because the pill animates its own width
  const strip = byId('phase-strip');
  strip.append(banner);
  strip.classList.add('announcing');
  // The pill returns as the banner starts to fade (84% of turn-sweep), so one fades in while the other fades out
  const sweep = parseFloat(getComputedStyle(banner).animationDuration) * 1000;
  // unless the next turn's banner has gone up meanwhile, which a turn that passes at once allows
  setTimeout(() => {
    if ([...strip.querySelectorAll('.turn-banner')].at(-1) === banner) strip.classList.remove('announcing');
  }, sweep * 0.84);
  // The turn goes on just before the banner has quite gone
  setTimeout(then, Math.max(0, sweep - TURN_OVERLAP_MS));
  // The end of the ::after light's animation reaches the banner too, so the sweep is named or the banner leaves early
  banner.addEventListener('animationend', e => {
    if (e.animationName === 'turn-sweep') banner.remove();
  });
  return true;
}
