// Entering a match is one continuous zoom. The page the match starts from zooms in and darkens, and stays drawn under the board.
// The board then zooms in over it once its seats are drawn and the page's zoom has ended, so the empty table that is drawn first is never shown

import { byId, reducedMotion, replay } from '../dom';

/** Whether the board is on screen but has not yet played its entry animation. */
let awaiting = false;
/** Whether the page the match starts from is still zooming. */
let zooming = false;
let giveUp = 0;
/** What waits for the board's entry animation to end. */
let afterEntry: (() => void)[] = [];

/** Starts the match and zooms and darkens the page. If the server never starts the match, the page goes back to how it was. */
export function zoomIntoMatch(page: HTMLElement, start: () => void): void {
  start();
  if (reducedMotion()) return;
  // The page is fixed where it is scrolled to, so it stays in place when the board is drawn below it
  page.style.setProperty('--scrolled', `${-window.scrollY}px`);
  page.classList.add('to-match');
  zooming = true;
  const zoomed = (e: AnimationEvent) => {
    if (e.target !== page) return;
    page.removeEventListener('animationend', zoomed);
    zooming = false;
    arrive();
  };
  page.addEventListener('animationend', zoomed);
  clearTimeout(giveUp);
  giveUp = window.setTimeout(() => {
    zooming = false;
    page.classList.remove('to-match');
  }, 8000);
}

/** Called each time the match is drawn. `entering` is true when it has just come on screen. */
export function arriveAtMatch(entering: boolean): void {
  if (entering) {
    // Anything still waiting belongs to a match that closed before its board came on screen
    afterEntry = [];
    awaiting = !reducedMotion();
  }
  arrive();
}

/** Runs `then` once the board has finished coming on screen, or at once if it has. */
export function whenArrived(then: () => void): void {
  if (awaiting || byId('match').classList.contains('arriving')) afterEntry.push(then);
  else then();
}

/** Plays the board's entry once both seats are drawn and the page under it has stopped zooming. Until then the board is hidden. */
function arrive(): void {
  if (!awaiting) return;
  const match = byId('match');
  if (zooming || !document.querySelector('#me .player') || !document.querySelector('#opponent .player')) {
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
    // The page under the board is hidden once the board covers it. It returns to normal when the match ends
    const page = document.querySelector<HTMLElement>('.to-match');
    page?.classList.remove('to-match');
    if (page) page.hidden = true;
    const waiting = afterEntry;
    afterEntry = [];
    waiting.forEach(then => then());
  });
}
