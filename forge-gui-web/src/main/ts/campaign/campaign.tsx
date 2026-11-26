import type { ComponentChildren, FunctionComponent, RefObject } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { imageUrl, skinIconUrl } from '../images';
import { artUrl } from '../sleeves';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { Balance, CampaignBar as Bar, Figure, PrefRow, Reward } from '../protocol';
import { t, formatNumber, type TextKey } from '../text';
import { backStep, HeadControls, PageHeader, PhoneHead, SetupHead, WAY_NAMES } from '../header';
import { isPortrait } from '../form';
import { ICONS, Icon } from '../icons';
import { changeUi, eased, type UiState } from '../ui';
import { Backdrop } from '../dialog';
import { reducedMotion } from '../dom';
import { useDismiss } from '../hooks';

/** The amounts a reveal has not shown yet, by the icon of the balance they were paid into. */
export type Owed = Record<string, number>;

export interface RevealProps { reward: Reward; onOwed: (owed: Owed) => void; done: () => void }

/** The attributes of something a tooltip explains: the term's name and the tooltip's text. */
/** The marks that give an element a term tooltip, when it has a text. */
export const explained = (name: string, text?: string | null) => (text ? { 'data-term': name, 'data-tip': text, tabIndex: 0 } : {});

/** drawGlyph draws the glyph in place of the skin's icon, for a mode with icons of its own. counted makes a change count up or down (useTally). */
export function Purse({ icon, n, label, text, glyph, term, drawGlyph, counted }: {
  icon: string; n: number; label: string; drawGlyph?: (name: string) => ComponentChildren; counted?: boolean;
} & Partial<Pick<Balance, 'text' | 'glyph' | 'term'>>) {
  const coin = useRef<HTMLSpanElement>(null);
  const [shown, counting] = useTally(icon, n, !!counted, coin);
  // A text such as life out of its maximum starts with the amount, which counts in its place
  const amount = text != null ? (shown === n ? text : text.replace(formatNumber(n), formatNumber(shown))) : formatNumber(shown);
  return (
    <span ref={coin} class={counting ? 'camp-coin counting' : 'camp-coin'} data-icon={icon} title={term ? undefined : label} role={glyph ? 'img' : undefined}
      aria-label={glyph ? `${label}: ${amount}` : undefined} {...explained(label, term)}>
      {glyph ? drawGlyph?.(glyph) ?? <Icon d={ICONS[glyph as keyof typeof ICONS]} /> : <img alt={label} src={skinIconUrl(icon)} />}
      <b>{amount}</b>
    </span>
  );
}

const TALLY_MS = 700;
/** libgdx's pow3Out, which the mobile client counts with. */
const pow3Out = (p: number) => 1 - (1 - p) ** 3;
// The amount each counted balance last went to, by icon. It outlives the bar, so a bar drawn again counts what changed while it was gone
const tallies = new Map<string, number>();
// Where the coins of a change fly from, by balance icon, while something on the page shows that change
const coinSources = new Map<string, Element>();

/** Forgets the counted amounts, so the next bar shows its balances as they are. A mode calls it when its save closes. */
export function forgetTallies(): void {
  tallies.clear();
}

/** Makes the coins of a change to the balance with that icon fly from el, or with null from the middle of the page again. */
export function coinsFrom(icon: string, el: Element | null): void {
  if (el) coinSources.set(icon, el);
  else coinSources.delete(icon);
}

/**
 * The amount a balance shows and whether it is counting. When counted, a change counts from the old amount to the new over 0.7 s
 * while coins fly into the balance for a gain or out of it for a loss, as the mobile client's run bar does. The first amount shows as it is.
 */
function useTally(icon: string, n: number, counted: boolean, coin: RefObject<HTMLElement>): [number, boolean] {
  const [shown, setShown] = useState(() => (counted ? tallies.get(icon) ?? n : n));
  const [counting, setCounting] = useState(false);
  const now = useRef(shown);
  useEffect(() => {
    if (!counted) return;
    const from = tallies.get(icon);
    tallies.set(icon, n);
    if (from === undefined || from === n || reducedMotion()) {
      now.current = n;
      setShown(n);
      setCounting(false);
      return;
    }
    flyCoins(icon, coin.current!, n - from);
    const start = now.current;
    const began = performance.now();
    let frame = requestAnimationFrame(function step(at) {
      const p = Math.min(1, (at - began) / TALLY_MS);
      now.current = start + (n - start) * pow3Out(p);
      setShown(Math.round(now.current));
      setCounting(p < 1);
      if (p < 1) frame = requestAnimationFrame(step);
    });
    setCounting(true);
    return () => cancelAnimationFrame(frame);
  }, [n, counted]);
  return [counted ? shown : n, counted && counting];
}

/** Flies 3 to 8 copies of a balance's picture from its source into it for a gain, or out of it for a loss, each rising on an arc. */
function flyCoins(icon: string, coin: HTMLElement, change: number): void {
  const to = coin.firstElementChild!.getBoundingClientRect();
  // A bar that hides its balances, as some Rogue pages do, has nowhere for the coins to go
  if (!to.width) return;
  const source = coinSources.get(icon);
  const from = source?.isConnected ? source.getBoundingClientRect() : null;
  const midX = from ? from.left + from.width / 2 : innerWidth / 2;
  const midY = from ? from.top + from.height / 2 : innerHeight * .4;
  const barX = to.left + to.width / 2, barY = to.top + to.height / 2;
  const coins = Math.max(3, Math.min(8, Math.abs(change)));
  for (let c = 0; c < coins; c++) {
    const spread = (c - coins / 2) * 12;
    const out = { x: midX + spread, y: midY, scale: 1.6 };
    const bar = { x: barX, y: barY, scale: 1 };
    const [a, b] = change > 0 ? [out, bar] : [bar, out];
    const mote = coin.firstElementChild!.cloneNode(true) as HTMLElement;
    mote.classList.add('camp-mote');
    mote.setAttribute('aria-hidden', 'true');
    mote.dataset.icon = icon;
    mote.style.opacity = '0';
    document.body.append(mote);
    const frames = Array.from({ length: 13 }, (_, i) => {
      const p = i / 12, e = pow3Out(p);
      const x = a.x + (b.x - a.x) * e, y = a.y + (b.y - a.y) * e - 40 * Math.sin(Math.PI * p);
      return { transform: `translate(${x}px, ${y}px) scale(${a.scale + (b.scale - a.scale) * e})`, opacity: 1 };
    });
    mote.animate(frames, { duration: 500, delay: c * 50 }).finished.then(() => mote.remove(), () => mote.remove());
  }
}

export const balance = (bar: Bar | null, icon: string): number => bar?.balances.find(b => b.icon === icon)?.amount ?? 0;

// The most the bar shows of a balance, by icon, while a trade's amounts fly to it. The new balance can arrive before they do
const ceilings = new Map<string, number>();
let redrawBar = () => {};

/** Sets the most the bar shows of a balance, or with null lets it show the server's amount again. */
export function capBalance(icon: string, most: number | null): void {
  if (most === null) ceilings.delete(icon);
  else ceilings.set(icon, most);
  redrawBar();
}

/** Animates an amount from a card to the bar's balance with that icon. The bar counts the amount when it arrives. */
export function flyShards(from: DOMRect, arrived: () => void, icon: string): void {
  const coin = document.querySelector(`.camp-purse .camp-coin[data-icon="${icon}"]`);
  const to = coin?.getBoundingClientRect();
  if (!to || reducedMotion()) {
    arrived();
    return;
  }
  // The mote is the balance's own picture, which is a skin image or a drawn glyph
  const mote = coin!.firstElementChild!.cloneNode(true) as HTMLElement;
  mote.classList.add('camp-mote');
  mote.setAttribute('aria-hidden', 'true');
  mote.dataset.icon = icon;
  document.body.append(mote);
  const x0 = from.left + from.width / 2, y0 = from.top + from.height * .4;
  mote.animate([{ transform: `translate(${x0}px, ${y0}px) scale(1.4)`, opacity: 1 },
    { transform: `translate(${to.left + 14}px, ${to.top + to.height / 2}px) scale(.8)`, opacity: .9 }],
    { duration: 600, easing: 'cubic-bezier(.5,0,.3,1)' }).finished.then(() => { mote.remove(); arrived(); }, () => mote.remove());
}

// The new items each tab has shown, by save and tab. A save's first bar counts as shown, so only what arrives while it is open is marked
const shown = new Map<string, Set<string>>();

/** Whether a tab has a new item it has not shown yet. An open tab shows all of them. */
function unseen(bar: Bar, tab: string, open: boolean): boolean {
  const fresh = bar.fresh[tab] ?? [];
  const key = `${bar.name}
${tab}`;
  const seen = shown.get(key);
  if (!seen || open) {
    shown.set(key, new Set([...(seen ?? []), ...fresh]));
    return false;
  }
  return fresh.some(name => !seen.has(name));
}

/**
 * The bar shared by every page of a campaign. It shows the save's name and line, the mode's tabs, the balances and the back button.
 * extra is a dev tool: a control beside the balances, or on a phone a row of the header's menu. mark draws what follows a tab's name.
 * disabledTabs holds the tabs that cannot be opened, with why. lineTerm is the text of the line's tooltip.
 * more draws the mode's own menu in place of Preferences: the panel its button opens, or on a phone rows of the header's menu.
 * glyph draws a balance's glyph, and moreIcon the icon of the menu's button, for a mode with icons of its own. counted makes the balances count their changes.
 */
export function CampaignBar<Tab extends string>({ bar, tabs, tab, setTab, lead, mark, disabledTabs, held, extra, prefs, more, leave, under, lineTerm, glyph, moreIcon, counted }: {
  bar: Bar; tabs: [Tab, TextKey][]; tab: Tab; setTab: (tab: Tab) => void;
  lead?: (tab: Tab) => ComponentChildren; mark?: (tab: Tab) => ComponentChildren; disabledTabs?: Partial<Record<Tab, string>>;
  /** The amounts a reward reveal has not shown yet, by balance icon. The server's balances already include them, so the bar subtracts them. */
  held: Record<string, number>;
  extra?: ComponentChildren; prefs?: () => void; more?: (close: () => void) => ComponentChildren; leave: () => void; under: boolean; lineTerm?: string;
  glyph?: (name: string) => ComponentChildren; moreIcon?: ComponentChildren; counted?: boolean;
}) {
  const strip = useRef<HTMLElement>(null);
  const purse = useRef<HTMLDivElement>(null);
  const [menu, setMenu] = useState(false);
  useDismiss(menu, purse, () => setMenu(false));
  const [, redraw] = useState(0);
  useEffect(() => {
    redrawBar = () => redraw(n => n + 1);
    return () => { redrawBar = () => {}; };
  }, []);
  // A tab opened from a page, such as Change on the map, may be scrolled out of a narrow strip
  useEffect(() => { strip.current?.querySelector('[aria-current="page"]')?.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }, [tab]);
  const nav = (
    <nav class="camp-tabs" ref={strip}>
      {tabs.map(([id, name]) => (
        <button key={id} class="camp-tab" data-tab={id} aria-current={id === tab ? 'page' : undefined} disabled={!!disabledTabs?.[id]} title={disabledTabs?.[id]}
          onClick={() => setTab(id)}>
          {lead?.(id)}{t(name)}{mark?.(id)}{unseen(bar, id, id === tab) && <span class="new-card">{t('lblNew')}</span>}{id === tab && <span class="camp-tab-line" />}
        </button>
      ))}
    </nav>
  );
  const balances = bar.balances.map(b => <Purse key={b.icon} {...b} n={Math.min(b.amount - (held[b.icon] ?? 0), ceilings.get(b.icon) ?? Infinity)} drawGlyph={glyph} counted={counted} />);
  const line = <span {...explained(bar.line, lineTerm)}>{bar.line}</span>;
  if (isPortrait()) {
    return (
      <PhoneHead back={{ label: t('lblBack'), go: leave }} title={bar.name} inert={under} tabs={nav}
        line={<><span class="camp-purse">{balances}</span>{line}</>}
        more={more ?? (close => prefs && <button role="menuitem" onClick={() => { close(); prefs(); }}><Icon d={ICONS.sliders} />{t('Preferences')}</button>)}
        dev={extra} />
    );
  }
  return (
    <div class="camp-bar" inert={under}>
      <div class="camp-id"><b>{bar.name}</b>{line}</div>
      {nav}
      <div class="camp-purse" ref={purse}>
        {balances}
        {prefs && !more && <button onClick={prefs}>{t('Preferences')}</button>}
        {extra}
        {more && (
          <button class="icon-button" title={t('lblMore')} aria-label={t('lblMore')} aria-expanded={menu} onClick={() => setMenu(!menu)}>
            {moreIcon ?? <Icon d={ICONS.ellipsis} />}
          </button>
        )}
        <HeadControls />
        <button onClick={leave}>{t('lblBack')}</button>
        {menu && more?.(() => setMenu(false))}
      </div>
    </div>
  );
}

/**
 * The panel beside a page's list. It shows the chosen item's title, picture and text, and the player's own items in the foot. A pressed picture in the foot is shown large.
 * fold makes the title a button that opens and closes the rest of the panel, for a panel drawn as a small card over the page.
 * noArt leaves out the picture's place, for an item that never has one.
 */
export function SidePanel({ kicker, where, title, art, noArt, zoom, setZoom, children, foot, fold, top }: {
  kicker: ComponentChildren; where?: ComponentChildren; title?: string; art?: string | null; noArt?: boolean;
  zoom: string | null; setZoom: (image: string | null) => void; children: ComponentChildren; foot: ComponentChildren;
  fold?: { open: boolean; toggle: () => void };
  /** A panel stacked above this one, such as Rogue's commander. The two then sit in separate frames. */
  top?: ComponentChildren;
}) {
  const body = <>
    <div class="camp-panel">
      <div class={art ? 'camp-side-head' : 'camp-side-head bare'}>
        <div class="camp-where"><span class="camp-kicker">{kicker}</span>{where}</div>
        {fold ? <h3><button class="camp-fold" aria-expanded={fold.open} onClick={fold.toggle}>{title}</button></h3> : <h3>{title}</h3>}
        {!noArt && <div class="camp-art" data-image={art ?? undefined} style={{ backgroundImage: art ? `url("${artUrl(art)}")` : undefined }} />}
      </div>
      {children}
    </div>
    <div class="camp-foot">{foot}</div>
  </>;
  return (
    <aside class={fold?.open ? 'camp-side open' : 'camp-side'}>
      {top ? <>{top}<div class="camp-side-body">{body}</div></> : body}
      {zoom && <div class="backdrop" onClick={() => setZoom(null)}><img class="camp-zoom" alt="" src={imageUrl(zoom)} /></div>}
    </aside>
  );
}

/** changeLabel names the change button, which says Change unless it is given. */
export function DeckRow({ image, name, line, problem, zoom, change, changeLabel }: {
  image?: string | null; name: string; line: string; problem: boolean; zoom: (image: string) => void; change?: () => void; changeLabel?: string;
}) {
  return (
    <div class="camp-mine">
      {image && <button class="camp-face2" aria-label={name} data-image={image} style={{ backgroundImage: `url("${artUrl(image)}")` }} onClick={() => zoom(image)} />}
      <div class="t">
        <b>{name}</b>
        {problem ? <span class="camp-warn">{t('lblInvalidDeck')}</span> : <span>{line}</span>}
      </div>
      {change && <button onClick={change}>{changeLabel ?? t('lblWebMenuChange')}</button>}
    </div>
  );
}

export function Figures({ figures }: { figures: Figure[] }) {
  return (
    <div class="camp-figures">
      {figures.map(f => (
        <div key={f.label} class="camp-figure">
          <span class="camp-kicker">{f.label}</span>
          <p><b>{f.text ?? formatNumber(f.amount)}</b>{f.of != null && <span> / {formatNumber(f.of)}{f.text == null && <> ({f.of ? Math.round(100 * f.amount / f.of) : 0}%)</>}</span>}</p>
          {f.of != null && <div class="camp-pbar"><i style={{ width: `${f.of ? 100 * f.amount / f.of : 0}%` }} /></div>}
        </div>
      ))}
    </div>
  );
}

/** A campaign's statistics. It shows figures and any tables the mode sends. first is the scope requested when the page opens. */
export function Stats({ model, actions, first }: { model: Model; actions: Actions; first?: string }) {
  const s = model.campaignStats;
  useEffect(() => { actions.campaignStats(first); }, []);
  if (!s) return <p class="muted pools-wait">{t('lblLoadingEllipsis')}</p>;
  return (
    <div class="camp-stats">
      <div class="camp-stats-head">
        <div><span class="muted">{model.campaignBar?.name}</span><h2>{s.title}</h2></div>
        {s.scopes.length > 0 && (
          <span class="seg" role="group">
            {s.allScopes && <button aria-pressed={!s.scope} onClick={() => actions.campaignStats(undefined)}>{s.allScopes}</button>}
            {s.scopes.map(p => <button key={p} aria-pressed={p === s.scope} onClick={() => actions.campaignStats(p)}>{p}</button>)}
          </span>
        )}
      </div>
      <Figures figures={s.figures} />
      <div class="camp-tables">
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

/** A mode's preferences. Each value is saved when it changes, because the server checks it against the current values of the others. */
export function Prefs({ model, actions, close }: { model: Model; actions: Actions; close: () => void }) {
  const p = model.campaignPrefs;
  /** Text typed but not yet accepted, by key. A refused value stays in its field so the player can correct it. */
  const [typed, setTyped] = useState<Record<string, string>>({});
  const [last, setLast] = useState<string | null>(null);
  useEffect(() => { actions.campaignPrefs(); }, []);
  // Once a value is accepted, its field shows the server's value again. Text typed in other fields is kept
  useEffect(() => {
    if (p && !p.problem && last) setTyped(({ [last]: _, ...rest }) => rest);
  }, [p]);
  if (!p) return null;
  // A group drawn as a table spans both columns of the dialog, so it is placed after the other groups
  const tabled = (g: string) => p.rows.some(r => r.group === g && r.column);
  const named = [...new Set(p.rows.map(r => r.group))];
  const groups = [...named.filter(g => !tabled(g)), ...named.filter(tabled)];
  // save takes the field's own text, because a blur can happen before the typed text has been rendered
  const save = (key: string, text: string, was: string) => {
    // The mode decides which values are allowed. A refused value comes back as the problem line
    if (text.trim() === '' || Number(text) === Number(was)) return;
    setLast(key);
    actions.campaignPref(key, text.trim());
  };
  // A value written as a decimal can be entered as any decimal. The mode still decides whether the value is allowed
  const field = (r: PrefRow, label?: string) => (
    <input type="number" min={0} step={r.value.includes('.') ? 'any' : 1} value={typed[r.key] ?? r.value} aria-label={label}
      onInput={e => setTyped({ ...typed, [r.key]: e.currentTarget.value })}
      onBlur={e => save(r.key, e.currentTarget.value, r.value)}
      onKeyDown={e => { if (e.key === 'Enter') save(r.key, e.currentTarget.value, r.value); }} />
  );
  const bad = (r: PrefRow) => !!p.problem && last === r.key;
  return (
    <Backdrop dismiss={close}>
      <div class="dialog camp-prefs">
        <header><h3>{p.title}</h3><button aria-label={t('lblClose')} onClick={close}>×</button></header>
        <div class="camp-prefs-body">
          {groups.map(g => {
            const rows = p.rows.filter(r => r.group === g);
            // Rows with a column are cells of a row that holds several values. They are drawn as a table with one column per name
            const columns = [...new Set(rows.flatMap(r => (r.column ? [r.column] : [])))];
            const lines = [...new Set(rows.filter(r => r.column).map(r => r.label))];
            return (
              <section key={g} class={columns.length ? 'wide' : undefined}>
                <h4>{g}</h4>
                {columns.length > 0 && (
                  <table class="camp-pref-table">
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
                  <label key={r.key} class={bad(r) ? 'camp-pref bad' : 'camp-pref'}>
                    <span>{r.label}</span>
                    {field(r)}
                  </label>
                ))}
              </section>
            );
          })}
        </div>
        <footer>
          <span class={p.problem ? 'camp-warn' : 'muted'}>{p.problem ?? p.note}</span>
          {p.canReset && <button onClick={() => { setTyped({}); setLast(null); actions.campaignPrefsReset(); }}>{t('lblReset')}</button>}
          <button class="primary" onClick={close}>{t('lblOK')}</button>
        </footer>
      </div>
    </Backdrop>
  );
}

/**
 * A campaign mode's page. It shows the saved games, the form that starts a new one in their place, or an open save's pages.
 * icons are the balance icons. They are loaded when the mode opens, because on a first visit they would otherwise load after every picture on the first page.
 * always is for a mode that has no shelf of saved games: open draws its pages also while no save is open.
 */
export function CampaignShell({ model, actions, icons, shelf, open, always, class: cls }: {
  model: Model; actions: Actions; icons: string[];
  shelf?: (creating: boolean, setCreating: (on: boolean) => void) => ComponentChildren; open: () => ComponentChildren;
  always?: boolean; class?: string;
}) {
  const inside = always || model.campaignSave !== null;
  useEffect(() => { for (const icon of icons) new Image().src = skinIconUrl(icon); }, []);
  // The new-save form closes and the saves list returns when the player goes back or the new save opens
  const [creating, setCreating] = useState(false);
  useEffect(() => { if (inside) setCreating(false); }, [inside]);
  return (
    <div key={inside ? 'open' : 'shelf'} class={!inside ? 'limited-page' : cls ? `camp-shell ${cls}` : 'camp-shell'}>
      {/* An open save has its back button on its bar. On a phone the shelf's trail draws the header */}
      {!inside && !isPortrait() && (
        <PageHeader class="limited-head">
          <div class="head-right">
            <HeadControls />
            <button onClick={() => (creating ? setCreating(false) : actions.campaignLeave())}>{t('lblBack')}</button>
          </div>
        </PageHeader>
      )}
      {inside ? open() : shelf?.(creating, setCreating)}
    </div>
  );
}

/** The saved games under a breadcrumb trail back to the start page, or the form that starts one. saves is null while they load. */
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
  const title = creating ? words.new : words.yours;
  return <>
    {isPortrait() && <PhoneHead back={backStep(trail)} title={title} />}
    <SetupHead trail={trail} title={title} />
    {model.error && !creating && <p class="limited-error">{model.error}</p>}
    {creating ? form : saves ?? <p class="muted pools-wait">{words.reading}</p>}
  </>;
}

/** A tab change slides the page in the direction of the new tab in the tab order (dialogs.css). */
export function slideTab<Tab extends string>(tabs: [Tab, TextKey][], from: Tab, to: Tab, set: (u: UiState) => void): void {
  const order = tabs.map(([id]) => id);
  if (to !== from) eased(set, () => ({ way: order.indexOf(to) > order.indexOf(from) ? 'on' : 'back' }), () => !document.querySelector('.camp-main .pools-wait'));
}

/**
 * An open save: the bar, the open tab's page, the preferences, and Reveal while there is a reward to show. page draws the tab's page under a key that
 * changes when the preferences close. The preferences set the prices the pages show, so the page must request its data again.
 */
export function CampaignPages<Tab extends string>({ model, actions, tabs, tab, setTab, extra, page, Reveal }: {
  model: Model; actions: Actions; tabs: [Tab, TextKey][]; tab: Tab; setTab: (tab: Tab) => void; extra?: ComponentChildren;
  Reveal?: FunctionComponent<RevealProps>;
  page: (key: number) => ComponentChildren;
}) {
  // The amounts the reward reveal has not shown yet. The bar subtracts them from the server's balances
  const [owed, setOwed] = useState<Owed>({});
  const reward = model.reward;
  const [prefs, setPrefs] = useState(false);
  const [priced, setPriced] = useState(0);
  useEffect(() => { if (!reward) setOwed({}); }, [reward]);
  const bar = model.campaignBar!;
  return <>
    <CampaignBar bar={bar} tabs={tabs} tab={tab} setTab={setTab} held={reward ? owed : {}} prefs={() => setPrefs(true)}
      // Back returns to the first tab, the save's home page, before it leaves the save
      leave={() => (tab === tabs[0][0] ? actions.campaignLeave() : setTab(tabs[0][0]))} under={!!reward} extra={extra} />
    {model.error && <p class="limited-error">{model.error}</p>}
    <div class="camp-main" inert={!!reward}>{page(priced)}</div>
    {prefs && <Prefs model={model} actions={actions} close={() => { setPrefs(false); setPriced(priced + 1); }} />}
    {reward && Reveal && <Reveal key={reward.steps.length + ':' + bar.name} reward={reward} onOwed={setOwed} done={() => actions.rewardClaim()} />}
  </>;
}
