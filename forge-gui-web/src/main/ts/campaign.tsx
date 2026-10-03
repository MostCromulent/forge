// What every campaign mode's pages share: the bar over them, a balance of one of its currencies, its statistics and its preferences

import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { skinIconUrl } from './images';
import type { Actions } from './actions';
import type { Model } from './model';
import type { CampaignBar as Bar } from './protocol';
import { t, type TextKey } from './text';

/** A balance: its icon, its number, and its name for a reader that cannot see the icon. */
export function Purse({ icon, n, label }: { icon: string; n: number; label: string }) {
  return <span class="cq-coin" title={label}><img alt={label} src={skinIconUrl(icon)} /><b>{n.toLocaleString('en-GB')}</b></span>;
}

/** How much of a currency the bar says the player has, by the currency's icon. */
export const balance = (bar: Bar | null, icon: string): number => bar?.balances.find(b => b.icon === icon)?.amount ?? 0;

/** The bar every page of a campaign shares: its name and a line, the mode's tabs, and the balances. */
export function CampaignBar<Tab extends string>({ bar, tabs, tab, setTab, held, extra, prefs, under }: {
  bar: Bar; tabs: [Tab, TextKey][]; tab: Tab; setTab: (tab: Tab) => void;
  /** What a reward being revealed has yet to show, by balance icon: the server's balances already hold it all, so the bar shows less. */
  held: Record<string, number>;
  extra?: ComponentChildren; prefs: () => void; under: boolean;
}) {
  return (
    <div class="cq-bar" inert={under}>
      <div class="cq-id"><b>{bar.name}</b><span>{bar.line}</span></div>
      <nav class="cq-tabs">
        {tabs.map(([id, name]) => (
          <button key={id} class="cq-tab" aria-current={id === tab ? 'page' : undefined} onClick={() => setTab(id)}>
            {t(name)}{id === tab && <span class="cq-tab-line" />}
          </button>
        ))}
      </nav>
      <div class="cq-purse">
        {extra}
        {bar.balances.map(b => <Purse key={b.icon} icon={b.icon} n={b.amount - (held[b.icon] ?? 0)} label={b.label} />)}
        <button onClick={prefs}>{t('lblWebCampaignPreferences')}</button>
      </div>
    </div>
  );
}

const amount = (n: number): string => n.toLocaleString('en-GB');

/** A campaign's statistics: figures, and whatever tables the mode sends. first is the scope asked for as the page opens. */
export function Stats({ model, actions, first }: { model: Model; actions: Actions; first?: string }) {
  const s = model.campaignStats;
  useEffect(() => { actions.campaignStats(first); }, []);
  if (!s) return <p class="muted pools-wait">{t('lblLoadingEllipsis')}</p>;
  return (
    <div class="cq-stats">
      <div class="cq-stats-head">
        <div><span class="muted">{model.campaignBar?.name}</span><h2>{s.title}</h2></div>
        {s.scopes.length > 0 && (
          <span class="seg" role="group">
            {s.allScopes && <button aria-pressed={!s.scope} onClick={() => actions.campaignStats(undefined)}>{s.allScopes}</button>}
            {s.scopes.map(p => <button key={p} aria-pressed={p === s.scope} onClick={() => actions.campaignStats(p)}>{p}</button>)}
          </span>
        )}
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
        {s.tables.map(table => (
          <table key={table.headers[0]}>
            <thead><tr>{table.headers.map(h => <th key={h}>{h}</th>)}</tr></thead>
            <tbody>
              {table.rows.map(row => <tr key={row[0]}>{row.map((cell, i) => <td key={i}>{cell}</td>)}</tr>)}
            </tbody>
          </table>
        ))}
      </div>
    </div>
  );
}

/** A mode's preferences, each saved as it is changed, since the server checks one against the others as they stand. */
export function Prefs({ model, actions, close }: { model: Model; actions: Actions; close: () => void }) {
  const p = model.campaignPrefs;
  /** What has been typed and not yet accepted, by key. A refused value stays in its field for the player to mend. */
  const [typed, setTyped] = useState<Record<string, string>>({});
  const [last, setLast] = useState<string | null>(null);
  useEffect(() => { actions.campaignPrefs(); }, []);
  // An accepted value leaves its own field to the server's; whatever is being typed in another stays
  useEffect(() => {
    if (p && !p.problem && last) setTyped(({ [last]: _, ...rest }) => rest);
  }, [p]);
  if (!p) return null;
  const groups = [...new Set(p.rows.map(r => r.group))];
  // The field's own text, since a blur can come before the typing has been drawn
  const save = (key: string, text: string, was: string) => {
    // What a value may be is the mode's to say, and its refusal comes back as the problem line
    if (text.trim() === '' || Number(text) === Number(was)) return;
    setLast(key);
    actions.campaignPref(key, text.trim());
  };
  return (
    <div class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
      <div class="dialog cq-prefs">
        <header><h3>{p.title}</h3><button aria-label={t('lblClose')} onClick={close}>×</button></header>
        <div class="cq-prefs-body">
          {groups.map(g => (
            <section key={g}>
              <h4>{g}</h4>
              {p.rows.filter(r => r.group === g).map(r => (
                <label key={r.key} class={p.problem && last === r.key ? 'cq-pref bad' : 'cq-pref'}>
                  <span>{r.label}</span>
                  <input type="number" min={0} step={1} value={typed[r.key] ?? r.value}
                    onInput={e => setTyped({ ...typed, [r.key]: e.currentTarget.value })}
                    onBlur={e => save(r.key, e.currentTarget.value, r.value)}
                    onKeyDown={e => { if (e.key === 'Enter') save(r.key, e.currentTarget.value, r.value); }} />
                </label>
              ))}
            </section>
          ))}
        </div>
        <footer>
          <span class={p.problem ? 'cq-warn' : 'muted'}>{p.problem ?? p.note}</span>
          {p.canReset && <button onClick={() => { setTyped({}); setLast(null); actions.campaignPrefsReset(); }}>{t('lblReset')}</button>}
          <button class="primary" onClick={close}>{t('lblOK')}</button>
        </footer>
      </div>
    </div>
  );
}
