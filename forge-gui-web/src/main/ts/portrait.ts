// Decisions the phone layout makes, kept apart from the page so they can be tested without one.

/** Whether a tap on a card opens its details and sends nothing: only when the tap could not have done anything. */
export function tapInspects(card: { playable: boolean; selectable: boolean; mine: boolean }, picking: boolean): boolean {
  return !card.playable && !card.selectable && !card.mine && !picking;
}

const REPEAT_MS = 350;
let last = { key: -1, at: -Infinity };

/** Whether this tap repeats the last one too soon to be meant, as a slow connection invites. The first of two is never a repeat. */
export function repeatTap(key: number, now: number): boolean {
  const repeat = key === last.key && now - last.at < REPEAT_MS;
  if (!repeat) last = { key, at: now };
  return repeat;
}

/** For each thing targeted, the numbers of the stack items that target it, the top of the stack being 1. */
export function tagsFor(items: readonly { key: number; targets: readonly number[] }[]): Map<number, number[]> {
  const tags = new Map<number, number[]>();
  items.forEach((item, i) => {
    for (const target of item.targets) tags.set(target, [...(tags.get(target) ?? []), i + 1]);
  });
  return tags;
}

/** How many of the cards there now were not there when the seat was last looked at. */
export function newCards(seen: ReadonlySet<number> | undefined, now: readonly number[]): number {
  return seen ? now.filter(key => !seen.has(key)).length : 0;
}

/** Which opponent's seat a phone shows: the one a new question is about, else the one whose turn it is, else where the player left it. */
export function seatToOpen(s: { open: number | null; seats: readonly number[]; active: number | null; turn: number; chosenTurn: number;
  promptSeats: readonly number[]; promptIsNew: boolean }): number | null {
  if (!s.seats.length) return null;
  if (s.promptIsNew && s.promptSeats.length === 1 && s.seats.includes(s.promptSeats[0])) return s.promptSeats[0];
  const open = s.open !== null && s.seats.includes(s.open) ? s.open : null;
  if (open !== null && s.chosenTurn === s.turn) return open;
  if (s.active !== null && s.seats.includes(s.active)) return s.active;
  return open ?? s.seats[0];
}
