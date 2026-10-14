// Lookups for markup a module built itself, where a looked-up element is always there, so a miss is a bug and says which

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

/** Hands the player a file to save, holding text. */
export function saveText(text: string, fileName: string, type: string): void {
  const link = document.createElement('a');
  link.href = URL.createObjectURL(new Blob([text], { type }));
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(link.href);
}
