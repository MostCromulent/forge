import { describe, expect, it } from 'vitest';
import { createStopMemory, type RememberedStops } from '../../main/ts/stopmemory';
import type { Controls, PhaseType } from '../../main/ts/protocol';

const controls = (mine: PhaseType[], others: PhaseType[]): Controls => ({
  t: 'controls', myStops: mine, otherStops: others, autoPass: false,
  settings: {} as Controls['settings'],
});

function setup(saved: RememberedStops | null) {
  const store = { stops: saved, load: () => store.stops, save: (s: RememberedStops) => { store.stops = s; } };
  const sent: [boolean, PhaseType[]][] = [];
  return { store, sent, memory: createStopMemory(store), setStops: (mine: boolean, phases: PhaseType[]) => sent.push([mine, phases]) };
}

describe('a guest\'s remembered phase stops', () => {
  it('gives both rows back as they were remembered', () => {
    const { sent, memory, setStops } = setup({ mine: ['MAIN1', 'MAIN2'], others: ['END_OF_TURN'] });
    memory.restore(setStops);
    expect(sent).toEqual([[true, ['MAIN1', 'MAIN2']], [false, ['END_OF_TURN']]]);
  });

  it('gives nothing back when it remembers nothing', () => {
    const { sent, memory, setStops } = setup(null);
    memory.restore(setStops);
    expect(sent).toEqual([]);
  });

  it('remembers a guest\'s stops as the server has them', () => {
    const { store, memory } = setup(null);
    memory.onControls(controls(['MAIN1'], ['COMBAT_DECLARE_ATTACKERS']), true);
    expect(store.stops).toEqual({ mine: ['MAIN1'], others: ['COMBAT_DECLARE_ATTACKERS'] });
  });

  it('leaves the host\'s alone, which are Forge\'s preferences', () => {
    const { store, memory } = setup({ mine: ['MAIN1'], others: [] });
    memory.onControls(controls([], []), false);
    expect(store.stops).toEqual({ mine: ['MAIN1'], others: [] });
  });
});
