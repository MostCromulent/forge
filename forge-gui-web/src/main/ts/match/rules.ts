// Facts of the game that the board, the hand and the preview all read, with nothing drawn here

/** Commander damage that loses the game, and the point from which the portrait warns of it. */
export const COMMANDER_LETHAL = 21;
export const COMMANDER_WARNING = 15;

/** The five colours by their bit in Forge's MagicColor mask. */
export const COLOUR_BITS: [number, string][] = [[1, 'W'], [2, 'U'], [4, 'B'], [8, 'R'], [16, 'G']];

/** Whether a power or toughness is above or below what the card is on its own, as a pump, a counter or a shrink leaves it. */
export const ptShift = (now: number, base: number | undefined): string => (base === undefined || now === base ? '' : now > base ? 'up' : 'down');

/** The frame of a card drawn without its image, from a MagicColor mask: its one colour, M for several, and L or C for none. */
export function frameColour(colours: number, type: string): string {
  const one = COLOUR_BITS.filter(([bit]) => colours & bit);
  if (one.length > 1) return 'M';
  if (one.length === 1) return one[0][1];
  return /Land/.test(type) ? 'L' : 'C';
}

/** A mana cost's mana value: {2}{W} counts as three, a hybrid shard as one and X as nothing. */
export function manaValue(cost: string | undefined): number {
  return [...String(cost ?? '').matchAll(/\{([^}]+)\}/g)].reduce((sum, [, shard]) => {
    const digits = shard.split('/').find(part => /^\d+$/.test(part));
    return sum + (digits !== undefined ? Number(digits) : /^[xyz]$/i.test(shard) ? 0 : 1);
  }, 0);
}
