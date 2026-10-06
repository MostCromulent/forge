// A guest's phase stops, remembered by its browser because the server keeps them only as long as its session

import type { Controls, PhaseType } from '../protocol';
import { storeJson, storedJson } from '../storage';

const KEY = 'forge.guestStops';

/** Gives the server the stops this browser remembers, if it remembers any. */
export function restoreStops(setStops: (mine: boolean, phases: PhaseType[]) => void): void {
  const saved = storedJson<{ mine: PhaseType[]; others: PhaseType[] } | null>(KEY, null);
  if (saved) {
    setStops(true, saved.mine);
    setStops(false, saved.others);
  }
}

/** Remembers a guest's stops as the server has them. The host's are Forge's preferences, which outlive the server. */
export function rememberStops(controls: Controls): void {
  storeJson(KEY, { mine: controls.myStops, others: controls.otherStops });
}
