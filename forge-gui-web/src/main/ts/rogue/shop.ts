// A bazaar's sums, apart from its page so they can be tested without drawing it

/**
 * What the picked items cost, the gold left after them, and the items that no longer fit in it, which the page dims.
 * A picked item is never dimmed, so it can always be put back.
 */
export function shopTotal(items: { id: number; price: number }[], picked: number[], gold: number): { cost: number; left: number; dimmed: Set<number> } {
  const cost = items.filter(i => picked.includes(i.id)).reduce((sum, i) => sum + i.price, 0);
  const left = gold - cost;
  return { cost, left, dimmed: new Set(items.filter(i => !picked.includes(i.id) && i.price > left).map(i => i.id)) };
}
