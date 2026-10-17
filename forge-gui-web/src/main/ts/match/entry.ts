// The way into a match is one push in: the page the player starts it from zooms in as it darkens, and the board comes in
// still zooming once it has its seats, so the empty table it is first drawn as never shows

import { byId, reducedMotion, replay } from '../dom';

/** A board that has come on screen but not yet in. */
let awaiting = false;
let giveUp = 0;

/** Zooms and darkens the page, then starts the match. A match the server never starts leaves the page as it was. */
export function zoomIntoMatch(page: HTMLElement, start: () => void): void {
  if (reducedMotion()) {
    start();
    return;
  }
  page.classList.add('to-match');
  const zoomed = (e: AnimationEvent) => {
    if (e.target !== page) return;
    page.removeEventListener('animationend', zoomed);
    start();
  };
  page.addEventListener('animationend', zoomed);
  clearTimeout(giveUp);
  giveUp = window.setTimeout(() => page.classList.remove('to-match'), 8000);
}

/** Called each frame the match is drawn, entering when it has just come on screen: the board is held back until both sides are seated. */
export function arriveAtMatch(entering: boolean): void {
  if (entering && !reducedMotion()) awaiting = true;
  if (!awaiting) return;
  const match = byId('match');
  if (!document.querySelector('#me .player') || !document.querySelector('#opponent .player')) {
    match.classList.add('held');
    return;
  }
  awaiting = false;
  clearTimeout(giveUp);
  match.classList.remove('held');
  replay(match, 'arriving');
  match.addEventListener('animationend', function done(e) {
    if (e.target !== match) return;
    match.removeEventListener('animationend', done);
    match.classList.remove('arriving');
  });
  // The page stays zoomed until the board covers it, and is itself again when the match ends
  document.querySelector('.to-match')?.classList.remove('to-match');
}
