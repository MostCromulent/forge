// The keys a player can change during a match, in a table of their own

import { useState } from 'preact/hooks';
import { keyName, rebind, type KeyBindings } from './keys';
import { SETTINGS, boundKeys, defaultKeys, setKeys, setting } from './settings';

export function KeysDialog({ close }: { close: () => void }) {
  const defaults = defaultKeys();
  return (
    <div id="options" class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
      <div class="options-dialog keys-dialog" role="dialog" aria-label="Keys">
        <header>
          <b>Keys</b>
          <span class="spacer" />
          <button class="close" title="Close (Esc)" onClick={close}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18" /><path d="m6 6 12 12" /></svg>
          </button>
        </header>
        <div class="rows">
          <table class="keys-table">
            <thead><tr><th>Action</th><th>Key</th><th>Default</th></tr></thead>
            <tbody>
              {SETTINGS.flatMap(def => (def.type === 'key' ? [def] : [])).map(def => (
                <tr key={def.key}>
                  <td>{def.label}</td>
                  <td><KeyControl action={def.action} value={String(setting(def.key))} /></td>
                  <td class="default">{keyName(defaults[def.action])}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <footer>
          <span class="hint">Click a key, then press the new one.</span>
          <button onClick={() => setKeys(defaults)}>Reset to defaults</button>
        </footer>
      </div>
    </div>
  );
}

// Click, then press the new key. Escape leaves the key as it was.
export function KeyControl({ action, value }: { action: keyof KeyBindings; value: string }) {
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
      {listening ? 'Press a key' : keyName(value)}
    </button>
  );
}
