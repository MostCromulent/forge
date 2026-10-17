// The command zone holds several kinds of card. Each kind is drawn in its own place.

import type { CardStateView, CardView } from '../protocol';

export type CommandKind = 'plane' | 'scheme' | 'avatar' | 'commander' | 'signature' | 'dice' | 'effect';

/** The name of the card Forge uses for each player's planar die. */
const PLANAR_DICE = 'Planar Dice';

export function commandKind(card: CardView, state: Partial<CardStateView>): CommandKind {
  if (card.GamePieceType === 'PLANAR') return 'plane';
  if (card.GamePieceType === 'SCHEME') return 'scheme';
  if (card.GamePieceType === 'AVATAR') return 'avatar';
  if (state.Name === PLANAR_DICE) return 'dice';
  if (card.IsCommander) {
    // An Oathbreaker's signature spell is also a commander. It can be cast only while its oathbreaker is on the battlefield
    return /\b(Creature|Planeswalker)\b/.test(state.Type ?? '') ? 'commander' : 'signature';
  }
  return 'effect';
}

/** Reads the hand size and starting life modifiers from a Vanguard card's rules text. The labels are translated, so only the numbers are read. */
export function avatarModifiers(rulesText: string | undefined): [number, number] | null {
  const numbers = (rulesText ?? '').match(/[+-]\d+/g);
  return numbers && numbers.length >= 2 ? [Number(numbers[0]), Number(numbers[1])] : null;
}
