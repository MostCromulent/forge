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
  const memory = createStopMemory(store, (mine, phases) => sent.push([mine, phases]));
  return { store, sent, memory };
}

describe('a guest\'s remembered phase stops', () => {
  it('gives a server that has forgotten them only the rows that differ', () => {
    const { sent, memory } = setup({ mine: ['MAIN1', 'MAIN2'], others: ['END_OF_TURN'] });
    memory.onControls(controls(['MAIN1', 'MAIN2'], []), true);
    expect(sent).toEqual([[false, ['END_OF_TURN']]]);
  });

  // A controls message already on its way still carries the server's old stops
  it('does not remember what it is replacing before the server has taken them', () => {
    const { store, memory } = setup({ mine: ['MAIN1'], others: [] });
    memory.onControls(controls(['MAIN2'], []), true);
    memory.onControls(controls(['MAIN2'], []), true);
    expect(store.stops).toEqual({ mine: ['MAIN1'], others: [] });
    memory.onControls(controls(['MAIN1'], []), true);
    memory.onControls(controls(['MAIN1', 'COMBAT_DECLARE_ATTACKERS'], []), true);
    expect(store.stops).toEqual({ mine: ['MAIN1', 'COMBAT_DECLARE_ATTACKERS'], others: [] });
  });

  it('offers them again only on a new connection', () => {
    const { sent, memory } = setup({ mine: ['MAIN1'], others: [] });
    memory.onControls(controls(['MAIN1'], []), true);
    memory.onControls(controls(['MAIN1'], []), true);
    expect(sent).toEqual([]);
    // The server restarted, and has its defaults again
    memory.reset();
    memory.onControls(controls([], []), true);
    expect(sent).toEqual([[true, ['MAIN1']]]);
  });

  it('leaves the host\'s alone, which are Forge\'s preferences', () => {
    const { store, sent, memory } = setup({ mine: ['MAIN1'], others: [] });
    memory.onControls(controls([], []), false);
    expect(sent).toEqual([]);
    expect(store.stops).toEqual({ mine: ['MAIN1'], others: [] });
  });
});
