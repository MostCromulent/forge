// The cog dialog: one scrolling list of settings with a search box

import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { saveText } from './dom';
import { KeyControl } from './keysdialog';
import { SETTINGS, defaultKeys, isGuest, set, setKeys, setting, type SettingDef } from './settings';
import { normalize, rankByName } from './search';

// Labels rank as every search box ranks names. A setting found only through its section or its hint comes after those.
// Each section stays together and in its usual place, so its heading is drawn once.
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

export function Options({ close }: { close: () => void }) {
  const [query, setQuery] = useState('');
  const search = useRef<HTMLInputElement>(null);
  useEffect(() => {
    search.current?.focus();
  }, []);
  const shown = matching(SETTINGS.filter(def => !def.volume && !def.menu && !(def.hostOnly && isGuest())), query);
  return (
    <OptionsDialog title="Options" close={close}
      head={<input ref={search} class="search" type="search" placeholder="Search settings" aria-label="Search settings"
        value={query} onInput={e => setQuery(e.currentTarget.value)} />}
      footer={<span class="hint">Changes apply at once. Auto-pass stops and conceding are in the ⋯ menu beside this button.</span>}>
      {shown.flatMap((def, i) => [
        ...(def.section !== shown[i - 1]?.section ? [<SectionHeading key={`section ${def.section}`} name={def.section} />] : []),
        <Row key={def.key} def={def} />,
      ])}
      {!shown.length && <p class="hint">No setting matches that.</p>}
    </OptionsDialog>
  );
}

/** A section's name; the keys carry the way back to their defaults beside it. */
function SectionHeading({ name }: { name: string }) {
  return (
    <h4>
      {name}
      {name === 'Keys' && <button class="section-action" onClick={() => setKeys(defaultKeys())}>Reset to defaults</button>}
    </h4>
  );
}

export const CloseIcon = () => <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18" /><path d="m6 6 12 12" /></svg>;

/**
 * The frame the options dialog and the game menu's dialogs share: a title with a close button, the rows, and a footer.
 * head takes the header's free space, which is otherwise left empty; label names the dialog when the title is a phrase.
 */
export function OptionsDialog({ title, label, kind, head, footer, close, children }: {
  title: string; label?: string; kind?: string; head?: ComponentChildren; footer: ComponentChildren; close: () => void;
  children: ComponentChildren;
}) {
  return (
    <div id="options" class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
      <div class={kind ? `options-dialog ${kind}` : 'options-dialog'} role="dialog" aria-label={label ?? title}>
        <header>
          <b>{title}</b>
          {head ?? <span class="spacer" />}
          <button class="close" title="Close (Esc)" onClick={close}><CloseIcon /></button>
        </header>
        <div class="rows">{children}</div>
        <footer>{footer}</footer>
      </div>
    </div>
  );
}

/**
 * One setting: what it is on the left, its control filling the column on the right. onChange runs after the setting
 * is changed, for a dialog that must follow it. The CSS editor is too big for the column, so it opens under the row.
 */
export function Row({ def, onChange }: { def: SettingDef; onChange?: () => void }) {
  const [editing, setEditing] = useState(false);
  return (
    <div class="setting">
      <div>
        <div>{def.label}</div>
        {def.hint && <div class="hint">{def.hint}</div>}
      </div>
      {def.type === 'css'
        ? <button class="edit" aria-expanded={editing} onClick={() => setEditing(!editing)}>{editing ? 'Done' : 'Edit…'}</button>
        : <Control def={def} onChange={onChange} />}
      {def.type === 'css' && editing && <CssEditor def={def} value={String(setting(def.key) ?? '')} />}
    </div>
  );
}

/**
 * On and off as a pair of segments, drawn as every other choice is, so each control in a list reads the same way.
 * Off is marked in grey rather than gold, so a glance down the list finds what is switched on.
 */
export function OnOff({ on, change }: { on: boolean; change: (on: boolean) => void }) {
  return (
    <div class="choice" role="radiogroup">
      <button class={on ? '' : 'on off'} role="radio" aria-checked={!on} onClick={() => change(false)}>Off</button>
      <button class={on ? 'on' : ''} role="radio" aria-checked={on} onClick={() => change(true)}>On</button>
    </div>
  );
}

function Control({ def, onChange }: { def: SettingDef; onChange?: () => void }) {
  const value = setting(def.key);
  const change = (next: string | number | boolean) => {
    set(def.key, next);
    onChange?.();
  };
  switch (def.type) {
    case 'toggle':
      return <OnOff on={!!value} change={change} />;
    case 'choice':
      return (
        <div class="choice" role="radiogroup">
          {def.options.map(([v, label]) => <button key={v} class={String(value) === v ? 'on' : ''} role="radio"
            aria-checked={String(value) === v} onClick={() => change(v)}>{label}</button>)}
        </div>
      );
    case 'css':
      return null;
    case 'key':
      return <KeyControl action={def.action} value={String(value)} />;
    case 'slider':
      return (
        <div class="slider">
          <input type="range" min={def.min} max={def.max} step={def.step ?? 5} value={Number(value)}
            onInput={e => change(Number(e.currentTarget.value))} />
          <span>{def.unit === 'seconds' ? `${(Number(value) / 1000).toFixed(2).replace(/0$/, '')}s` : `${value}%`}</span>
        </div>
      );
  }
}

// A theme is a plain CSS file: load one, save the current one, or edit it here. Typed CSS lands at once.
function CssEditor({ def, value }: { def: SettingDef; value: string }) {
  const file = useRef<HTMLInputElement>(null);
  return (
    <div class="css-editor">
      <textarea class="css" spellcheck={false} rows={5} placeholder="#prompt { border-color: #dfa23c; }" value={value}
        onInput={e => set(def.key, e.currentTarget.value)} />
      <div class="css-buttons">
        <button onClick={() => file.current?.click()}>Import</button>
        <button onClick={() => saveText(value, 'forge-theme.css', 'text/css')}>Export</button>
        <button onClick={() => set(def.key, '')}>Clear</button>
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
