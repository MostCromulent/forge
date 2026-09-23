import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { initPace, pace, resetPace } from '../../main/ts/pace';
import type { ServerMessage } from '../../main/ts/protocol';

const state = (seq: number, full = false): ServerMessage => ({
  t: 'state', full, seq, root: 1, newObjects: {}, deltas: {}, visible: [], localPlayers: [], events: [],
});
const prompt = (priority: boolean): ServerMessage => ({
  t: 'prompt', message: '', priority, ok: { label: '', enabled: priority }, cancel: { label: '', enabled: false },
  focusOk: false, paying: false, selectable: [], selectableMin: 0, selectablePlayers: [], highlighted: [],
});
const seqs = (got: ServerMessage[]) => got.map(m => (m.t === 'state' ? m.seq : m.t));

describe('pacing an opponent\'s turn', () => {
  let got: ServerMessage[];

  beforeEach(() => {
    vi.useFakeTimers();
    got = [];
    resetPace();
    initPace(m => got.push(m));
  });
  afterEach(() => vi.useRealTimers());

  it('lets what you watch through one step at a time', () => {
    pace(state(1));
    pace(state(2));
    pace(state(3));
    expect(got).toEqual([]);
    vi.advanceTimersByTime(350);
    expect(seqs(got)).toEqual([1]);
    vi.advanceTimersByTime(700);
    expect(seqs(got)).toEqual([1, 2, 3]);
  });

  it('empties the queue the moment the game wants an answer, so nothing you click is stale', () => {
    pace(state(1));
    pace(state(2));
    pace(prompt(true));
    expect(seqs(got)).toEqual([1, 2, 'prompt']);
    // While you hold priority nothing waits
    pace(state(3));
    expect(seqs(got)).toEqual([1, 2, 'prompt', 3]);
  });

  it('lets a whole new table straight through', () => {
    pace(state(1));
    pace(state(2, true));
    expect(seqs(got)).toEqual([1, 2]);
  });

  it('catches up rather than falling further behind', () => {
    for (let seq = 1; seq <= 9; seq++) pace(state(seq));
    expect(seqs(got)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9]);
  });
});
