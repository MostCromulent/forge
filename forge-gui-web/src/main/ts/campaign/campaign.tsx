// What every campaign mode shares: the page that holds its shelf of saves or an open save, the bar over an open one's pages, a balance of one
// of its currencies, its statistics, its preferences and the reveal of a reward

import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { imageUrl, skinIconUrl } from '../images';
import { artUrl } from '../sleeves';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { CampaignBar as Bar, PrefRow } from '../protocol';
import { t, formatNumber, type TextKey } from '../text';
import { HeadControls, PageHeader, SetupHead, WAY_NAMES } from '../header';
import { changeUi, eased, type UiState } from '../ui';
import { Reveal, type Owed } from './reward';
import { Backdrop } from '../dialog';

/** A balance: its icon, its number, and its name for a reader that cannot see the icon. */
export function Purse({ icon, n, label }: { icon: string; n: number; label: string }) {
  return <span class="cq-coin" title={label}><img alt={label} src={skinIconUrl(icon)} /><b>{formatNumber(n)}</b></span>;
}

/** How much of a currency the bar says the player has, by the currency's icon. */
export const balance = (bar: Bar | null, icon: string): number => bar?.balances.find(b => b.icon === icon)?.amount ?? 0;

/** The bar every page of a campaign shares: its name and a line, the mode's tabs, and the balances. */
export function CampaignBar<Tab extends string>({ bar, tabs, tab, setTab, held, extra, prefs, under }: {
  bar: Bar; tabs: [Tab, TextKey][]; tab: Tab; setTab: (tab: Tab) => void;
  /** What a reward being revealed has yet to show, by balance icon: the server's balances already hold it all, so the bar shows less. */
  held: Record<string, number>;
  extra?: ComponentChildren; prefs?: () => void; under: boolean;
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
        {prefs && <button onClick={prefs}>{t('lblWebCampaignPreferences')}</button>}
      </div>
    </div>
  );
}

/** The panel beside a page's list: what is chosen, its picture, what it says, and under it what the player brings. A picture pressed is shown large. */
export function SidePanel({ kicker, where, title, art, label, zoom, setZoom, children, foot }: {
  kicker: string; where?: ComponentChildren; title?: string; art?: string | null; label?: string;
  zoom: string | null; setZoom: (image: string | null) => void; children: ComponentChildren; foot: ComponentChildren;
}) {
  return (
    <aside class="cq-side">
      <div class="cq-panel">
        <div class="cq-where"><span class="cq-kicker">{kicker}</span>{where}</div>
        <h3>{title}</h3>
        <button class="cq-art" disabled={!art} style={{ backgroundImage: art ? `url("${artUrl(art)}")` : undefined }}
          onClick={() => setZoom(art ?? null)}>
          <b>{label}</b>
        </button>
        {children}
      </div>
      <div class="cq-foot">{foot}</div>
      {zoom && <div class="backdrop" onClick={() => setZoom(null)}><img class="cq-zoom" alt="" src={imageUrl(zoom)} /></div>}
    </aside>
  );
}

/** The deck a match is fought with, and the way to another. */
export function DeckRow({ image, name, line, problem, zoom, change }: {
  image?: string | null; name: string; line: string; problem: boolean; zoom: (image: string) => void; change?: () => void;
}) {
  return (
    <div class="cq-mine">
      {image && <button class="cq-face2" aria-label={name} style={{ backgroundImage: `url("${artUrl(image)}")` }} onClick={() => zoom(image)} />}
      <div class="t">
        <b>{name}</b>
        {problem ? <span class="cq-warn">{t('lblInvalidDeck')}</span> : <span>{line}</span>}
      </div>
      {change && <button onClick={change}>{t('lblWebMenuChange')}</button>}
    </div>
  );
}

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
            <p><b>{formatNumber(f.amount)}</b>{f.of != null && <span> / {formatNumber(f.of)} ({f.of ? Math.round(100 * f.amount / f.of) : 0}%)</span>}</p>
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
  // A group drawn as a table spans the dialog, so it comes after the groups that share its two columns
  const tabled = (g: string) => p.rows.some(r => r.group === g && r.column);
  const named = [...new Set(p.rows.map(r => r.group))];
  const groups = [...named.filter(g => !tabled(g)), ...named.filter(tabled)];
  // The field's own text, since a blur can come before the typing has been drawn
  const save = (key: string, text: string, was: string) => {
    // What a value may be is the mode's to say, and its refusal comes back as the problem line
    if (text.trim() === '' || Number(text) === Number(was)) return;
    setLast(key);
    actions.campaignPref(key, text.trim());
  };
  // A value written as a decimal may be stepped as one; whether it may be one is still the mode's to say
  const field = (r: PrefRow, label?: string) => (
    <input type="number" min={0} step={r.value.includes('.') ? 'any' : 1} value={typed[r.key] ?? r.value} aria-label={label}
      onInput={e => setTyped({ ...typed, [r.key]: e.currentTarget.value })}
      onBlur={e => save(r.key, e.currentTarget.value, r.value)}
      onKeyDown={e => { if (e.key === 'Enter') save(r.key, e.currentTarget.value, r.value); }} />
  );
  const bad = (r: PrefRow) => !!p.problem && last === r.key;
  return (
    <Backdrop dismiss={close}>
      <div class="dialog cq-prefs">
        <header><h3>{p.title}</h3><button aria-label={t('lblClose')} onClick={close}>×</button></header>
        <div class="cq-prefs-body">
          {groups.map(g => {
            const rows = p.rows.filter(r => r.group === g);
            // Rows that carry a column are one value of a row of several, drawn as a table with a column each
            const columns = [...new Set(rows.flatMap(r => (r.column ? [r.column] : [])))];
            const lines = [...new Set(rows.filter(r => r.column).map(r => r.label))];
            return (
              <section key={g} class={columns.length ? 'wide' : undefined}>
                <h4>{g}</h4>
                {columns.length > 0 && (
                  <table class="cq-pref-table">
                    <thead><tr><th />{columns.map(c => <th key={c}>{c}</th>)}</tr></thead>
                    <tbody>
                      {lines.map(label => (
                        <tr key={label}>
                          <th>{label}</th>
                          {columns.map(c => {
                            const r = rows.find(x => x.label === label && x.column === c);
                            return <td key={c} class={r && bad(r) ? 'bad' : undefined}>{r && field(r, `${label}, ${c}`)}</td>;
                          })}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                {rows.filter(r => !r.column).map(r => (
                  <label key={r.key} class={bad(r) ? 'cq-pref bad' : 'cq-pref'}>
                    <span>{r.label}</span>
                    {field(r)}
                  </label>
                ))}
              </section>
            );
          })}
        </div>
        <footer>
          <span class={p.problem ? 'cq-warn' : 'muted'}>{p.problem ?? p.note}</span>
          {p.canReset && <button onClick={() => { setTyped({}); setLast(null); actions.campaignPrefsReset(); }}>{t('lblReset')}</button>}
          <button class="primary" onClick={close}>{t('lblOK')}</button>
        </footer>
      </div>
    </Backdrop>
  );
}

/**
 * A campaign mode's page: its saved games as a shelf, which a form that starts one takes the place of, and an open save's pages.
 * icons are the balances' icons, asked for as the mode opens, since on a first visit they would wait behind every picture of its first page.
 */
export function CampaignShell({ model, actions, icons, shelf, open }: {
  model: Model; actions: Actions; icons: string[];
  shelf: (creating: boolean, setCreating: (on: boolean) => void) => ComponentChildren; open: () => ComponentChildren;
}) {
  const inside = model.campaignSave !== null;
  useEffect(() => { for (const icon of icons) new Image().src = skinIconUrl(icon); }, []);
  // The form that starts a save gives the shelf back when left or when its save opens
  const [creating, setCreating] = useState(false);
  useEffect(() => { if (inside) setCreating(false); }, [inside]);
  return (
    <div key={inside ? 'open' : 'shelf'} class={inside ? 'cq-shell' : 'limited-page'}>
      {/* A reward being revealed is gone through to its end: nothing behind it can be reached, by key or by pointer */}
      <div class="cq-under" inert={inside && !!model.reward}>
        <PageHeader class="limited-head">
          <div class="head-right">
            <HeadControls />
            <button onClick={() => (creating && !inside ? setCreating(false) : actions.campaignLeave())}>{t('lblBack')}</button>
          </div>
        </PageHeader>
      </div>
      {inside ? open() : shelf(creating, setCreating)}
    </div>
  );
}

/** The saved games under the way back to the start page, or the form that starts one. saves is null while they are being read. */
export function CampaignShelf({ model, actions, creating, setCreating, words, form, saves }: {
  model: Model; actions: Actions; creating: boolean; setCreating: (on: boolean) => void;
  words: { mode: string; new: string; yours: string; reading: string }; form: ComponentChildren; saves: ComponentChildren | null;
}) {
  const trail = [
    { label: t('lblStart'), go: () => { changeUi(u => { u.menuChoice = null; }); actions.campaignLeave(); } },
    { label: WAY_NAMES.play, go: () => { changeUi(u => { u.menuChoice = 'play'; }); actions.campaignLeave(); } },
    creating ? { label: words.mode, go: () => setCreating(false) } : { label: words.mode },
    ...(creating ? [{ label: words.new }] : []),
  ];
  return <>
    <SetupHead trail={trail} title={creating ? words.new : words.yours} />
    {model.error && !creating && <p class="limited-error">{model.error}</p>}
    {creating ? form : saves ?? <p class="muted pools-wait">{words.reading}</p>}
  </>;
}

/** A change of tab slides the page the way the tabs run (dialogs.css). */
export function slideTab<Tab extends string>(tabs: [Tab, TextKey][], from: Tab, to: Tab, set: (u: UiState) => void): void {
  const order = tabs.map(([id]) => id);
  if (to !== from) eased(set, () => ({ way: order.indexOf(to) > order.indexOf(from) ? 'on' : 'back' }));
}

/**
 * An open save: the bar, the open tab's page, the preferences and a reward's reveal. page draws the tab's page under a key that
 * changes when the preferences close, since they set the prices the pages show, so the page asks again.
 */
export function CampaignPages<Tab extends string>({ model, actions, tabs, tab, setTab, extra, page }: {
  model: Model; actions: Actions; tabs: [Tab, TextKey][]; tab: Tab; setTab: (tab: Tab) => void; extra?: ComponentChildren;
  page: (key: number) => ComponentChildren;
}) {
  // What a reward being revealed has yet to show. The server's balances already hold it all, so the bar shows less.
  const [owed, setOwed] = useState<Owed>({});
  const reward = model.reward;
  const [prefs, setPrefs] = useState(false);
  const [priced, setPriced] = useState(0);
  useEffect(() => { if (!reward) setOwed({}); }, [reward]);
  const bar = model.campaignBar!;
  return <>
    <CampaignBar bar={bar} tabs={tabs} tab={tab} setTab={setTab} held={reward ? owed : {}} prefs={() => setPrefs(true)} under={!!reward} extra={extra} />
    {model.error && <p class="limited-error">{model.error}</p>}
    <div class="cq-main" inert={!!reward}>{page(priced)}</div>
    {prefs && <Prefs model={model} actions={actions} close={() => { setPrefs(false); setPriced(priced + 1); }} />}
    {reward && <Reveal key={reward.steps.length + ':' + bar.name} reward={reward} onOwed={setOwed} done={() => actions.rewardClaim()} />}
  </>;
}
