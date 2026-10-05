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
});
