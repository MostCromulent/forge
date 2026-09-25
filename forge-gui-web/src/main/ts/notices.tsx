// Messages from the server in the corner of the screen. An error stays until clicked; anything else goes by itself.

import type { Model } from './model';

export function Notices({ model, dismiss }: { model: Model; dismiss: (id: number) => void }) {
  return <>{model.notices.map(({ id, notice, view }) => (
    <div key={id} class={notice.error ? 'notice error' : 'notice'} onClick={() => dismiss(id)}>
      <b>{notice.title ?? ''}</b>
      {notice.message && <div>{notice.message}</div>}
      {view && <button class="notice-view" onClick={view}>View cards</button>}
    </div>
  ))}</>;
}
