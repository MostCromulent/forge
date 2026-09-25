// Messages from the server in the corner of the screen. An error stays until clicked; anything else goes by itself.

import type { Model } from './model';
import type { Notice } from './protocol';

let push: (notice: Notice, view?: () => void, label?: string) => void = () => {};

/** Lets a screen raise a notice of its own, such as a removal it offers to undo. The controller owns the list. */
export function initNotices(show: typeof push): void {
  push = show;
}

export function showNotice(notice: Notice, view?: () => void, label?: string): void {
  push(notice, view, label);
}

export function Notices({ model, dismiss }: { model: Model; dismiss: (id: number) => void }) {
  return <>{model.notices.map(({ id, notice, view, label }) => (
    <div key={id} class={notice.error ? 'notice error' : 'notice'} onClick={() => dismiss(id)}>
      <b>{notice.title ?? ''}</b>
      {notice.message && <div>{notice.message}</div>}
      {view && <button class="notice-view" onClick={view}>{label ?? 'View cards'}</button>}
    </div>
  ))}</>;
}
