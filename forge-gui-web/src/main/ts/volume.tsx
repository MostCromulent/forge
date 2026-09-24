// The volume control: a small panel over the speaker button beside the options, so the sound can be turned down
// in a moment without opening the whole options list.

import { SETTINGS, set, setting } from './settings';

const VOLUMES = SETTINGS.filter(def => def.volume);

/** Nothing plays, which the speaker button shows crossed out. */
export const isSilent = (): boolean => VOLUMES.every(def => Number(setting(def.key)) <= 0);

export function Volume({ close }: { close: () => void }) {
  const button = document.querySelector('#prompt .volume')?.getBoundingClientRect();
  // Opens upwards from the button and leftwards from its edge, as the console sits in the bottom-right corner
  const at = button ? { right: `${window.innerWidth - button.right}px`, bottom: `${window.innerHeight - button.top + 6}px` } : {};
  return (
    <div id="volume" class="backdrop anchored" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
      <div class="volume-panel" role="dialog" aria-label="Volume" style={at}>
        {VOLUMES.map(def => {
          const value = Number(setting(def.key));
          return (
            <label key={def.key} class="volume-row">
              <span>{def.label}</span>
              <input type="range" min={0} max={100} step={5} value={value}
                onInput={e => set(def.key, Number(e.currentTarget.value))} />
              <span class="value">{value > 0 ? `${value}%` : 'Off'}</span>
            </label>
          );
        })}
      </div>
    </div>
  );
}
