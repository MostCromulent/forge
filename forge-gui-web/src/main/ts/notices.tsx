// Messages from the server in the corner of the screen. An error stays until clicked; anything else goes by itself.

import type { Model } from './model';

export function Notices({ model, dismiss }: { model: Model; dismiss: (id: number) => void }) {
  return <>{model.notices.map(({ id, notice }) => (
    <div key={id} class={notice.error ? 'notice error' : 'notice'} onClick={() => dismiss(id)}>
      <b>{notice.title ?? ''}</b>
      <div>{notice.message ?? ''}</div>
    </div>
  ))}</>;
}
