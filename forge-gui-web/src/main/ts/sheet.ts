// What every sheet on a phone shares: Back closes it, a swipe down closes it, and opening one closes the others.

import { byId, q } from './dom';

const open = new Map<string, () => void>();
let inMatch = false;
let leaving = false;
/** Steps back this module took itself, to give a closed sheet's entry back, which are not the player pressing Back. */
let ownSteps = 0;

/** A match holds one history entry of its own, so Back with nothing open asks before it leaves the page. */
export function matchHistory(now: boolean): void {
  if (now && !inMatch) history.pushState({ match: true }, '');
  inMatch = now;
}

export function initSheets(): void {
  const ask = byId('leave-ask');
  q(ask, '.stay').onclick = () => { ask.hidden = true; };
  // Leaving is what Back would have done: out past the entry the match holds
  q(ask, '.leave').onclick = () => {
    ask.hidden = true;
    leaving = true;
    history.go(-2);
    setTimeout(() => { leaving = false; }, 500);
  };
  addEventListener('popstate', () => {
    if (ownSteps > 0) {
      ownSteps--;
      return;
    }
    const last = [...open.keys()].pop();
    if (last === undefined) {
      if (inMatch && !leaving) {
        history.pushState({ match: true }, '');
        ask.hidden = false;
      }
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
    if (history.state?.sheet === id) {
      ownSteps++;
      history.back();
    }
  }
}

export function closeSheets(except?: string): void {
  for (const [id, close] of [...open]) {
    if (id !== except) close();
  }
}

export const anySheet = (): boolean => open.size > 0;

/** A swipe down from a sheet's handle closes it; lower down, the same swipe scrolls what the sheet holds. */
export function swipeDown(el: HTMLElement, close: () => void): void {
  el.addEventListener('pointerdown', e => {
    if (e.pointerType !== 'touch' || e.clientY - el.getBoundingClientRect().top > 48) return;
    const from = e.clientY;
    const up = (ev: PointerEvent) => {
      document.removeEventListener('pointerup', up);
      if (ev.clientY - from > 60) close();
    };
    document.addEventListener('pointerup', up);
  });
}
