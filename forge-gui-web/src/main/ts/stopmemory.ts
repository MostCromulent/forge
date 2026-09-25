// A guest's phase stops, remembered by its browser. The server keeps a guest's stops only as long as its session, so
// a browser gives them back whenever it connects, before any game opens: the game's players are seeded with them,
// and nothing has to be corrected once play has begun. Setting a row is the same whatever the server had, so the
// browser need not know what that was.

import type { Controls, PhaseType } from './protocol';
import { storeJson, storedJson } from './storage';

export interface RememberedStops {
  mine: PhaseType[];
  others: PhaseType[];
}

export interface StopStore {
  load(): RememberedStops | null;
  save(stops: RememberedStops): void;
}

export interface StopMemory {
  /** Gives the server the stops this browser remembers, if it remembers any. */
  restore(setStops: (mine: boolean, phases: PhaseType[]) => void): void;
  /** Remembers a guest's stops as the server has them. The host's are Forge's preferences, which outlive the server. */
  onControls(controls: Controls, guest: boolean): void;
}

export function createStopMemory(store: StopStore): StopMemory {
  return {
    restore(setStops) {
      const saved = store.load();
      if (saved) {
        setStops(true, saved.mine);
        setStops(false, saved.others);
      }
    },
    onControls(controls, guest) {
      if (guest) {
        store.save({ mine: controls.myStops, others: controls.otherStops });
      }
    },
  };
}

/** The browser's storage, which can be unavailable; the stops then last as long as the session. */
export function localStopStore(key: string): StopStore {
  return {
    load: () => storedJson(key, null),
    save: stops => storeJson(key, stops),
  };
}
