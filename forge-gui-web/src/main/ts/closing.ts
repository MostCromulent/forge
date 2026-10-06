// What closes plays its way out first (dialogs.css), as the chat dock does, and is gone once that animation ends

import { useState } from 'preact/hooks';
import { reducedMotion } from './dom';

/** Longer than any way out (dialogs.css). */
const GIVE_UP_MS = 400;

/** A close that waits for the way out: the element takes `closing`, and its own animation's end closes it. */
export function useClosing(close: () => void): { closing: boolean; shut: () => void; gone: (e: AnimationEvent) => void } {
  const [closing, setClosing] = useState(false);
  return {
    closing,
    shut: () => {
      if (reducedMotion()) {
        close();
        return;
      }
      setClosing(true);
      // Closed anyway if no animation runs to say so, as when a style leaves it none
      setTimeout(close, GIVE_UP_MS);
    },
    gone: e => { if (closing && e.target === e.currentTarget) close(); },
  };
}

/** The same for an element drawn by hand: it takes `closing`, and is removed once its animation ends. */
export function leave(el: Element | null): void {
  if (!el) return;
  if (reducedMotion() || el.classList.contains('closing')) {
    el.remove();
    return;
  }
  el.classList.add('closing');
  el.addEventListener('animationend', e => { if (e.target === el) el.remove(); });
  setTimeout(() => el.remove(), GIVE_UP_MS);
}
