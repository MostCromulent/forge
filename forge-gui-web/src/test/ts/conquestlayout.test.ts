import { expect, test } from 'vitest';
import { layout } from '../../main/ts/conquestlayout';

const plane = (regions: number) => ({ rows: 3, cols: 3, regions: Array.from({ length: regions }, () => ({ name: '', art: '', colors: '', conquered: 0, total: 9 })) });

// Fails if regions are not spaced as designed: open ones 46 apart, an open one and a folded one 30, folded ones 10
test('regions stand apart by what they are', () => {
  const l = layout(plane(4), r => r >= 2);
  const w = 3 * 100 + 2 * 14;
  expect(l.regions.map(r => r.x)).toEqual([4, 4 + w + 46, 4 + 2 * w + 46 + 30, 4 + 2 * w + 46 + 30 + 62 + 10]);
  expect(l.regions.map(r => r.folded)).toEqual([false, false, true, true]);
});

// Fails if a place is not put at its step along the road and its lane down it
test('a place stands at its step and lane', () => {
  const l = layout(plane(2), () => false);
  const w = 3 * 100 + 2 * 14;
  expect(l.at(1, 2, 1)).toEqual({ x: 4 + w + 46 + 2 * 114, y: 8 + 92 + 32 + 164 });
});

// Fails if a region of six events, two steps along, is laid out as wide as one of nine
test('a region of two steps is narrower', () => {
  const l = layout({ ...plane(2), rows: 2 }, () => false);
  expect(l.regions[1].x).toBe(4 + 2 * 100 + 14 + 46);
});
