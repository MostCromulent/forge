// Reuses child elements by key, so a card keeps its element (and its CSS transitions) across renders
export function reconcile<T, E extends HTMLElement = HTMLElement>(
  parent: HTMLElement,
  items: readonly T[],
  keyOf: (item: T) => string | number,
  create: (item: T) => E,
  update: (el: E, item: T) => void,
): void {
  const existing = new Map([...parent.children].map(el => [(el as HTMLElement).dataset.key, el as E]));
  let prev: E | null = null;
  for (const item of items) {
    const key = String(keyOf(item));
    let el = existing.get(key);
    if (el) existing.delete(key);
    else {
      el = create(item);
      el.dataset.key = key;
    }
    update(el, item);
    const next: ChildNode | null = prev ? prev.nextSibling : parent.firstChild;
    if (el !== next) parent.insertBefore(el, next);
    prev = el;
  }
  for (const el of existing.values()) el.remove();
}
