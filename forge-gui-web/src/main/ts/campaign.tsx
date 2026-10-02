// What every campaign mode's pages share: the bar over them, and a balance of one of its currencies

import type { ComponentChildren } from 'preact';
import { skinIconUrl } from './images';
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
          <button key={id} class="cq-tab" aria-current={id === tab ? 'page' : undefined} onClick={() => setTab(id)}>{t(name)}</button>
        ))}
      </nav>
      <div class="cq-purse">
        {extra}
        {bar.balances.map(b => <Purse key={b.icon} icon={b.icon} n={b.amount - (held[b.icon] ?? 0)} label={b.label} />)}
        <button onClick={prefs}>{t('lblWebConquestPreferences')}</button>
      </div>
    </div>
  );
}
