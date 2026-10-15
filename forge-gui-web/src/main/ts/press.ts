// A finger rested on something opens what a right-click or a hover opens for a mouse.

import { make } from './dom';

export const LONG_PRESS_MS = 500;
const MOVE_LIMIT_PX = 8;
/** How long after a finger lifts the browser's own context menu for that touch may still arrive. */
const AFTER_TOUCH_MS = 700;

let touching = false;
let touchEnded = -Infinity;
let swallowClick = false;

/** A pointer that can rest over something without pressing it. A phone has none, whatever events its browser makes up. */
export const hovers = (e: PointerEvent): boolean => e.pointerType === 'mouse' && matchMedia('(any-hover: hover)').matches;

/** A click says what made it on most browsers; where it does not, the press before it does. */
export const isTouch = (e: Event | undefined): boolean => {
  const type = (e as PointerEvent | undefined)?.pointerType;
  return type ? type === 'touch' : !!e && performance.now() - touchEnded < AFTER_TOUCH_MS;
};

export function initPress(): void {
  const lifted = (e: PointerEvent) => {
    if (e.pointerType !== 'touch') return;
    touching = false;
    touchEnded = performance.now();
  };
  document.addEventListener('pointerdown', e => {
    if (e.pointerType !== 'touch') return;
    touching = true;
    swallowClick = false;
  }, true);
  document.addEventListener('pointerup', lifted, true);
  document.addEventListener('pointercancel', lifted, true);
  // Android answers a long touch with its own context menu, which would do the right-click's job on top of the press's own
  document.addEventListener('contextmenu', e => {
    if (e.isTrusted && (touching || performance.now() - touchEnded < AFTER_TOUCH_MS)) {
      e.preventDefault();
      e.stopPropagation();
    }
  }, true);
  // The click that ends a press which opened something is not a click on what was pressed
  document.addEventListener('click', e => {
    if (swallowClick) {
      swallowClick = false;
      e.preventDefault();
      e.stopPropagation();
    }
  }, true);
}

/** Opens something after a finger rests on an element, with a ring filling while it waits. A touch that moves is a scroll or a drag. */
export function longPress(e: PointerEvent, open: (x: number, y: number) => void): void {
  if (e.pointerType !== 'touch') {
    return;
  }
  const x = e.clientX;
  const y = e.clientY;
  const ring = make('div', 'press-ring');
  ring.style.left = `${x - 20}px`;
  ring.style.top = `${y - 20}px`;
  document.body.append(ring);
  const timer = setTimeout(() => {
    cancel();
    swallowClick = true;
    navigator.vibrate?.(10);
    open(x, y);
  }, LONG_PRESS_MS);
  const moved = (ev: PointerEvent) => {
    if (Math.hypot(ev.clientX - x, ev.clientY - y) > MOVE_LIMIT_PX) cancel();
  };
  function cancel(): void {
    clearTimeout(timer);
    ring.remove();
    document.removeEventListener('pointermove', moved);
    document.removeEventListener('pointerup', cancel);
    document.removeEventListener('pointercancel', cancel);
  }
  document.addEventListener('pointermove', moved);
  document.addEventListener('pointerup', cancel);
  document.addEventListener('pointercancel', cancel);
}

/**
 * Follows a press that becomes a drag only once the pointer has moved threshold pixels: start is called then, and may answer
 * false to wait for the next move, move on each move from then on, and end when the press lifts or is cancelled. The click that
 * ends a drag is swallowed, so letting go over something does not also press it.
 */
export function trackDrag(e: PointerEvent, threshold: number, on: {
  start: (ev: PointerEvent) => boolean | void; move: (ev: PointerEvent) => void; end: (ev: PointerEvent, dragged: boolean) => void;
}): void {
  const x0 = e.clientX;
  const y0 = e.clientY;
  let dragging = false;
  const moved = (ev: PointerEvent) => {
    if (!dragging) {
      if (Math.hypot(ev.clientX - x0, ev.clientY - y0) < threshold || on.start(ev) === false) return;
      dragging = true;
    }
    on.move(ev);
  };
  const lifted = (ev: PointerEvent) => {
    document.removeEventListener('pointermove', moved);
    document.removeEventListener('pointerup', lifted);
    document.removeEventListener('pointercancel', lifted);
    if (dragging) {
      // A click comes in the same task as the lift, or not at all, so the next one is not eaten later
      const swallow = (c: Event) => { c.stopPropagation(); c.preventDefault(); };
      document.addEventListener('click', swallow, true);
      setTimeout(() => document.removeEventListener('click', swallow, true));
    }
    on.end(ev, dragging);
  };
  document.addEventListener('pointermove', moved);
  document.addEventListener('pointerup', lifted);
  document.addEventListener('pointercancel', lifted);
}
