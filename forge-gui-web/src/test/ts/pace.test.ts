import { describe, expect, it } from 'vitest';
import { worthSeeing } from '../../main/ts/match/pace';
import type { GameEvent } from '../../main/ts/protocol';

const ME = 1;
const THEM = 2;
const cast = (caster: number): GameEvent => ({ kind: 'cardMoved', card: { ref: 9 }, from: { zone: 'Hand', player: { ref: caster } }, to: { zone: 'Stack' }, caster: { ref: caster } });

describe('worthSeeing', () => {
  it("holds for another player's spell and not for your own", () => {
    expect(worthSeeing([cast(THEM)], [ME])).toBe(true);
    expect(worthSeeing([cast(ME)], [ME])).toBe(false);
  });
  it('does not hold for a card moving between hidden zones', () => {
    const draw: GameEvent = { kind: 'cardMoved', card: { ref: 9 }, from: { zone: 'Library', player: { ref: THEM } }, to: { zone: 'Hand', player: { ref: THEM } } };
    expect(worthSeeing([draw], [ME])).toBe(false);
  });
  it('holds for an attack by another player, and not for a combat with no attackers', () => {
    const attack = { attacker: { ref: 9 }, defender: { ref: ME } };
    expect(worthSeeing([{ kind: 'attackersDeclared', player: { ref: THEM }, attacks: [attack] }], [ME])).toBe(true);
    expect(worthSeeing([{ kind: 'attackersDeclared', player: { ref: THEM }, attacks: [] }], [ME])).toBe(false);
  });
  it("holds for another player's blocks, and not for your own or for none", () => {
    const blocks = [{ blocker: { ref: 9 }, attacker: { ref: 8 } }];
    expect(worthSeeing([{ kind: 'blockersDeclared', player: { ref: THEM }, blocks }], [ME])).toBe(true);
    expect(worthSeeing([{ kind: 'blockersDeclared', player: { ref: ME }, blocks }], [ME])).toBe(false);
    expect(worthSeeing([{ kind: 'blockersDeclared', player: { ref: THEM }, blocks: [] }], [ME])).toBe(false);
  });
  it("holds for another player's ability on the stack and as it resolves, and not for your own", () => {
    expect(worthSeeing([{ kind: 'stackAdded', player: { ref: THEM } }], [ME])).toBe(true);
    expect(worthSeeing([{ kind: 'stackResolved', player: { ref: THEM } }], [ME])).toBe(true);
    expect(worthSeeing([{ kind: 'stackAdded', player: { ref: ME } }, { kind: 'stackResolved', player: { ref: ME } }], [ME])).toBe(false);
  });
  it('holds for a life change, counters and an attachment', () => {
    expect(worthSeeing([{ kind: 'lifeChanged', player: { ref: ME }, from: 20, to: 23 }], [ME])).toBe(true);
    expect(worthSeeing([{ kind: 'playerCounters', player: { ref: ME } }], [ME])).toBe(true);
    expect(worthSeeing([{ kind: 'cardCounters', card: { ref: 9 } }], [ME])).toBe(true);
    expect(worthSeeing([{ kind: 'cardAttached', card: { ref: 9 }, to: { ref: 8 } }], [ME])).toBe(true);
  });
});
