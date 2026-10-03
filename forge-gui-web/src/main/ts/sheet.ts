// What every sheet on a phone shares: Back closes it, a swipe down closes it, and opening one closes the others.

const open = new Map<string, () => void>();

export function initSheets(): void {
  addEventListener('popstate', () => {
    const last = [...open.keys()].pop();
    if (last === undefined) return;
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
    if (history.state?.sheet === id) history.back();
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
