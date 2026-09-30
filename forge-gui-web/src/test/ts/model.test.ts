import { describe, expect, it } from 'vitest';
import { applyState, createModel } from '../../main/ts/model';
import type { StateMessage } from '../../main/ts/protocol';

const state = (patch: Partial<StateMessage>): StateMessage => ({
  t: 'state', full: false, seq: 1, root: 1, newObjects: {}, deltas: {}, visible: [], localPlayers: [], events: [], ...patch,
});

describe('applying state', () => {
  it('drops a property a delta sets to null, which is the property going back to its default', () => {
    const model = createModel();
    applyState(model, state({ full: true, newObjects: { 1: { Turn: 3, Players: [{ ref: 2 }] }, 2: { Tapped: true } } }));
    applyState(model, state({ deltas: { 2: { Tapped: null } } }));
    expect(model.objects.get(2)).toEqual({ $key: 2 });
  });

  it('treats a null in a new object as the property being left out', () => {
    const model = createModel();
    applyState(model, state({ full: true, newObjects: { 1: { Turn: null } as never } }));
    expect(model.objects.get(1)).toEqual({ $key: 1 });
  });

  it('forgets an object nothing reaches any more', () => {
    const model = createModel();
    applyState(model, state({ full: true, newObjects: { 1: { Players: [{ ref: 2 }] }, 2: {} } }));
    applyState(model, state({ deltas: { 1: { Players: [] } } }));
    expect([...model.objects.keys()]).toEqual([1]);
  });

  it('keeps what happened for the next frame, and forgets it when a whole new table arrives', () => {
    const model = createModel();
    const moved = { kind: 'cardMoved' as const, card: { ref: 5 } };
    applyState(model, state({ full: true, newObjects: { 1: {} }, events: [moved] }));
    applyState(model, state({ events: [moved] }));
    expect(model.events).toHaveLength(2);
    applyState(model, state({ full: true, newObjects: { 1: {} } }));
    expect(model.events).toEqual([]);
  });
});
