// Where the map's nodes and lines go. Every node stands on one grid of seven half columns and equal rows, so rows of four, three, two
// and one are centred on the same lines. The map climbs: the first row is at the bottom and the last, the boss's, at the top

export interface Box { x: number; y: number; w: number; h: number }

/** What the layout needs of a node: whether it is a side node, and whether it is the larger boss tile. */
export interface Spot { side: boolean; boss: boolean }

export interface Layout {
  width: number;
  height: number;
  /** Each node's box, by row and place in its row. */
  boxes: Box[][];
  /** Each link's drawing, in the order the links were given. */
  paths: string[];
}

/** A link from a node to one of the next row, each by its row and its place in the row. */
export type Link = [row: number, from: number, to: number];

/** Tile width and height, side node, half column, row, and the boss's width and height. */
const WIDE = [136, 98, 60, 109, 170, 264, 148];
const PHONE = [84, 70, 44, 46, 110, 170, 96];

export function layout(rows: Spot[][], links: Link[], phone: boolean): Layout {
  const [W, TH, S, HALF, ROW, BW, BH] = phone ? PHONE : WIDE;
  const cross = HALF * 6 + W, top = BH / 2 + 22, foot = TH / 2 + 20, total = top + (rows.length - 1) * ROW + foot + (BH - TH) / 2;
  const boxes = rows.map((row, ri) => row.map((n, i) => {
    const x = cross / 2 + (i - (row.length - 1) / 2) * 2 * HALF, y = ri === rows.length - 1 ? top : total - foot - ri * ROW;
    const [w, h] = n.side ? [S, S] : n.boss ? [BW, BH] : [W, TH];
    return { x: x - w / 2, y: y - h / 2, w, h };
  }));
  // From the top of the lower node to the foot of the upper one, straight for a little at each end, with the curve between
  const k = phone ? 11 : 20;
  const paths = links.map(([r, i, j]) => {
    const b = boxes[r][i], t = boxes[r + 1][j], x = b.x + b.w / 2, u = t.x + t.w / 2, v = t.y + t.h, y0 = b.y - k, v0 = v + k, m = (y0 + v0) / 2;
    return `M${x} ${b.y}V${y0}C${x} ${m} ${u} ${m} ${u} ${v0}V${v}`;
  });
  return { width: cross, height: total, boxes, paths };
}

/** The zoom that fits the whole map in a box, with a margin of 14 on every side, and never above 1. */
export function fit(map: Pick<Layout, 'width' | 'height'>, box: { width: number; height: number }): number {
  return +Math.min(1, (box.height - 28) / map.height, (box.width - 28) / map.width).toFixed(4);
}
