// The pack dial's geometry, kept apart from drawing so it can be tested without a page. Seats sit on a ring in pass
// order with the player (seat 0) at the bottom; angles are radians in screen terms, where y grows downwards.

export interface DialToken {
  seat: number;
  /** 0 for the pack in hand; 1, 2 … for the packs waiting behind it. */
  slot: number;
  inHand: boolean;
  angle: number;
}

/** How far the first waiting pack sits from its seat, and how far apart the rest queue, as parts of the gap between seats. */
const FIRST = 0.27;
const STEP = 0.19;

/** Seat i of n: seat 0 at the bottom, and the seats after it running anticlockwise on screen, towards the player's right. */
export function seatAngle(i: number, n: number): number {
  return Math.PI / 2 - (i * 2 * Math.PI) / n;
}

/**
 * One token per pack. The pack a seat is picking from sits at the seat; the ones waiting queue on the side packs arrive
 * from, which is the previous seat while packs go to the next one (direction 1) and the next seat otherwise.
 */
export function dialTokens(depths: number[], direction: 1 | -1): DialToken[] {
  const n = depths.length;
  const gap = (2 * Math.PI) / n;
  const tokens: DialToken[] = [];
  depths.forEach((depth, seat) => {
    const at = seatAngle(seat, n);
    const waiting = Math.max(0, depth - 1);
    // A long queue closes up so it never reaches the seat it came from
    const step = waiting > 1 ? Math.min(STEP, (1 - FIRST - 0.08) / (waiting - 1)) : STEP;
    for (let slot = 0; slot < depth; slot++) {
      const offset = slot === 0 ? 0 : (FIRST + (slot - 1) * step) * gap;
      tokens.push({ seat, slot, inHand: slot === 0, angle: at + direction * offset });
    }
  });
  return tokens;
}

/** The seat your next pack comes from: walking upstream from you, the first seat holding a pack; null when nobody holds one. */
export function nextFrom(depths: number[], direction: 1 | -1): number | null {
  const n = depths.length;
  for (let k = 1; k < n; k++) {
    const seat = (((-direction * k) % n) + n) % n;
    if (depths[seat] > 0) return seat;
  }
  return null;
}
