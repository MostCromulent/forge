// What every sheet on a phone shares: Back closes it, a swipe down closes it, and opening one closes the others.

import { byId, q } from './dom';

const open = new Map<string, () => void>();
let inMatch = false;
let leaving = false;
/** Steps back this module took itself, to give a closed sheet's entry back, which are not the player pressing Back. */
let ownSteps = 0;

/** A match holds one history entry of its own, so Back with nothing open asks before it leaves the page. */
export function matchHistory(now: boolean): void {
  // A reload keeps the entry the match already had
  if (now && !inMatch && !history.state?.match) history.pushState({ match: true }, '');
  if (!now && inMatch) {
    // Given back with the match, so the page it returns to has no Back press that goes nowhere
    open.clear();
    if (history.state?.match) step();
  }
  inMatch = now;
}

/** A step back this module takes itself, which the Back handler must not take for the player's. */
function step(): void {
  ownSteps++;
  history.back();
}

export function initSheets(): void {
  const ask = byId('leave-ask');
  // Staying puts back the entry that Back took, so the next Back asks again
  q(ask, '.stay').onclick = () => {
    ask.hidden = true;
    if (inMatch && !history.state?.match) history.pushState({ match: true }, '');
  };
  // Leaving is what Back would have done: out past the entry the match holds
  q(ask, '.leave').onclick = () => {
    ask.hidden = true;
    leaving = true;
    // Back was already taken once to get here, so one more step is the page before this one
    history.back();
    setTimeout(() => { leaving = false; }, 500);
  };
  addEventListener('popstate', () => {
    if (ownSteps > 0) {
      ownSteps--;
      return;
    }
    const last = [...open.keys()].pop();
    // An entry left by a sheet that closed while another was above it holds nothing, so Back passes through it
    if (history.state?.sheet && !open.has(history.state.sheet)) {
      step();
    }
    if (last === undefined) {
      // The match's own entry was just left; the question stands in front of the step that leaves the page
      if (inMatch && !leaving && !history.state?.match) ask.hidden = false;
      return;
    }
    const close = open.get(last);
    open.delete(last);
    close?.();
  });
}

/** Told each frame whether a sheet is open, so a sheet closed by any route also gives its history entry back. */
export function sheet(id: string, isOpen: boolean, close: () => void): void {
  if (isOpen && !open.has(id)) {
    open.set(id, close);
    history.pushState({ sheet: id }, '');
  } else if (isOpen) {
    open.set(id, close);
  } else if (open.has(id)) {
    open.delete(id);
    if (history.state?.sheet === id) step();
  }
}

/** A swipe down from a sheet's handle closes it; lower down, the same swipe scrolls what the sheet holds. */
export function swipeDown(el: HTMLElement, close: () => void): void {
  // Touch events, since a browser that takes a drag for a scroll cancels the pointer and never says where it lifted
  let from: number | null = null;
  el.addEventListener('touchstart', e => {
    const y = e.touches[0].clientY;
    from = y - el.getBoundingClientRect().top <= 48 ? y : null;
  }, { passive: true });
  el.addEventListener('touchend', e => {
    if (from !== null && e.changedTouches[0].clientY - from > 60) close();
    from = null;
  });
  el.addEventListener('touchcancel', () => { from = null; });
}
