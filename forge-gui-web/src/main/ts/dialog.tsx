// The page's popups: the dimmed layer every dialog sits on, the two-button question most of them are, and the panel a button drops

import type { ComponentChildren, JSX } from 'preact';
import { useRef, useState } from 'preact/hooks';
import { useDismiss } from './hooks';

/** The layer under a dialog, of class layer and cls. A press that starts on the layer dismisses it; one that starts in the dialog does not, though it end outside, as a text selection does. */
export function Backdrop({ layer = 'backdrop', cls, dismiss, children, ...rest }: {
  layer?: string; cls?: string; dismiss?: () => void; children: ComponentChildren;
} & Omit<JSX.HTMLAttributes<HTMLDivElement>, 'class' | 'onMouseDown'>) {
  return (
    <div {...rest} class={cls ? `${layer} ${cls}` : layer} onMouseDown={dismiss && (e => { if (e.target === e.currentTarget) dismiss(); })}>
      {children}
    </div>
  );
}

/** A question with a way back, which a press outside also takes, and a way on, marked danger when it loses something. */
export function Confirm({ title, label, text, back, backLabel, go, goLabel, danger, cls }: {
  title: string; label?: string; text: string; back: () => void; backLabel: string; go: () => void; goLabel: string; danger?: boolean; cls?: string;
}) {
  return (
    <Backdrop cls={cls} dismiss={back}>
      <div class="dialog" role="alertdialog" aria-label={label ?? title}>
        <h3>{title}</h3>
        <p class="hint">{text}</p>
        <div class="actions">
          <button onClick={back}>{backLabel}</button>
          <button class={danger ? 'danger' : 'primary'} onClick={go}>{goLabel}</button>
        </div>
      </div>
    </Backdrop>
  );
}

/** A button that drops a panel, which Escape or a press outside closes. button draws the button from whether it is open and a way to toggle it; children draw the panel, given a way to close it. block wraps them in a div rather than a span. */
export function Dropdown({ cls, block, button, children }: {
  cls: string; block?: boolean; button: (open: boolean, toggle: () => void) => ComponentChildren; children: (close: () => void) => ComponentChildren;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLElement>(null);
  useDismiss(open, root, () => setOpen(false));
  const Wrap = block ? 'div' : 'span';
  return (
    <Wrap ref={root as never} class={cls}>
      {button(open, () => setOpen(!open))}
      {open && children(() => setOpen(false))}
    </Wrap>
  );
}
