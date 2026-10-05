// The pack dial's geometry, kept apart from drawing so it can be tested without a page, with angles in radians and y growing downwards

/** Seat i of n: seat 0 at the bottom, and the seats after it running anticlockwise on screen, towards the player's right. */
export function seatAngle(i: number, n: number): number {
  return Math.PI / 2 - (i * 2 * Math.PI) / n;
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
