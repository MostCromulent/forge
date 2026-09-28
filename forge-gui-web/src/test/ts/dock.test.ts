import { describe, expect, it } from 'vitest';
import { unreadSince } from '../../main/ts/dock';
import type { ChatEntry } from '../../main/ts/model';

const line = (from: string, text: string, earlier = false): ChatEntry => ({ from, text, earlier });

describe('unread chat', () => {
  it('counts what others said after the last line seen', () => {
    const seen = line('Ann', 'hi');
    const chat = [line('Bea', 'hello'), seen, line('Bea', 'gl'), line('Cal', 'hf')];
    expect(unreadSince(chat, seen, 'Ann')).toBe(2);
  });

  it('passes over your own lines, the server\'s and lines said before you arrived', () => {
    const chat = [line('Bea', 'old news', true), line('', 'Cal joined'), line('Ann', 'hi'), line('Bea', 'hello')];
    expect(unreadSince(chat, undefined, 'Ann')).toBe(1);
  });

  it('counts a replayed chat from its start, as its lines are new objects', () => {
    const seen = line('Bea', 'hello');
    const replayed = [line('Bea', 'hello', true), line('Cal', 'said while you were away')];
    expect(unreadSince(replayed, seen, 'Ann')).toBe(1);
  });
});
