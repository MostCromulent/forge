// The button that sets one key: click it, then press the new key

import { useState } from 'preact/hooks';
import { keyName, rebind, type KeyBindings } from './keys';
import { boundKeys, setKeys } from './settings';

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
