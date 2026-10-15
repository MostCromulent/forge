// A shelf of saved things as cards, each with the one thing to do next, after a card that starts a new one

import type { ComponentChildren } from 'preact';
import { useState } from 'preact/hooks';
import { Dropdown } from './dialog';
import { t } from './text';

export function Shelf<Row extends { name: string }>({ rows, create, cls, art, sub, mid, foot, rename, remove }: {
  rows: Row[];
  create?: { title: string; line?: string; go: () => void };
  cls: (row: Row) => string;
  art?: (row: Row) => ComponentChildren;
  /** What a card says under its name and in its middle, and its buttons while it is asked nothing. */
  sub: (row: Row) => ComponentChildren; mid: (row: Row) => ComponentChildren; foot: (row: Row) => ComponentChildren;
  /** field names the name's input for a reader who cannot see it. */
  rename?: { field: string; go: (row: Row, to: string) => void };
  /** item is the menu's word for it, ask the question put before it is done, and keep the answer that leaves it. */
  remove?: { item: string; ask: (row: Row) => string; keep: string; go: (row: Row) => void };
}) {
  const [renaming, setRenaming] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  return (
    <div class="event-shelf">
      {create && (
        <button class="ev new" onClick={create.go}>
          <span class="plus" aria-hidden="true">+</span>
          <b>{create.title}</b>
          {create.line && <span>{create.line}</span>}
        </button>
      )}
      {rows.map(r => (
        <article key={r.name} class={cls(r)}>
          {art?.(r)}
          <div class="ev-top">
            {rename && renaming === r.name
              ? (
                <NameForm cls="cq-rename" initial={r.name} label={rename.field} submit={t('lblRename')} cancel={() => setRenaming(null)}
                  done={to => {
                    setRenaming(null);
                    if (to !== r.name) rename.go(r, to);
                  }} />
              )
              : <b>{r.name}</b>}
            {sub(r)}
          </div>
          <div class="ev-mid">{mid(r)}</div>
          <div class="ev-foot">
            {remove && deleting === r.name
              ? <>
                  <span class="ev-ask">{remove.ask(r)}</span>
                  <span class="sp" />
                  <button onClick={() => setDeleting(null)}>{remove.keep}</button>
                  <button class="danger" onClick={() => { setDeleting(null); remove.go(r); }}>{t('lblDelete')}</button>
                </>
              : <>
                  {foot(r)}
                  <span class="sp" />
                  {/* Without a way to remove a save there is nothing for its menu to hold */}
                  {remove && <Dropdown cls="ev-more" button={(open, toggle) => (
                    <button class="more" title={t('lblWebLimitedMore')} aria-label={t('lblWebLimitedMoreFor', r.name)} aria-expanded={open}
                      onClick={toggle}>⋯</button>
                  )}>
                    {close => (
                      <div class="ev-menu" role="menu">
                        {rename && <button role="menuitem" class="plain" onClick={() => { close(); setRenaming(r.name); }}>{t('lblRename')}</button>}
                        <button role="menuitem" onClick={() => { close(); setDeleting(r.name); }}>{remove.item}</button>
                      </div>
                    )}
                  </Dropdown>}
                </>}
          </div>
        </article>
      ))}
    </div>
  );
}

/** A name typed in: submitted, a name that is not blank goes to done, and a blank one, Escape or Cancel goes to cancel. */
export function NameForm({ cls, initial, label, placeholder, submit, done, cancel }: {
  cls: string; initial: string; label: string; placeholder?: string; submit: string; done: (name: string) => void; cancel: () => void;
}) {
  return (
    <form class={cls} onSubmit={e => {
      e.preventDefault();
      const name = new FormData(e.currentTarget).get('name')?.toString().trim();
      if (name) done(name);
      else cancel();
    }}>
      <input name="name" defaultValue={initial} maxLength={60} placeholder={placeholder} aria-label={label} autoFocus
        onKeyDown={e => { if (e.key === 'Escape') cancel(); }} />
      <button class="primary" type="submit">{submit}</button>
      <button type="button" onClick={cancel}>{t('lblCancel')}</button>
    </form>
  );
}
