// A grid of every avatar or sleeve the skin has. close is called with the chosen index, or null when dismissed.

export function LookPicker({ title, count, urlOf, current, tall, close }: {
  title: string; count: number; urlOf: (index: number) => string; current: number; tall?: boolean;
  close: (chosen: number | null) => void;
}) {
  return (
    <div class="backdrop" onClick={e => { if (e.target === e.currentTarget) close(null); }}>
      <div class="dialog look-picker">
        <h3>{title}</h3>
        <div class={tall ? 'look-grid tall' : 'look-grid'}>
          {Array.from({ length: count }, (_, i) => (
            <button key={i} class={i === current ? 'look chosen' : 'look'} onClick={() => close(i)}>
              <img alt="" src={urlOf(i)} />
            </button>
          ))}
        </div>
        <button onClick={() => close(null)}>Cancel</button>
      </div>
    </div>
  );
}
