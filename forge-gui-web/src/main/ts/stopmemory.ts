// A guest's phase stops, remembered by its browser. The server keeps a guest's stops only as long as its session,
// so a browser that reaches a server without them (after a restart) gives them back, once per connection.
//
// A stop is toggled on the server, so the browser learns the result only from the next controls message, and a
// message already on its way can still carry the old stops. The memory therefore follows the server only once the
// server shows the stops it was given back; until then it would be remembering what it is about to replace.

import type { Controls, PhaseType } from './protocol';

export interface RememberedStops {
  mine: PhaseType[];
  others: PhaseType[];
}

export interface StopStore {
  load(): RememberedStops | null;
  save(stops: RememberedStops): void;
}

export interface StopMemory {
  /** A new connection: the server may have forgotten the stops, so they are offered again. */
  reset(): void;
  onControls(controls: Controls, guest: boolean): void;
}

export function createStopMemory(store: StopStore, setStops: (mine: boolean, phases: PhaseType[]) => void): StopMemory {
  let phase: 'restore' | 'waiting' | 'following' = 'restore';
  let given: RememberedStops | null = null;
  return {
    reset() {
      phase = 'restore';
      given = null;
    },
    onControls(controls, guest) {
      // The host's stops are Forge's preferences, which outlive the server
      if (!guest) {
        return;
      }
      const now = { mine: controls.myStops, others: controls.otherStops };
      if (phase === 'restore') {
        const saved = store.load();
        if (saved && !same(saved, now)) {
          if (!sameRow(saved.mine, now.mine)) setStops(true, saved.mine);
          if (!sameRow(saved.others, now.others)) setStops(false, saved.others);
          given = saved;
          phase = 'waiting';
          return;
        }
        phase = 'following';
      }
      if (phase === 'waiting') {
        if (!given || !same(given, now)) {
          return;
        }
        phase = 'following';
      }
      store.save(now);
    },
  };
}

const sameRow = (a: readonly PhaseType[], b: readonly PhaseType[]): boolean =>
  a.length === b.length && a.every(p => b.includes(p));
const same = (a: RememberedStops, b: RememberedStops): boolean => sameRow(a.mine, b.mine) && sameRow(a.others, b.others);

/** The browser's storage, which can be unavailable; the stops then last as long as the session. */
export function localStopStore(key: string): StopStore {
  return {
    load() {
      try {
        return JSON.parse(localStorage.getItem(key) ?? 'null');
      } catch {
        return null;
      }
    },
    save(stops) {
      try {
        localStorage.setItem(key, JSON.stringify(stops));
      } catch {
        // Nowhere to keep them; the server has them until it stops
      }
    },
  };
}
