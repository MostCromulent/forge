// A finger rested on something opens what a right-click or a hover opens for a mouse.

export const LONG_PRESS_MS = 500;
const MOVE_LIMIT_PX = 8;
/** How long after a finger lifts the browser's own context menu for that touch may still arrive. */
const AFTER_TOUCH_MS = 700;

let touching = false;
let touchEnded = -Infinity;
let swallowClick = false;

export const isTouch = (e: Event | undefined): boolean => (e as PointerEvent | undefined)?.pointerType === 'touch';

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
  // Android answers a long touch with a context menu, which would do the right-click's job on top of the press's own
  document.addEventListener('contextmenu', e => {
    if (touching || performance.now() - touchEnded < AFTER_TOUCH_MS) {
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
  const ring = document.createElement('div');
  ring.className = 'press-ring';
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
