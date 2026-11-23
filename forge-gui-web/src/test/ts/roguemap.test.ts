import { expect, test } from 'vitest';
import { fit, layout, type Link, type Spot } from '../../main/ts/rogue/maplayout';

const plane: Spot = { side: false, boss: false };
const side: Spot = { side: true, boss: false };
/** Eleven rows as the generator makes them: plane rows and side rows by turns, of two to four, and the boss on top. */
const ROWS: Spot[][] = [3, 4, 3, 3, 4, 3, 3, 4, 2, 3].map((n, r) => Array(n).fill(r % 2 ? side : plane)).concat([[{ side: false, boss: true }]]);
const LINKS: Link[] = ROWS.slice(0, -1).flatMap((row, r) => row.map((_, i): Link => [r, i, Math.min(i, ROWS[r + 1].length - 1)]));
const centre = (b: { x: number; w: number }) => b.x + b.w / 2;

// Fails if the nodes of a row are not on the shared grid, spread unevenly or off the middle of the map
test('neighbours are evenly spaced in every row', () => {
  for (const phone of [false, true]) {
    const map = layout(ROWS, LINKS, phone);
    for (const row of map.boxes) {
      const gaps = row.slice(1).map((b, i) => centre(b) - centre(row[i]));
      expect(new Set(gaps).size).toBeLessThanOrEqual(1);
      expect((centre(row[0]) + centre(row.at(-1)!)) / 2).toBe(map.width / 2);
    }
    expect(centre(map.boxes[0][1]) - centre(map.boxes[0][0])).toBe(phone ? 92 : 218);
  }
});

// Fails if a line leaves or reaches a node on a curve, which would cut across the tile's corner
test('a path runs straight at both ends', () => {
  for (const d of layout(ROWS, LINKS, false).paths) {
    const commands = d.match(/[A-Z]/g)!;
    expect(commands[1]).toBe('V');
    expect(commands.at(-1)).toBe('V');
  }
});

// Fails if the whole map overflows its box at the zoom fit gives it
test('the whole map fits its box', () => {
  for (const phone of [false, true]) {
    const map = layout(ROWS, LINKS, phone);
    for (const box of [{ width: 1088, height: 788 }, { width: 366, height: 474 }, { width: 2000, height: 4000 }]) {
      const z = fit(map, box);
      expect(z).toBeLessThanOrEqual(1);
      expect(map.width * z).toBeLessThanOrEqual(box.width - 27);
      expect(map.height * z).toBeLessThanOrEqual(box.height - 27);
    }
  }
});
