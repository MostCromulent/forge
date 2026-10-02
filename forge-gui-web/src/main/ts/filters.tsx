// One filter bar for the deck finder and the editor's catalogue, where a chip the table or the deck sets is gold and says its source

import { useEffect, useRef, useState } from 'preact/hooks';
import type { ComponentChildren } from 'preact';
import { t } from './text';

export interface FilterKind<F> {
  id: string;
  /** The heading it is listed under in the menu. */
  group: string;
  label: string;
  /** The words on its chip while it is in use, or null while it is not. */
  chip: (f: F) => string | null;
  /** Where a chip set by something other than the player comes from, which gilds it. Empty gilds it and names nothing. */
  from?: (f: F) => string | null;
  /** A filter the table fixes has a chip but no ×. */
  fixed?: (f: F) => boolean;
  clear: (f: F) => F;
  /** Its panel. done closes the menu, for a choice that needs nothing more. */
  panel: (f: F, set: (next: F) => void, done: () => void) => ComponentChildren;
}

export function FilterBar<F>({ kinds, filter, set, clearAll, narrowed, children }: {
  kinds: FilterKind<F>[]; filter: F; set: (next: F) => void; clearAll: () => void; narrowed: boolean; children?: ComponentChildren;
}) {
  const [open, setOpen] = useState<{ kind: string | null } | null>(null);
  const [find, setFind] = useState('');
  const root = useRef<HTMLDivElement>(null);
  // A press anywhere else, or Escape, puts the menu away
  useEffect(() => {
    if (!open) return;
    const away = (e: Event) => { if (!root.current?.contains(e.target as Node)) setOpen(null); };
    const key = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      e.stopPropagation();
      setOpen(null);
    };
    document.addEventListener('pointerdown', away, true);
    document.addEventListener('keydown', key, true);
    return () => {
      document.removeEventListener('pointerdown', away, true);
      document.removeEventListener('keydown', key, true);
    };
  }, [open]);
  useEffect(() => setFind(''), [open === null]);
  const inUse = kinds.filter(k => k.chip(filter) !== null);
  const shown = kinds.filter(k => k.label.toLowerCase().includes(find.trim().toLowerCase()));
  const groups = [...new Set(shown.map(k => k.group))];
  const kind = kinds.find(k => k.id === open?.kind);
  return (
    <div class="fbar" ref={root}>
      {children}
      {inUse.map(k => {
        const from = k.from?.(filter) ?? null;
        return (
          <span key={k.id} class={from !== null ? 'chip set-by' : 'chip'}>
            <button class="chip-body" onClick={() => setOpen({ kind: k.id })}>
              <span class="k">{from ? `${from} · ${k.label}` : k.label}</span><b>{k.chip(filter)}</b>
            </button>
            {!k.fixed?.(filter) && (
              <button class="x" aria-label={t('lblWebFilterRemove', k.label)} onClick={() => set(k.clear(filter))}>×</button>
            )}
          </span>
        );
      })}
      <span class="add-wrap">
        <button class={open ? 'add open' : 'add'} aria-expanded={!!open} onClick={() => setOpen(open ? null : { kind: null })}>
          {t('lblWebFilterAdd')}
        </button>
        {open && (
          <div class="filter-menu">
            <div class="mlist" role="menu">
              <input class="mfind" type="search" placeholder={t('lblWebFilterFind')} value={find} autoFocus
                onInput={e => setFind(e.currentTarget.value)} />
              {groups.map(g => (
                <div key={g} class="mgroup">
                  <span class="mhead">{g}</span>
                  {shown.filter(k => k.group === g).map(k => (
                    <button key={k.id} role="menuitem" class={`mi${k.id === open.kind ? ' on' : ''}${k.chip(filter) !== null ? ' used' : ''}`}
                      onClick={() => setOpen({ kind: k.id })}>
                      <span>{k.label}</span><span class="mi-note">{k.chip(filter) !== null ? t('lblWebFilterInUse') : '›'}</span>
                    </button>
                  ))}
                </div>
              ))}
              {!shown.length && <p class="mnone">{t('lblWebFilterNoneFound')}</p>}
            </div>
            {kind && (
              <div class="fpanel" key={kind.id}>
                <h5>{kind.label}</h5>
                {kind.panel(filter, set, () => setOpen(null))}
              </div>
            )}
          </div>
        )}
      </span>
      {narrowed && <button class="clear-all" onClick={clearAll}>{t('lblWebEditorClearFilters')}</button>}
    </div>
  );
}

/** A panel of options of which one is chosen, which applies at once. */
export function OneOf<V extends string | number>({ options, value, pick }: {
  options: readonly (readonly [V, string])[]; value: V | null; pick: (v: V) => void;
}) {
  return (
    <div class="fopts" role="radiogroup">
      {options.map(([v, name]) => (
        <button key={String(v)} role="radio" aria-checked={v === value} class={v === value ? 'fopt on' : 'fopt'} onClick={() => pick(v)}>
          <i class="dot" />{name}
        </button>
      ))}
    </div>
  );
}

/** A panel of options of which any are ticked; Add applies them. */
export function AnyOf<V extends string>({ options, value, apply }: {
  options: readonly (readonly [V, string])[]; value: readonly V[]; apply: (v: V[]) => void;
}) {
  const [ticked, setTicked] = useState<Set<V>>(() => new Set(value));
  return (
    <>
      <div class="fopts">
        {options.map(([v, name]) => (
          <button key={v} role="checkbox" aria-checked={ticked.has(v)} class={ticked.has(v) ? 'fopt box on' : 'fopt box'} onClick={() => {
            const next = new Set(ticked);
            if (!next.delete(v)) next.add(v);
            setTicked(next);
          }}><i class="dot" />{name}</button>
        ))}
      </div>
      <div class="fapply"><button class="primary" onClick={() => apply([...ticked])}>{t('lblWebFilterApply')}</button></div>
    </>
  );
}

/** A panel for a range of numbers, either end left empty for no limit. */
export function Between({ from, to, apply, min = 0 }: {
  from: number | null; to: number | null; apply: (from: number | null, to: number | null) => void; min?: number;
}) {
  const [lo, setLo] = useState(from === null ? '' : String(from));
  const [hi, setHi] = useState(to === null ? '' : String(to));
  const num = (s: string) => (s.trim() === '' ? null : Math.max(min, Number(s)));
  return (
    <>
      <div class="frange">
        <input type="number" min={min} placeholder={t('lblWebFilterFrom')} value={lo} onInput={e => setLo(e.currentTarget.value)} />
        <span>–</span>
        <input type="number" min={min} placeholder={t('lblWebFilterTo')} value={hi} onInput={e => setHi(e.currentTarget.value)} />
      </div>
      <div class="fapply"><button class="primary" onClick={() => apply(num(lo), num(hi))}>{t('lblWebFilterApply')}</button></div>
    </>
  );
}

/** A panel for words: a box, and Apply. With names, the box offers those matching what typing has sent. */
export function Words({ value, placeholder, apply, names, typing }: {
  value: string; placeholder: string; apply: (words: string) => void; names?: readonly string[]; typing?: (words: string) => void;
}) {
  const [text, setText] = useState(value);
  return (
    <form class="fwords" onSubmit={e => {
      e.preventDefault();
      if (text.trim()) apply(text.trim());
    }}>
      <input type="search" autoFocus placeholder={placeholder} value={text} list={names ? 'filter-names' : undefined} onInput={e => {
        setText(e.currentTarget.value);
        typing?.(e.currentTarget.value);
      }} />
      {names && <datalist id="filter-names">{names.map(n => <option key={n} value={n} />)}</datalist>}
      <div class="fapply"><button class="primary" type="submit" disabled={!text.trim()}>{t('lblWebFilterApply')}</button></div>
    </form>
  );
}

/** A range as a chip says it: "2–4", "≥ 2", "≤ 4". */
export function rangeWords(from: number | null, to: number | null): string | null {
  if (from === null && to === null) return null;
  if (from !== null && to !== null) return from === to ? String(from) : `${from}–${to}`;
  return from !== null ? `≥ ${from}` : `≤ ${to}`;
}
