// The keys a player can change during a match, in a table of their own

import { useState } from 'preact/hooks';
import { keyName, rebind, type KeyBindings } from './keys';
import { OptionsDialog } from './options';
import { SETTINGS, boundKeys, defaultKeys, setKeys, setting } from './settings';

export function KeysDialog({ close }: { close: () => void }) {
  const defaults = defaultKeys();
  return (
    <OptionsDialog title="Keys" kind="keys-dialog" close={close} footer={<>
      <span class="hint">Click a key, then press the new one.</span>
      <button onClick={() => setKeys(defaults)}>Reset to defaults</button>
    </>}>
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
    </OptionsDialog>
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
