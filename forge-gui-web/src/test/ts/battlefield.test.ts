import { describe, expect, it } from 'vitest';
import { signature, slotsFor } from '../../main/ts/battlefield';
import { createModel, type Model } from '../../main/ts/model';

const swampArt = 'c:Swamp|KTK|1';

/** One card on the table with its own current state, as the server sends them. */
function put(model: Model, key: number, card: Record<string, unknown>, state: Record<string, unknown>): void {
  model.objects.set(key + 1000, { $key: key + 1000, Name: 'Swamp', ImageKey: swampArt, Type: 'Basic Land - Swamp', ...state } as never);
  model.objects.set(key, { $key: key, CurrentState: { ref: key + 1000 }, ...card } as never);
  model.visible.add(key);
}

const sig = (model: Model, key: number) => signature(model, model.objects.get(key) as never, []);

describe('which cards pile', () => {
  // A land untapped this turn carries Tapped: false, one never tapped carries nothing; they looked different
  it('piles identical cards whatever the game has set back to how it was', () => {
    const model = createModel();
    put(model, 1, {}, {});
    put(model, 2, { Tapped: false, Attacking: false, Damage: 0, Counters: {} }, { Loyalty: '0' });
    put(model, 3, { Counters: { 'P1P1': 0 } }, {});
    expect(sig(model, 2)).toBe(sig(model, 1));
    expect(sig(model, 3)).toBe(sig(model, 1));
  });

  it('keeps apart what anyone can see differs', () => {
    const model = createModel();
    put(model, 1, {}, {});
    put(model, 2, { Tapped: true }, {});
    put(model, 3, { Counters: { 'P1P1': 1 } }, {});
    put(model, 4, { Cloned: true }, {});
    const first = sig(model, 1);
    expect(sig(model, 2)).not.toBe(first);
    expect(sig(model, 3)).not.toBe(first);
    expect(sig(model, 4)).not.toBe(first);
  });

  // Two printings of one land are the same card to play, and the pile shows the top card's art
  it('piles two printings of the same card together', () => {
    const model = createModel();
    put(model, 1, {}, {});
    put(model, 2, {}, { ImageKey: 'c:Swamp|KTK|2' });
    expect(sig(model, 2)).toBe(sig(model, 1));
  });

  it('reads counters in any order as the same counters', () => {
    const model = createModel();
    put(model, 1, { Counters: { A: 1, B: 2 } }, {});
    put(model, 2, { Counters: { B: 2, A: 1 } }, {});
    expect(sig(model, 2)).toBe(sig(model, 1));
  });
});

describe('where piles sit', () => {
  // A land tapped out of its pile used to land wherever its card came in the zone, across the row from the pile
  it('keeps cards of one name side by side, so a split pile stays together', () => {
    const model = createModel();
    put(model, 1, {}, {});
    put(model, 2, {}, { Name: 'Forest', ImageKey: 'c:Forest|KTK|1', Type: 'Basic Land - Forest' });
    put(model, 3, {}, {});
    put(model, 4, { Tapped: true }, {});
    const cards = [1, 2, 3, 4].map(k => model.objects.get(k) as never);
    const slots = slotsFor(model, cards, cards);
    expect(slots.map(s => s.members.map(m => m.$key))).toEqual([[1, 3], [4], [2]]);
  });
});

