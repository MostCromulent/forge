import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { countdown, dropCountdown, finishCountdown, initAutoPass, startCountdown } from '../../main/ts/autopass';
import type { AutoPassRequest } from '../../main/ts/protocol';

const request = (id: number, delay = 200): AutoPassRequest => ({ t: 'request', id, kind: 'autoPass', delay, default: true });

describe('a pass on its way', () => {
  let answers: [number, boolean][];

  beforeEach(() => {
    vi.useFakeTimers();
    answers = [];
    dropCountdown();
    initAutoPass((id, go) => answers.push([id, go]), () => {});
  });
  afterEach(() => vi.useRealTimers());

  it('goes ahead once the button has filled, and not before', () => {
    startCountdown(request(1));
    const ms = countdown()!.ms;
    vi.advanceTimersByTime(ms - 1);
    expect(answers).toEqual([]);
    vi.advanceTimersByTime(1);
    expect(answers).toEqual([[1, true]]);
    expect(countdown()).toBeNull();
  });

  it('fills for the same length whatever pause the server suggests', () => {
    startCountdown(request(1, 5000));
    const long = countdown()!.ms;
    dropCountdown();
    startCountdown(request(2, 50));
    expect(countdown()!.ms).toBe(long);
  });

  it('can be stopped, which answers once and only once', () => {
    startCountdown(request(1));
    finishCountdown(false);
    vi.advanceTimersByTime(10_000);
    expect(answers).toEqual([[1, false]]);
  });

  it('is forgotten without an answer when the server asks again after a reconnect', () => {
    startCountdown(request(1));
    dropCountdown();
    vi.advanceTimersByTime(10_000);
    expect(answers).toEqual([]);
  });
});
