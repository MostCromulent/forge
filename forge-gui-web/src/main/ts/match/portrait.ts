// Decisions the phone layout makes, kept apart from the page so they can be tested without one.

/**
 * Whether a tap on a card opens its details and sends nothing: only when the tap could not have done anything.
 * In combat a tap picks the attacker to block, or the planeswalker to attack, and the host marks neither as selectable.
 */
export function tapInspects(card: { playable: boolean; selectable: boolean; mine: boolean; attacking?: boolean }, picking: boolean, phase?: string): boolean {
  return !card.playable && !card.selectable && !card.mine && !card.attacking && !picking && phase !== 'COMBAT_DECLARE_ATTACKERS';
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

/** Whether a permanent has arrived or left since the seat was last looked at. */
export function fieldChanged(seen: ReadonlySet<number> | undefined, now: readonly number[]): boolean {
  return !!seen && (now.length !== seen.size || now.some(key => !seen.has(key)));
}

/** Which opponent's seat shows when only one does: the one whose turn it is, as desktop's tabs do, or the one the player chose since that turn began. */
export function seatToOpen(s: { open: number | null; seats: readonly number[]; active: number | null; turn: number; chosenTurn: number }): number | null {
  if (!s.seats.length) return null;
  const open = s.open !== null && s.seats.includes(s.open) ? s.open : null;
  if (open !== null && s.chosenTurn === s.turn) return open;
  if (s.active !== null && s.seats.includes(s.active)) return s.active;
  return open ?? s.seats[0];
}
