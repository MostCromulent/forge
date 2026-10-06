// Small pieces of page behaviour that several pages share.

import { useEffect, useState } from 'preact/hooks';
import type { RefObject } from 'preact';

/** A value as it stood once it stopped changing: a long list costs a request per row, so a search waits for a pause in typing. */
export function useDebounced<T>(value: T, ms = 200): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(timer);
  }, [value]);
  return settled;
}

/** Closes a popup on Escape, or on a press outside the element it hangs from. */
export function useDismiss(open: boolean, root: RefObject<HTMLElement>, close: () => void): void {
  useEffect(() => {
    if (!open) return;
    const outside = (e: PointerEvent) => { if (!root.current?.contains(e.target as Node)) close(); };
    const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);
}

/** Closes a menu on any press outside the element that holds it, as the table's menus close. */
export function usePressOutside(open: boolean, inside: string, close: () => void): void {
  useEffect(() => {
    if (!open) return;
    const outside = (e: PointerEvent) => { if (!(e.target as Element).closest?.(inside)) close(); };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [open]);
}
