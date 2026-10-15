// Messages from the server in the corner of the screen. An error stays until clicked; anything else goes by itself.

import { t } from './text';
import type { Model } from './model';
import type { Notice } from './protocol';

let model: Model | null = null;
let redraw = (): void => {};
let noticeId = 0;
const NOTICE_MS = 6000;
/** As long as a notice's way out (theme.css). */
const NOTICE_OUT_MS = 180;

/** Gives the notices the model they are kept in and the way to draw it again. */
export function initNotices(into: Model, schedule: () => void): void {
  model = into;
  redraw = schedule;
}

/** Shows a notice, from the server or from a screen, such as a removal it offers to undo. An error stays until dismissed; anything else goes by itself. */
export function showNotice(notice: Notice, view?: () => void, label?: string): void {
  if (!model) return;
  const id = ++noticeId;
  model.notices = [...model.notices, { id, notice, view, label }];
  if (!notice.error) setTimeout(() => dismissNotice(id), NOTICE_MS);
  redraw();
}

/** A notice slides out before it goes (theme.css); dismissed again on its way out, it goes at once. */
function dismissNotice(id: number): void {
  if (!model) return;
  const going = model.notices.find(n => n.id === id);
  if (going && !going.leaving && document.documentElement.dataset.motion !== 'reduced') {
    model.notices = model.notices.map(n => (n.id === id ? { ...n, leaving: true } : n));
    setTimeout(() => dismissNotice(id), NOTICE_OUT_MS);
  } else {
    model.notices = model.notices.filter(n => n.id !== id);
  }
  redraw();
}

export function Notices({ model }: { model: Model }) {
  return <>{model.notices.map(({ id, notice, view, label, leaving }) => (
    <div key={id} class={`notice${notice.error ? ' error' : ''}${leaving ? ' leaving' : ''}`} onClick={() => dismissNotice(id)}>
      <b>{notice.title ?? ''}</b>
      {notice.message && <div>{notice.message}</div>}
      {view && <button class="notice-view" onClick={view}>{label ?? t('lblWebNoticesViewCards')}</button>}
    </div>
  ))}</>;
}
