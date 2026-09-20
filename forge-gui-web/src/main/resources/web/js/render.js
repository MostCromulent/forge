// Reuses child elements by key, so a card keeps its element (and its CSS transitions) across renders
export function reconcile(parent, items, keyOf, create, update) {
  const existing = new Map([...parent.children].map(el => [el.dataset.key, el]));
  let prev = null;
  for (const item of items) {
    const key = String(keyOf(item));
    let el = existing.get(key);
    if (el) existing.delete(key);
    else {
      el = create(item);
      el.dataset.key = key;
    }
    update(el, item);
    const next = prev ? prev.nextSibling : parent.firstChild;
    if (el !== next) parent.insertBefore(el, next);
    prev = el;
  }
  for (const el of existing.values()) el.remove();
}
