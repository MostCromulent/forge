// The cog dialog: one scrolling list of settings with a search box, and the concede button under it

import { useEffect, useRef, useState } from 'preact/hooks';
import { SETTINGS, playmatList, set, setting, type SettingDef } from './settings';
import { playmatUrl } from './looks';

export function Options({ close, concede }: { close: () => void; concede: () => void }) {
  const [query, setQuery] = useState('');
  const [armed, setArmed] = useState(false);
  const search = useRef<HTMLInputElement>(null);
  useEffect(() => {
    search.current?.focus();
  }, []);
  const q = query.trim().toLowerCase();
  const shown = SETTINGS.filter(def => !def.volume).filter(def => !q || `${def.section} ${def.label} ${def.hint ?? ''}`.toLowerCase().includes(q));
  return (
    <div id="options" class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
      <div class="options-dialog" role="dialog" aria-label="Options">
        <header>
          <b>Options</b>
          <input ref={search} class="search" type="search" placeholder="Search settings" aria-label="Search settings"
            value={query} onInput={e => setQuery(e.currentTarget.value)} />
          <button class="close" title="Close (Esc)" onClick={close}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18" /><path d="m6 6 12 12" /></svg>
          </button>
        </header>
        <div class="rows">
          {shown.flatMap((def, i) => [
            ...(def.section !== shown[i - 1]?.section ? [<h4 key={`section ${def.section}`}>{def.section}</h4>] : []),
            <Row key={def.key} def={def} />,
          ])}
          {!shown.length && <p class="hint">No setting matches that.</p>}
        </div>
        <footer>
          <span class="hint">Changes apply at once.</span>
          <button class={armed ? 'concede armed' : 'concede'} onClick={() => {
            if (!armed) {
              setArmed(true);
              return;
            }
            concede();
            close();
          }}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 22V4a1 1 0 0 1 .4-.8A6 6 0 0 1 8 2c3 0 5 2 7.333 2q2 0 3.067-.8A1 1 0 0 1 20 4v10a1 1 0 0 1-.4.8A6 6 0 0 1 16 16c-3 0-5-2-8-2a6 6 0 0 0-4 1.528" /></svg>
            {armed ? 'Confirm concede' : 'Concede game'}
          </button>
        </footer>
      </div>
    </div>
  );
}

function Row({ def }: { def: SettingDef }) {
  return (
    <div class={def.type === 'css' ? 'setting wide' : 'setting'}>
      <div>
        <div>{def.label}</div>
        {def.hint && <div class="hint">{def.hint}</div>}
      </div>
      <Control def={def} />
    </div>
  );
}

function Control({ def }: { def: SettingDef }) {
  const value = setting(def.key);
  switch (def.type) {
    case 'toggle':
      return <button class={value ? 'switch on' : 'switch'} role="switch" aria-checked={!!value}
        onClick={() => set(def.key, !setting(def.key))} />;
    case 'choice':
      return (
        <div class="choice">
          {def.options.map(([v, label]) => <button key={v} class={String(value) === v ? 'on' : ''} onClick={() => set(def.key, v)}>{label}</button>)}
        </div>
      );
    case 'playmat':
      return <Playmats def={def} value={String(value ?? '')} />;
    case 'css':
      return <CssEditor def={def} value={String(value ?? '')} />;
    case 'slider':
      return (
        <div class="slider">
          <input type="range" min={def.min} max={def.max} step={def.step ?? 5} value={Number(value)}
            onInput={e => set(def.key, Number(e.currentTarget.value))} />
          <span>{def.unit === 'seconds' ? `${(Number(value) / 1000).toFixed(2).replace(/0$/, '')}s` : `${value}%`}</span>
        </div>
      );
  }
}

// The playmats an installation has are its skins' own table images, so the picker is built from what the server sends
function Playmats({ def, value }: { def: SettingDef; value: string }) {
  return (
    <div class="mat-grid">
      {[{ id: '', label: 'Plain' }, ...playmatList()].map(mat => (
        <button key={mat.id} class={mat.id === value ? 'mat chosen' : 'mat'} title={mat.label}
          style={{ backgroundImage: mat.id ? `url("${playmatUrl(mat.id)}")` : 'none' }}
          onClick={() => set(def.key, mat.id)} />
      ))}
    </div>
  );
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
        <button onClick={() => saveCss(value)}>Export</button>
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

function saveCss(text: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/css' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = 'forge-theme.css';
  link.click();
  URL.revokeObjectURL(url);
}
