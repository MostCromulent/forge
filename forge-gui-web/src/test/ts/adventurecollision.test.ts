import { describe, expect, it } from 'vitest';
import { adjustMovement, Grid } from '../../main/ts/adventure/collision';

const TILE = 16;

/** An 8 by 8 grid whose blocked tiles are listed as [x, y], with y counted up from the bottom as the world counts it. */
function grid(blocked: [number, number][]): Grid {
  const cells = new Uint8Array(64);
  for (const [x, y] of blocked) cells[y * 8 + x] = 1;
  return new Grid(8, 8, TILE, cells, new Uint8Array(64));
}

const column = (x: number) => Array.from({ length: 8 }, (_, y) => [x, y] as [number, number]);
const row = (y: number) => Array.from({ length: 8 }, (_, x) => [x, y] as [number, number]);

describe('adjustMovement', () => {
  it('slides along a wall: the blocked axis shrinks and the free one keeps its move', () => {
    const g = grid(column(4));
    const box = { x: 4 * TILE - 10.5, y: 40, w: 10, h: 6 };
    const [dx, dy] = adjustMovement(g, 3, 2, box);
    expect(dx).toBeCloseTo(0.4, 4);
    expect(dy).toBe(2);
  });

  it('stops at a corner', () => {
    const g = grid([...column(4), ...row(4)]);
    const box = { x: 4 * TILE - 10.5, y: 4 * TILE - 6.5, w: 10, h: 6 };
    expect(adjustMovement(g, 3, 3, box)).toEqual([0, 0]);
  });

  it('prefers the longer of two free moves, as GameStage does', () => {
    // Only the diagonal tile blocks, so keeping either axis works; keeping x moves further
    const g = grid([[5, 4]]);
    const box = { x: 5 * TILE - 10.5, y: 4 * TILE - 6.5, w: 10, h: 6 };
    const [dx, dy] = adjustMovement(g, 3, 1, box);
    expect(dx).toBe(3);
    expect(dy).toBeCloseTo(0.4, 4);
  });

  it('lets a player standing inside collision walk out, as WorldStage does after flying', () => {
    const g = grid([[2, 2]]);
    const box = { x: 2 * TILE + 2, y: 2 * TILE + 2, w: 10, h: 6 };
    expect(adjustMovement(g, -3, 0, box)).toEqual([-3, 0]);
  });

  it('ignores collision while flying', () => {
    const g = grid(column(4));
    const box = { x: 4 * TILE - 10.5, y: 40, w: 10, h: 6 };
    expect(adjustMovement(g, 3, 2, box, true)).toEqual([3, 2]);
  });
});

describe('Grid', () => {
  it('treats tiles outside the world as blocked', () => {
    const g = grid([]);
    expect(g.colliding(-1, 0)).toBe(true);
    expect(g.colliding(8, 0)).toBe(true);
    expect(g.colliding(0, 8)).toBe(true);
    expect(g.colliding(3, 3)).toBe(false);
  });

  it('reads the server grid image top row first: red is the biome, green marks blocked tiles', () => {
    // A 2 by 2 image: top-left blocked biome 1, bottom-right biome 3
    const rgba = new Uint8ClampedArray([1, 255, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255, 3, 0, 0, 255]);
    const g = Grid.fromImage(2, 2, TILE, rgba);
    expect(g.colliding(0, 1)).toBe(true);
    expect(g.biome(0, 1)).toBe(1);
    expect(g.colliding(1, 0)).toBe(false);
    expect(g.biome(1, 0)).toBe(3);
  });

  it('checks the four corners of a box as World.collidingTile does', () => {
    const g = grid([[3, 3]]);
    expect(g.collidingRect(2 * TILE + 8, 2 * TILE + 8, 10, 10)).toBe(true);
    expect(g.collidingRect(2 * TILE + 1, 2 * TILE + 1, 10, 10)).toBe(false);
  });
});
