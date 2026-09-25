import { describe, expect, it } from 'vitest';
import { mayRoll } from '../../main/ts/planes';
import type { GameView } from '../../main/ts/protocol';

const g = (more: Partial<GameView> = {}): GameView => ({ $key: 1, PlayerTurn: { ref: 7 }, Phase: 'MAIN1', Stack: [], ...more });

describe('when the planar die can be rolled', () => {
  // Fails if the button offers a roll the engine would refuse: not the viewer's turn, not a main phase, or a spell waiting
  it('only with priority, on your own turn, in a main phase, with an empty stack', () => {
    expect(mayRoll({ priority: true }, g(), 7)).toBe(true);
    expect(mayRoll({ priority: true }, g({ Phase: 'MAIN2' }), 7)).toBe(true);
    expect(mayRoll({ priority: false }, g(), 7)).toBe(false);
    expect(mayRoll({ priority: true }, g(), 8)).toBe(false);
    expect(mayRoll({ priority: true }, g({ Phase: 'COMBAT_BEGIN' }), 7)).toBe(false);
    expect(mayRoll({ priority: true }, g({ Stack: [{ ref: 3 }] }), 7)).toBe(false);
    expect(mayRoll(null, g(), 7)).toBe(false);
  });
});
