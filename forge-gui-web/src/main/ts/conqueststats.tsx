// A conquest's statistics, with two tables mobile never shows: what is conquered by region, and each commander's record

import { useEffect } from 'preact/hooks';
import type { Actions } from './actions';
import type { Model } from './model';
import { t } from './text';

const amount = (n: number): string => n.toLocaleString('en-GB');

export function Stats({ model, actions }: { model: Model; actions: Actions }) {
  const s = model.conquestStats;
  // The plane the player stands on is the one first shown
  useEffect(() => { actions.conquestStats(model.conquestState?.plane); }, []);
  if (!s) return <p class="muted pools-wait">{t('lblWebConquestReading')}</p>;
  return (
    <div class="cq-stats">
      <div class="cq-stats-head">
        <div><span class="muted">{model.campaignBar?.name}</span><h2>{t('lblStatistics')}</h2></div>
        <span class="seg" role="group">
          <button aria-pressed={!s.plane} onClick={() => actions.conquestStats(undefined)}>{t('lblAllPlanes')}</button>
          {s.planes.map(p => <button key={p} aria-pressed={p === s.plane} onClick={() => actions.conquestStats(p)}>{p}</button>)}
        </span>
      </div>
      <div class="cq-figures">
        {s.figures.map(f => (
          <div key={f.label} class="cq-figure">
            <span class="cq-kicker">{f.label}</span>
            <p><b>{amount(f.amount)}</b>{f.of != null && <span> / {amount(f.of)} ({f.of ? Math.round(100 * f.amount / f.of) : 0}%)</span>}</p>
            {f.of != null && <div class="cq-pbar"><i style={{ width: `${f.of ? 100 * f.amount / f.of : 0}%` }} /></div>}
          </div>
        ))}
      </div>
      <div class="cq-tables">
        {s.regions.length > 0 && (
          <table>
            <thead><tr><th>{t('lblRegion')}</th><th>{t('lblConqueredEvents')}</th><th>{t('lblTotalWins')}</th><th>{t('lblTotalLosses')}</th></tr></thead>
            <tbody>
              {s.regions.map(r => <tr key={r.name}><td>{r.name}</td><td>{r.conquered} / {r.events}</td><td>{r.wins}</td><td>{r.losses}</td></tr>)}
            </tbody>
          </table>
        )}
        <table>
          <thead><tr><th>{t('lblCommanders')}</th><th>{t('lblTotalWins')}</th><th>{t('lblTotalLosses')}</th></tr></thead>
          <tbody>
            {s.commanders.map(c => <tr key={c.name}><td>{c.name}</td><td>{c.wins}</td><td>{c.losses}</td></tr>)}
          </tbody>
        </table>
      </div>
    </div>
  );
}
