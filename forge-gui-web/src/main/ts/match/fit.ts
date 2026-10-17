// How large a question's cards are drawn on the dimmed board

// Cards on the board grow into the room it leaves them, up to about 200px wide, where their text reads easily
const MOST_FIT = 2.3;

export interface CardFit {
  /** The pixels between two cards. */
  gap: number;
  /** The pixels of buttons under each card. */
  below?: number;
  /** Whether the cards may wrap onto more rows. */
  wrap?: boolean;
  /** The smallest scale, below which the cards scroll instead. */
  least?: number;
  /** How many boxes share the backdrop's height, one above another. */
  share?: number;
}

/**
 * How much to enlarge a question's cards so they fill the room given. Wrapping rows are tried when allowed, and the layout
 * with the biggest cards wins.
 */
function cardScale(count: number, width: number, height: number, card: { w: number; h: number }, how: CardFit): number {
  const { gap, below = 0, wrap = false, least = 1 } = how;
  let best = 0;
  for (let rows = 1; rows <= (wrap ? count : 1); rows++) {
    const cols = Math.ceil(count / rows);
    best = Math.max(best, Math.min((width - gap * (cols - 1)) / cols / card.w, (height - gap * (rows - 1) - below * rows) / rows / card.h));
  }
  return Math.max(least, Math.min(MOST_FIT, best));
}

/** Sets the scale of the count cards in a row, so they fill the room the backdrop has left after the rest of the box round them. */
export function fitCards(row: HTMLElement, box: HTMLElement, back: HTMLElement, count: number, how: CardFit): void {
  const around = getComputedStyle(back);
  const own = getComputedStyle(row);
  const card = { w: parseFloat(own.getPropertyValue('--card-w')), h: parseFloat(own.getPropertyValue('--card-h')) };
  const width = back.clientWidth - parseFloat(around.paddingLeft) - parseFloat(around.paddingRight)
    - parseFloat(own.paddingLeft) - parseFloat(own.paddingRight) - 16;
  // The row's padding holds the labels under its cards, and the rest of the box keeps its height
  const used = box.offsetHeight - row.clientHeight + parseFloat(own.paddingTop) + parseFloat(own.paddingBottom);
  const room = back.clientHeight - parseFloat(around.paddingTop) - parseFloat(around.paddingBottom);
  const height = room / (how.share ?? 1) - used - 16;
  row.style.setProperty('--fit', cardScale(count, width, height, card, how).toFixed(2));
}
