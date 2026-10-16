// Helpers for markup drawn by hand: lookups that treat a miss as a bug and say which, building, pointer and motion helpers, re-placing after a layout change, and a file to save

export function q<T extends HTMLElement = HTMLElement>(root: ParentNode, selector: string): T {
  const el = root.querySelector<T>(selector);
  if (!el) throw new Error(`Missing ${selector}`);
  return el;
}

export function byId<T extends HTMLElement = HTMLElement>(id: string): T {
  const el = document.getElementById(id);
  if (!el) throw new Error(`Missing #${id}`);
  return el as T;
}

/** A new element of one class list, which is how nearly every element the page builds by hand starts out. */
export function make<K extends keyof HTMLElementTagNameMap>(tag: K, className: string): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  el.className = className;
  return el;
}

/** Whether the player has asked for less motion; the Motion option decides, and follows the system only when set to. */
export const reducedMotion = (): boolean => document.documentElement.dataset.motion === 'reduced';

/** How far, in degrees, a card preview leans with the pointer at the edge of what it is a preview of. */
const MAX_LEAN = 3;

/** Where the pointer is over el, x and y each running from -0.5 to 0.5 across it. */
export function pointerOn(el: Element, e: PointerEvent): { x: number; y: number } {
  const box = el.getBoundingClientRect();
  return {
    x: Math.max(-0.5, Math.min(0.5, (e.clientX - box.left) / box.width - 0.5)),
    y: Math.max(-0.5, Math.min(0.5, (e.clientY - box.top) / box.height - 0.5)),
  };
}

/** Leans every card preview toward the pointer's place on what it is a preview of (theme.css). */
export function lean({ x, y }: { x: number; y: number }): void {
  const root = document.documentElement.style;
  root.setProperty('--lean-x', `${(-y * MAX_LEAN * 2).toFixed(2)}deg`);
  root.setProperty('--lean-y', `${(x * MAX_LEAN * 2).toFixed(2)}deg`);
}

/** Plays a class's CSS animation again from its start, first taking off the classes it replaces. */
export function replay(el: HTMLElement, add: string, ...remove: string[]): void {
  el.classList.remove(add, ...remove);
  void el.offsetWidth;
  el.classList.add(add);
}

/** Places el gap away from a point, down and to the right, turned back where it would leave the window. */
export function placeBeside(el: HTMLElement, x: number, y: number, gap: number): void {
  const left = x + gap + el.offsetWidth > innerWidth - 8 ? x - gap - el.offsetWidth : x + gap;
  el.style.left = `${Math.round(Math.max(8, left))}px`;
  el.style.top = `${Math.round(Math.max(8, Math.min(y + gap, innerHeight - el.offsetHeight - 8)))}px`;
}

const relayouts: (() => void)[] = [];

/** Runs place when the window resizes, and when relayout says the board moved without it. */
export function onRelayout(place: () => void): void {
  relayouts.push(place);
  window.addEventListener('resize', place);
}

/** Places again whatever is placed by measuring the board, after the board changed size while the window did not. */
export function relayout(): void {
  for (const place of relayouts) place();
}

export function saveText(text: string, fileName: string, type: string): void {
  const link = document.createElement('a');
  link.href = URL.createObjectURL(new Blob([text], { type }));
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(link.href);
}

/**
 * Calls away for every press outside the elements matching inside, for a panel drawn by hand to close itself (hooks.ts's
 * useDismiss is the same for a component). On pointerdown, unless the press may choose something outside inside first, as a click.
 */
export function onPressOutside(inside: string, away: () => void, on: 'pointerdown' | 'click' = 'pointerdown'): void {
  document.addEventListener(on, e => {
    if (!(e.target instanceof Element && e.target.closest(inside))) away();
  });
}
