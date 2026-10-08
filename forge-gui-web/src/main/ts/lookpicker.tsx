// A grid of every avatar the skin has. close is called with the chosen index, or null when dismissed. Those in taken cannot be chosen.

import { t } from './text';

export function LookPicker({ title, count, urlOf, current, taken = [], close }: {
  title: string; count: number; urlOf: (index: number) => string; current: number; taken?: readonly number[];
  close: (chosen: number | null) => void;
}) {
  return (
    <div class="backdrop" onClick={e => { if (e.target === e.currentTarget) close(null); }}>
      <div class="dialog look-picker">
        <h3>{title}</h3>
        <div class="look-grid">
          {Array.from({ length: count }, (_, i) => (
            <button key={i} class={i === current ? 'look chosen' : 'look'} disabled={taken.includes(i)} onClick={() => close(i)}>
              <img alt="" src={urlOf(i)} />
            </button>
          ))}
        </div>
        <button onClick={() => close(null)}>{t('lblCancel')}</button>
      </div>
    </div>
  );
}
