import { describe, expect, it } from 'vitest';
import { repeatTap, tagsFor, tapInspects } from '../../main/ts/portrait';

describe('tapInspects', () => {
  const card = { playable: false, selectable: false, mine: false };
  it('opens the sheet for an opponent\'s card nothing is asking about', () => {
    expect(tapInspects(card, false)).toBe(true);
  });
  it('sends the tap for your own card, which may be a land to tap', () => {
    expect(tapInspects({ ...card, mine: true }, false)).toBe(false);
  });
  it('sends the tap for a card that can be chosen or played', () => {
    expect(tapInspects({ ...card, selectable: true }, false)).toBe(false);
    expect(tapInspects({ ...card, playable: true }, false)).toBe(false);
  });
  it('sends the tap while a prompt is choosing cards', () => {
    expect(tapInspects(card, true)).toBe(false);
  });
});

describe('repeatTap', () => {
  it('drops a second tap on the same card within 350ms and takes a later one', () => {
    expect(repeatTap(7, 1000)).toBe(false);
    expect(repeatTap(7, 1300)).toBe(true);
    expect(repeatTap(7, 1700)).toBe(false);
  });
  it('takes a tap on another card at once', () => {
    expect(repeatTap(8, 2000)).toBe(false);
    expect(repeatTap(9, 2100)).toBe(false);
  });
});

describe('tagsFor', () => {
  it('numbers stack items from the top and lists every item that targets a thing', () => {
    const tags = tagsFor([{ key: 90, targets: [5, 6] }, { key: 91, targets: [6] }, { key: 92, targets: [] }]);
    expect(tags.get(5)).toEqual([1]);
    expect(tags.get(6)).toEqual([1, 2]);
    expect(tags.has(92)).toBe(false);
    expect(tags.size).toBe(2);
  });
});
