import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { holdFor, initPace, pace, resetPace } from '../../main/ts/pace';
import type { GameEvent, ServerMessage } from '../../main/ts/protocol';

const state = (seq: number, events: GameEvent[] = [], full = false): ServerMessage => ({
  t: 'state', full, seq, root: 1, newObjects: {}, deltas: {}, visible: [], localPlayers: [], events,
});
const prompt = (priority: boolean): ServerMessage => ({
  t: 'prompt', message: '', priority, ok: { label: '', enabled: priority }, cancel: { label: '', enabled: false },
  focusOk: false, paying: false, selectable: [], selectableMin: 0, selectablePlayers: [], highlighted: [],
});
const blocks: GameEvent = { kind: 'blockersDeclared', player: { ref: 2 }, blocks: [{ attacker: { ref: 10 }, blocker: { ref: 20 } }] };
const cast: GameEvent = { kind: 'cardMoved', card: { ref: 5 }, from: { zone: 'Hand', player: { ref: 2 } }, to: { zone: 'Stack' } };
const tapped = state(0);
const seqs = (got: ServerMessage[]) => got.map(m => (m.t === 'state' ? m.seq : m.t));

describe('pacing the computer\'s play', () => {
  let got: ServerMessage[];

  beforeEach(() => {
    vi.useFakeTimers();
    got = [];
    resetPace();
    initPace(m => got.push(m));
  });
  afterEach(() => vi.useRealTimers());

  it('shows an update at once, and holds the next back for as long as the first takes to see', () => {
    pace(state(1, [blocks]));
    pace(state(2));
    expect(seqs(got)).toEqual([1]);
    vi.advanceTimersByTime(holdFor(state(1, [blocks])) - 1);
    expect(seqs(got)).toEqual([1]);
    vi.advanceTimersByTime(1);
    expect(seqs(got)).toEqual([1, 2]);
  });

  // Blockers flashed past unseen when every update was held alike and a backlog was dumped
  it('holds a block and a cast far longer than a quiet update', () => {
    expect(holdFor(state(1, [blocks]))).toBeGreaterThanOrEqual(1000);
    expect(holdFor(state(1, [cast]))).toBeGreaterThanOrEqual(800);
    expect(holdFor(tapped)).toBeLessThan(300);
  });

  it('never asks a question before the board it is about has been shown, but hurries to it', () => {
    pace(state(1, [blocks]));
    pace(state(2, [blocks]));
    pace(prompt(true));
    expect(seqs(got)).toEqual([1]);
    // With a question waiting, the two blocks ahead of it play out in less time than one takes at full pace
    vi.advanceTimersByTime(holdFor(state(1, [blocks])) * 0.7);
    expect(seqs(got)).toEqual([1, 2, 'prompt']);
  });

  it('holds nothing while you hold priority, so what you do shows at once', () => {
    pace(prompt(true));
    pace(state(1, [cast]));
    pace(state(2, [cast]));
    expect(seqs(got)).toEqual(['prompt', 1, 2]);
  });

  it('lets a whole new table straight through, with whatever was still waiting', () => {
    pace(state(1, [blocks]));
    pace(state(2));
    pace(state(3, [], true));
    expect(seqs(got)).toEqual([1, 2, 3]);
  });

  it('never falls more than a few seconds behind the game', () => {
    for (let seq = 1; seq <= 40; seq++) pace(state(seq, [blocks]));
    vi.advanceTimersByTime(6000);
    expect(got).toHaveLength(40);
  });

  it('never holds back what is not about the board', () => {
    pace(state(1, [blocks]));
    pace(state(2));
    pace({ t: 'chat', from: 'Bob', text: 'hi' } as ServerMessage);
    expect(got.map(m => m.t)).toEqual(['state', 'chat']);
  });
});
