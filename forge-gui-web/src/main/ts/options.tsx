// The cog dialog: settings in tabs down a side rail, with a search box that looks through every tab

import type { ComponentChildren } from 'preact';
import { exportBackup, importBackup } from './backup';
import { ForgeLinks } from './links';
import { useEffect, useRef, useState } from 'preact/hooks';
import { saveText } from './dom';
import { keyName, rebind, type KeyBindings } from './keys';
import { SETTINGS, boundKeys, defaultKeys, isGuest, set, setKeys, setting, type SettingDef } from './settings';
import { normalize, rankByName } from './search';
import { t, textLanguage } from './text';
import { easedLocal } from './ui';
import { useClosing } from './closing';

// A setting found only through its section or its hint ranks after label matches, and each section stays together in its usual place
function matching(defs: SettingDef[], typed: string): SettingDef[] {
  const text = normalize(typed);
  if (!text) return defs;
  const ranked = rankByName(defs.map(d => d.label), typed);
  const found = new Set(ranked);
  defs.forEach((d, i) => {
    if (!found.has(i) && normalize(`${d.section} ${d.hint ?? ''}`).includes(text)) ranked.push(i);
  });
  const firstOf = new Map<string, number>();
  defs.forEach((d, i) => { if (!firstOf.has(d.section)) firstOf.set(d.section, i); });
  return ranked.map(i => defs[i]).sort((a, b) => (firstOf.get(a.section) ?? 0) - (firstOf.get(b.section) ?? 0));
}

// Icons from Lucide (ISC, see web/licenses/lucide-license.txt)
const TABS: { name: string; sections: string[]; icon: ComponentChildren }[] = [
  {
    name: t('lblWebOptionsSectionGameplay'),
    sections: [t('lblWebOptionsSectionGameplay'), t('lblWebGameMenuStopsTitle'), t('lblWebGameMenuDecisionsTitle')],
    icon: <><path d="M6 11h4M8 9v4M15 12h.01M18 10h.01" /><path d="M17.32 5H6.68a4 4 0 0 0-3.978 3.59C2.604 9.416 2 14.456 2 16a3 3 0 0 0 3 3c1 0 1.5-.5 2-1l1.414-1.414A2 2 0 0 1 9.828 16h4.344a2 2 0 0 1 1.414.586L17 18c.5.5 1 1 2 1a3 3 0 0 0 3-3c0-1.545-.604-6.584-.685-7.258A4 4 0 0 0 17.32 5z" /></>,
  },
  { name: t('lblDisplay'), sections: [t('lblDisplay')], icon: <><rect width="20" height="14" x="2" y="3" rx="2" /><path d="M8 21h8M12 17v4" /></> },
  {
    name: t('lblWebOptionsSectionSound'), sections: [t('lblWebOptionsSectionSound')],
    icon: <><path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z" /><path d="M16 9a5 5 0 0 1 0 6" /></>,
  },
  {
    name: t('lblWebOptionsSectionKeys'), sections: [t('lblWebOptionsSectionKeys')],
    icon: <><rect width="20" height="16" x="2" y="4" rx="2" /><path d="M6 8h.01M10 8h.01M14 8h.01M18 8h.01M8 12h.01M12 12h.01M16 12h.01M7 16h10" /></>,
  },
  { name: t('lblAdvanced'), sections: [t('lblAdvanced')], icon: <><path d="m16 18 6-6-6-6" /><path d="m8 6-6 6 6 6" /></> },
];

/** The tab last looked at, so the dialog opens where it was left. */
let lastTab = 0;

export function Options({ close }: { close: () => void }) {
  const [query, setQuery] = useState('');
  const [at, setAt] = useState(lastTab);
  const search = useRef<HTMLInputElement>(null);
  useEffect(() => {
    search.current?.focus();
  }, []);
  const searching = !!query.trim();
  const tab = TABS[at];
  const all = SETTINGS.filter(def => !(def.hostOnly && isGuest()));
  // A tab lists its sections in its own order; a search looks through every tab
  const shown = searching ? matching(all, query) : tab.sections.flatMap(section => all.filter(def => def.section === section));
  // The rail runs down the side, so the next tab's page comes up from below and an earlier one down from above (dialogs.css)
  const open = (i: number) => easedLocal(() => {
    lastTab = i;
    setAt(i);
    setQuery('');
  }, (): Record<string, string> => (i === at ? {} : { rail: i > at ? 'on' : 'back' }));
  return (
    <OptionsDialog title={t('lblWebHeadOptions')} kind="tabbed" close={close}
      head={<input ref={search} class="search" type="search" placeholder={t('lblWebOptionsSearch')} aria-label={t('lblWebOptionsSearch')}
        value={query} onInput={e => setQuery(e.currentTarget.value)} />}
      rail={<>
        {TABS.map((tb, i) => (
          <button key={tb.name} role="tab" aria-selected={!searching && i === at} onClick={() => open(i)}>
            <svg viewBox="0 0 24 24" aria-hidden="true">{tb.icon}</svg>{tb.name}
            {!searching && i === at && <span class="rail-mark" />}
          </button>
        ))}
        <ForgeLinks />
      </>}
      footer={<>
        <span class="hint">{t('lblWebOptionsFooter')}</span>
        {!searching && shown[0]?.type === 'key' && <button class="section-action" onClick={() => setKeys(defaultKeys())}>{t('lblWebOptionsResetKeys')}</button>}
      </>}>
      {shown.flatMap((def, i) => [
        // A tab's first section goes by the tab's own name, so it has no heading
        ...(def.section !== shown[i - 1]?.section && (searching || def.section !== tab.name)
          ? [<SectionHeading key={`section ${def.section}`} name={def.section} keys={searching && def.type === 'key'} />] : []),
        <Row key={def.key} def={def} />,
      ])}
      {!searching && tab.name === t('lblAdvanced') && <BackupRow />}
      {!shown.length && <p class="hint">{t('lblWebOptionsNoMatch')}</p>}
    </OptionsDialog>
  );
}

/** What this browser keeps for its player, saved as a file or put back from one. A file put back loads the page again. */
function BackupRow() {
  const [refused, setRefused] = useState(false);
  const file = useRef<HTMLInputElement>(null);
  return (
    <div class="setting">
      <div>
        <div>{t('lblWebOptionsBackup')}</div>
        <div class="hint">{t(refused ? 'lblWebOptionsImportRefused' : 'lblWebOptionsBackupHint')}</div>
      </div>
      <div class="backup">
        <button class="edit" onClick={() => void exportBackup()}>{t('lblWebOptionsExport')}</button>
        <button class="edit" onClick={() => file.current?.click()}>{t('lblImport')}</button>
        <input ref={file} type="file" accept=".json,application/json" hidden onChange={e => {
          const chosen = e.currentTarget.files?.[0];
          e.currentTarget.value = '';
          if (chosen) void chosen.text().then(importBackup).then(done => setRefused(!done));
        }} />
      </div>
    </div>
  );
}

/** A section's name; the keys carry the way back to their defaults beside it. */
function SectionHeading({ name, keys }: { name: string; keys: boolean }) {
  return (
    <h4>
      {name}
      {keys && <button class="section-action" onClick={() => setKeys(defaultKeys())}>{t('lblWebOptionsResetKeys')}</button>}
    </h4>
  );
}

export const CloseIcon = () => <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18" /><path d="m6 6 12 12" /></svg>;

/** The frame the options dialog and the game menu's dialogs share, where label names the dialog when the title is a phrase. */
export function OptionsDialog({ title, label, kind, head, rail, footer, close, children }: {
  title: string; label?: string; kind?: string; head?: ComponentChildren; rail?: ComponentChildren; footer: ComponentChildren; close: () => void;
  children: ComponentChildren;
}) {
  const { closing, shut, gone } = useClosing(close);
  return (
    <div id="options" class={closing ? 'backdrop closing' : 'backdrop'} onAnimationEnd={gone} onMouseDown={e => { if (e.target === e.currentTarget) shut(); }}>
      <div class={kind ? `options-dialog ${kind}` : 'options-dialog'} role="dialog" aria-label={label ?? title}>
        <header>
          <b>{title}</b>
          {head ?? <span class="spacer" />}
          <button class="close" title={t('lblWebOptionsCloseEsc')} onClick={shut}><CloseIcon /></button>
        </header>
        {rail ? <div class="split"><nav class="rail" role="tablist">{rail}</nav><div class="rows">{children}</div></div> : <div class="rows">{children}</div>}
        <footer>{footer}</footer>
      </div>
    </div>
  );
}

/** onChange runs after the setting is changed, for a dialog that must follow it. */
export function Row({ def, onChange }: { def: SettingDef; onChange?: () => void }) {
  const [editing, setEditing] = useState(false);
  return (
    <div class={def.type === 'tiles' ? 'setting wide' : 'setting'}>
      <div>
        <div>{def.label}</div>
        {def.hint && <div class="hint">{def.hint}</div>}
      </div>
      {def.type === 'css'
        ? <button class="edit" aria-expanded={editing} onClick={() => setEditing(!editing)}>{editing ? t('lblDone') : t('lblWebOptionsEdit')}</button>
        : <Control def={def} onChange={onChange} />}
      {def.type === 'css' && editing && <CssEditor def={def} value={String(setting(def.key) ?? '')} />}
    </div>
  );
}

export function OnOff({ on, label, change }: { on: boolean; label: string; change: (on: boolean) => void }) {
  return <button class="switch" role="switch" aria-checked={on} aria-label={label} onClick={() => change(!on)} />;
}

function Control({ def, onChange }: { def: SettingDef; onChange?: () => void }) {
  const value = setting(def.key);
  const change = (next: string | number | boolean) => {
    set(def.key, next);
    onChange?.();
  };
  switch (def.type) {
    case 'toggle':
      return <OnOff on={!!value} label={def.label} change={change} />;
    case 'choice':
      return (
        <select class="pick" aria-label={def.label} value={String(value)} onChange={e => change(e.currentTarget.value)}>
          {def.options.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
        </select>
      );
    case 'tiles': {
      // A choice that cannot be made now shows the one in force in its place
      const usable = def.options.filter(o => !o.unavailable?.());
      const inForce = usable.some(o => o.value === value) ? value : usable[0]?.value;
      return (
        <div class="tiles" role="radiogroup" aria-label={def.label}>
          {def.options.map(o => (
            <button key={o.value} role="radio" aria-checked={inForce === o.value} disabled={!usable.includes(o)} onClick={() => change(o.value)}>
              <svg viewBox="0 0 104 80" aria-hidden="true" dangerouslySetInnerHTML={{ __html: o.picture }} />
              <b>{o.label}</b>
              <small>{o.hint}</small>
            </button>
          ))}
        </div>
      );
    }
    case 'css':
      // Row draws the CSS editor's own button and editor, since the editor is too big for the control column
      return null;
    case 'key':
      return <KeyControl action={def.action} value={String(value)} />;
    case 'slider':
      return (
        <div class="slider">
          <input type="range" min={def.min} max={def.max} step={def.step ?? 5} value={Number(value)}
            onInput={e => change(Number(e.currentTarget.value))} />
          <span>{def.unit === 'seconds' ? t('lblWebOptionsSeconds', seconds(Number(value))) : t('lblWebOptionsPercent', Number(value))}</span>
        </div>
      );
  }
}

/** A delay in milliseconds as seconds, to one or two decimal places: 1.5, 0.25, 0.0. */
function seconds(ms: number): string {
  return new Intl.NumberFormat(textLanguage(), { minimumFractionDigits: 1, maximumFractionDigits: 2 }).format(ms / 1000);
}

// A theme is a plain CSS file: load one, save the current one, or edit it here. Typed CSS lands at once.
function CssEditor({ def, value }: { def: SettingDef; value: string }) {
  const file = useRef<HTMLInputElement>(null);
  return (
    <div class="css-editor">
      <textarea class="css" spellcheck={false} rows={5} placeholder="#prompt { border-color: #dfa23c; }" value={value}
        onInput={e => set(def.key, e.currentTarget.value)} />
      <div class="css-buttons">
        <button onClick={() => file.current?.click()}>{t('lblImport')}</button>
        <button onClick={() => saveText(value, 'forge-theme.css', 'text/css')}>{t('lblWebOptionsExport')}</button>
        <button onClick={() => set(def.key, '')}>{t('lblWebOptionsClear')}</button>
      </div>
      <input ref={file} type="file" accept=".css,text/css" hidden onChange={async e => {
        const input = e.currentTarget;
        const chosen = input.files?.[0];
        if (chosen) {
          set(def.key, await chosen.text());
        }
        input.value = '';
      }} />
    </div>
  );
}

// Click, then press the new key. Escape leaves the key as it was.
function KeyControl({ action, value }: { action: keyof KeyBindings; value: string }) {
  const [listening, setListening] = useState(false);
  return (
    <button class={listening ? 'key-bind listening' : 'key-bind'} onClick={() => setListening(true)} onBlur={() => setListening(false)}
      onKeyDown={e => {
        if (!listening || e.ctrlKey || e.altKey || e.metaKey || ['Shift', 'Control', 'Alt', 'Meta'].includes(e.key)) return;
        // The page's own key handling must not also act on the key being chosen
        e.preventDefault();
        e.stopPropagation();
        if (e.key !== 'Escape') setKeys(rebind(boundKeys(), action, e.key));
        setListening(false);
        // Blurring stops a Space from also clicking the button when it is released, which would listen again
        e.currentTarget.blur();
      }}>
      {listening ? t('lblWebOptionsPressKey') : keyName(value)}
    </button>
  );
}
