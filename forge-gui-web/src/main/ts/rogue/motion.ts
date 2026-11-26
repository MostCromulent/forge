// Rogue Commander's screen motion, timed as the mobile client's: a new screen rises over the page, and closing it drops it away

import type { RefObject } from 'preact';
import { useLayoutEffect } from 'preact/hooks';
import { reducedMotion } from '../dom';

/** libgdx's pow3Out, which mobile eases its screens with. */
export const POW3_OUT = 'cubic-bezier(.215, .61, .355, 1)';
const SCREEN_MS = 500;
/** How dark the page under a moving screen gets, as mobile dims it to 55%. */
const DIMMED = 'brightness(.45)';

/**
 * Starts a view transition's mark for a move to a new screen, which rises over the page, or with back for the page dropping away.
 * Pass the result as eased's or easedLocal's mark. The mark is also set now, because the old page must be captured whole (rogue.css).
 */
export function screenWay(back: boolean): () => Record<string, string> {
  const way = back ? 'drop' : 'rise';
  if ('startViewTransition' in document && !reducedMotion()) document.documentElement.dataset.rogue = way;
  return () => ({ rogue: way });
}

/** How long a closed screen waits in place for the next one, which the server sends after the answer. */
const NEXT_WAIT_MS = 250;
// Set while an element that rose has just left, until the end of the task, so one that replaces it in the same draw shows without motion
let leaving = false;
let risen = 0;
// A copy of the screen that last closed, standing in its place until the next one arrives or the wait ends
let waiting: { frame: HTMLElement; timer: number } | null = null;

/**
 * Makes an element laid over its parent rise in when it mounts, dimming what is under it, and drop away when it unmounts.
 * One that replaces another, at once or within a short wait, shows without motion, as one page of a story follows another.
 * stays says the element went only for a while, such as for another tab, so it leaves without motion.
 */
export function useRise(ref: RefObject<HTMLElement>, stays: () => boolean): void {
  useLayoutEffect(() => {
    const el = ref.current!;
    risen++;
    const follows = leaving || waiting !== null;
    if (waiting) {
      clearTimeout(waiting.timer);
      waiting.frame.remove();
      waiting = null;
    }
    if (!follows && !reducedMotion()) {
      el.animate([{ transform: 'translateY(100%)' }, { transform: 'none' }], { duration: SCREEN_MS, easing: POW3_OUT });
      for (const under of el.parentElement!.children) {
        if (under !== el) under.animate([{ filter: 'none' }, { filter: DIMMED }], { duration: SCREEN_MS, easing: POW3_OUT });
      }
    }
    return () => {
      const box = el.getBoundingClientRect();
      const copy = el.cloneNode(true) as HTMLElement;
      const home = el.closest('.rg-shell') ?? document.body;
      const was = risen;
      leaving = true;
      queueMicrotask(() => {
        leaving = false;
        if (risen !== was || stays() || reducedMotion() || !home.isConnected) return;
        const frame = standIn(copy, box, home);
        waiting = { frame, timer: window.setTimeout(() => { waiting = null; dropAway(frame, copy); }, NEXT_WAIT_MS) };
      });
    };
  }, []);
}

/** A copy of a closed screen where it stood, which the page cannot press. */
function standIn(copy: HTMLElement, box: DOMRect, home: Element): HTMLElement {
  const frame = document.createElement('div');
  frame.className = 'rg-leaving';
  frame.setAttribute('aria-hidden', 'true');
  Object.assign(frame.style, { left: `${box.left}px`, top: `${box.top}px`, width: `${box.width}px`, height: `${box.height}px` });
  frame.append(copy);
  home.append(frame);
  return frame;
}

/** Drops the copy off the bottom of its frame, over the page it uncovers, which brightens from dimmed. */
function dropAway(frame: HTMLElement, copy: HTMLElement): void {
  if (!frame.isConnected) return;
  frame.animate([{ background: 'rgba(0, 0, 0, .55)' }, { background: 'transparent' }], { duration: SCREEN_MS, easing: POW3_OUT });
  copy.animate([{ transform: 'none' }, { transform: 'translateY(100%)' }], { duration: SCREEN_MS, easing: POW3_OUT, fill: 'forwards' })
    .finished.then(() => frame.remove(), () => frame.remove());
}
