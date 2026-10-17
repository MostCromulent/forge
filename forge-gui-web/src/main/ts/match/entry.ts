// Entering a match is one continuous zoom. The page the match starts from zooms in and darkens. The board then zooms in
// once its seats are drawn, so the empty table that is drawn first is never shown

import { byId, reducedMotion, replay } from '../dom';

/** Whether the board is on screen but has not yet played its entry animation. */
let awaiting = false;
let giveUp = 0;

/** Zooms and darkens the page, then starts the match. If the server never starts the match, the page goes back to how it was. */
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

/** Called each time the match is drawn. `entering` is true when it has just come on screen. The board stays hidden until both seats are drawn. */
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
  // The page stays zoomed until the board covers it. It returns to normal when the match ends
  document.querySelector('.to-match')?.classList.remove('to-match');
}
