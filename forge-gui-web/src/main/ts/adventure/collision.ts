// Walking on the adventure world map: which tiles block, and how a move slides along them. Ported from the mobile client's
// World.collidingTile and GameStage.adjustMovement, so the player stops where it stops on mobile

export interface Box { x: number; y: number; w: number; h: number }

/** The world's tiles. y counts up from the bottom of the world, as the mobile client counts it. */
export class Grid {
  constructor(readonly width: number, readonly height: number, readonly tileSize: number,
    private readonly blocked: Uint8Array, private readonly biomes: Uint8Array) {}

  /** The server's grid picture, top row first: red is the tile's highest biome, green is 255 where the tile blocks. */
  static fromImage(width: number, height: number, tileSize: number, rgba: Uint8ClampedArray): Grid {
    const blocked = new Uint8Array(width * height);
    const biomes = new Uint8Array(width * height);
    for (let row = 0; row < height; row++) {
      const y = height - 1 - row;
      for (let x = 0; x < width; x++) {
        const at = (row * width + x) * 4;
        biomes[y * width + x] = rgba[at];
        blocked[y * width + x] = rgba[at + 1] === 255 ? 1 : 0;
      }
    }
    return new Grid(width, height, tileSize, blocked, biomes);
  }

  static async load(url: string, width: number, height: number, tileSize: number): Promise<Grid> {
    const image = await createImageBitmap(await (await fetch(url)).blob());
    const canvas = new OffscreenCanvas(width, height);
    const g = canvas.getContext('2d')!;
    g.drawImage(image, 0, 0);
    return Grid.fromImage(width, height, tileSize, g.getImageData(0, 0, width, height).data);
  }

  /** Tiles outside the world block, as World.isColliding treats them. */
  colliding(tx: number, ty: number): boolean {
    if (tx < 0 || ty < 0 || tx >= this.width || ty >= this.height) return true;
    return this.blocked[ty * this.width + tx] === 1;
  }

  biome(tx: number, ty: number): number {
    if (tx < 0 || ty < 0 || tx >= this.width || ty >= this.height) return 0;
    return this.biomes[ty * this.width + tx];
  }

  /** Whether any corner of the box is on a blocking tile. Java's int cast truncates toward zero, so Math.trunc matches it. */
  collidingRect(x: number, y: number, w: number, h: number): boolean {
    const left = Math.trunc(x / this.tileSize);
    const bottom = Math.trunc(y / this.tileSize);
    const right = Math.trunc((x + w) / this.tileSize);
    const top = Math.trunc((y + h) / this.tileSize);
    return this.colliding(left, bottom) || this.colliding(left, top) || this.colliding(right, top) || this.colliding(right, bottom);
  }
}

const STEP = 0.2;

/** Shortens one axis of a move by a step toward zero. */
const shorten = (v: number) => (v >= 0 ? Math.max(0, v - STEP) : Math.min(0, v + STEP));

/**
 * The part of a move the player can make. Each axis is shortened in turn until the box fits; when both fit, the longer
 * move wins. A player already standing in a blocked tile, as after flying, may move freely until it is out.
 */
export function adjustMovement(grid: Grid, dx: number, dy: number, box: Box, flying = false): [number, number] {
  const blocked = (mx: number, my: number) => !flying && grid.collidingRect(box.x + mx, box.y + my, box.w, box.h);
  if (flying || blocked(0, 0)) return [dx, dy];
  let xx = dx;
  let foundX = false;
  for (;;) {
    if (!blocked(xx, dy)) {
      foundX = true;
      break;
    }
    if (xx === 0) break;
    xx = shorten(xx);
  }
  let yy = dy;
  let foundY = false;
  for (;;) {
    if (!blocked(dx, yy)) {
      foundY = true;
      break;
    }
    if (yy === 0) break;
    yy = shorten(yy);
  }
  if (foundX && foundY) return Math.hypot(xx, dy) > Math.hypot(dx, yy) ? [xx, dy] : [dx, yy];
  if (foundY) return [dx, yy];
  if (foundX) return [xx, dy];
  return [0, 0];
}
