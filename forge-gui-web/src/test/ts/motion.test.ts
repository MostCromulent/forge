import { describe, expect, it } from 'vitest';
import { journeys } from '../../main/ts/motion';
import type { CardMoved, GameEvent } from '../../main/ts/protocol';

const move = (card: number, from: CardMoved['from'], to: CardMoved['to']): CardMoved => ({ kind: 'cardMoved', card: { ref: card }, from, to });
const hand = { zone: 'Hand' as const, player: { ref: 1 } };
const stack = { zone: 'Stack' as const };
const field = { zone: 'Battlefield' as const, player: { ref: 1 } };

describe('what moved since the last frame', () => {
  it('takes a card\'s first origin and last destination, so two hops in one frame are one flight', () => {
    const trips = journeys([move(5, hand, stack), move(5, stack, field)]);
    expect(trips.get('5')).toMatchObject({ from: hand, to: field });
  });

  it('keeps each card\'s own trip, and ignores events that are not moves', () => {
    const events: GameEvent[] = [move(5, hand, stack), { kind: 'shuffled', player: { ref: 1 } }, move(6, undefined, field)];
    const trips = journeys(events);
    expect([...trips.keys()]).toEqual(['5', '6']);
    expect(trips.get('6')?.from).toBeUndefined();
  });
});
