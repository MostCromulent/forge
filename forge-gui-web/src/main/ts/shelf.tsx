// A shelf of saved things as cards, each with the one thing to do next, after a card that starts a new one

import type { ComponentChildren } from 'preact';
import { useState } from 'preact/hooks';
import { usePressOutside } from './hooks';
import { t } from './text';

export function Shelf<Row extends { name: string }>({ rows, create, cls, art, sub, mid, foot, rename, remove }: {
  rows: Row[];
  create: { title: string; line?: string; go: () => void };
  cls: (row: Row) => string;
  art?: (row: Row) => ComponentChildren;
  /** What a card says under its name and in its middle, and its buttons while it is asked nothing. */
  sub: (row: Row) => ComponentChildren; mid: (row: Row) => ComponentChildren; foot: (row: Row) => ComponentChildren;
  /** field names the name's input for a reader who cannot see it. */
  rename?: { field: string; go: (row: Row, to: string) => void };
  /** item is the menu's word for it, ask the question put before it is done, and keep the answer that leaves it. */
  remove: { item: string; ask: (row: Row) => string; keep: string; go: (row: Row) => void };
}) {
  const [menu, setMenu] = useState<string | null>(null);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  usePressOutside(menu !== null, '.ev-more', () => setMenu(null));
  return (
    <div class="event-shelf">
      <button class="ev new" onClick={create.go}>
        <span class="plus" aria-hidden="true">+</span>
        <b>{create.title}</b>
        {create.line && <span>{create.line}</span>}
      </button>
      {rows.map(r => (
        <article key={r.name} class={cls(r)}>
          {art?.(r)}
          <div class="ev-top">
            {rename && renaming === r.name
              ? (
                <form class="cq-rename" onSubmit={e => {
                  e.preventDefault();
                  const to = new FormData(e.currentTarget).get('name')?.toString().trim();
                  setRenaming(null);
                  if (to && to !== r.name) rename.go(r, to);
                }}>
                  <input name="name" defaultValue={r.name} maxLength={60} aria-label={rename.field} autoFocus
                    onKeyDown={e => { if (e.key === 'Escape') setRenaming(null); }} />
                  <button class="primary" type="submit">{t('lblRename')}</button>
                  <button type="button" onClick={() => setRenaming(null)}>{t('lblCancel')}</button>
                </form>
              )
              : <b>{r.name}</b>}
            {sub(r)}
          </div>
          <div class="ev-mid">{mid(r)}</div>
          <div class="ev-foot">
            {deleting === r.name
              ? <>
                  <span class="ev-ask">{remove.ask(r)}</span>
                  <span class="sp" />
                  <button onClick={() => setDeleting(null)}>{remove.keep}</button>
                  <button class="danger" onClick={() => { setDeleting(null); remove.go(r); }}>{t('lblDelete')}</button>
                </>
              : <>
                  {foot(r)}
                  <span class="sp" />
                  <span class="ev-more">
                    <button class="more" title={t('lblWebLimitedMore')} aria-label={t('lblWebLimitedMoreFor', r.name)} aria-expanded={menu === r.name}
                      onClick={() => setMenu(menu === r.name ? null : r.name)}>⋯</button>
                    {menu === r.name && (
                      <div class="ev-menu" role="menu">
                        {rename && <button role="menuitem" class="plain" onClick={() => { setMenu(null); setRenaming(r.name); }}>{t('lblRename')}</button>}
                        <button role="menuitem" onClick={() => { setMenu(null); setDeleting(r.name); }}>{remove.item}</button>
                      </div>
                    )}
                  </span>
                </>}
          </div>
        </article>
      ))}
    </div>
  );
}
