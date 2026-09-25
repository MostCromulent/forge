// The command zone holds very different things: a commander to cast, an avatar to activate, a plane that rules the
// table, a scheme in motion, and small reminders like the monarch. Each kind is drawn in its own place.

import type { CardStateView, CardView } from './protocol';

export type CommandKind = 'plane' | 'scheme' | 'avatar' | 'commander' | 'signature' | 'dice' | 'effect';

/** The name Forge gives the card that carries each player's planar die. */
export const PLANAR_DICE = 'Planar Dice';

export function commandKind(card: CardView, state: Partial<CardStateView>): CommandKind {
  const type = state.Type ?? '';
  if (/\b(Plane|Phenomenon)\b/.test(type)) return 'plane';
  if (/\bScheme\b/.test(type)) return 'scheme';
  if (/\bVanguard\b/.test(type)) return 'avatar';
  if (state.Name === PLANAR_DICE) return 'dice';
  if (card.IsCommander) {
    // An Oathbreaker's signature spell is a commander too, but one cast only beside its oathbreaker
    return /\b(Creature|Planeswalker)\b/.test(type) ? 'commander' : 'signature';
  }
  return 'effect';
}

/** Hand size then starting life, from a Vanguard card's rules text. The labels are translated, so only the numbers count. */
export function avatarModifiers(rulesText: string | undefined): [number, number] | null {
  const numbers = (rulesText ?? '').match(/[+-]\d+/g);
  return numbers && numbers.length >= 2 ? [Number(numbers[0]), Number(numbers[1])] : null;
}
