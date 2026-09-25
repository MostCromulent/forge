import { describe, expect, it } from 'vitest';
import { dialTokens, nextFrom, seatAngle } from '../../main/ts/dial';

const gap = (n: number) => 2 * Math.PI / n;
/** Whether angle a lies strictly between from and from + span, walking in span's sign. */
const between = (a: number, from: number, span: number) => span > 0 ? a > from && a < from + span : a < from && a > from + span;

describe('the pack dial', () => {
  // Fails if a pack is lost or drawn twice
  it('draws one token per pack', () => {
    const depths = [1, 1, 1, 0, 1, 1, 3, 1];
    expect(dialTokens(depths, 1)).toHaveLength(9);
    expect(dialTokens(depths, -1)).toHaveLength(9);
  });

  // Fails if waiting packs queue on the side packs leave from, which flips wrongly when the pass direction changes
  it('queues waiting packs on the side they arrive from', () => {
    const depths = [1, 1, 1, 0, 1, 1, 3, 1];
    for (const direction of [1, -1] as const) {
      const waiting = dialTokens(depths, direction).filter(t => t.seat === 6 && !t.inHand);
      expect(waiting).toHaveLength(2);
      for (const t of waiting) expect(between(t.angle, seatAngle(6, 8), direction * gap(8))).toBe(true);
    }
  });

  // Fails if the pack in hand is not drawn at its seat
  it('draws the pack in hand at its seat', () => {
    const inHand = dialTokens([2, 0, 0, 0], 1).filter(t => t.inHand);
    expect(inHand).toHaveLength(1);
    expect(inHand[0].angle).toBeCloseTo(seatAngle(0, 4));
  });

  // Fails if "next pack from" names the wrong neighbour for the pass direction
  it('names the seat your next pack comes from', () => {
    expect(nextFrom([0, 1, 1, 1, 1, 1, 3, 1], 1)).toBe(7);
    expect(nextFrom([0, 1, 1, 1, 1, 1, 3, 1], -1)).toBe(1);
    expect(nextFrom([0, 0, 0, 2, 0, 0, 0, 0], 1)).toBe(3);
    expect(nextFrom([0, 0, 0, 0], 1)).toBeNull();
  });
});
