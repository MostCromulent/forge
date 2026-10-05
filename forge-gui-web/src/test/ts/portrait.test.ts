import { describe, expect, it } from 'vitest';
import { newCards, repeatTap, seatToOpen, tagsFor, tapInspects } from '../../main/ts/match/portrait';

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
  it('sends the tap on an attacker, which is how one is picked to block', () => {
    expect(tapInspects({ ...card, attacking: true }, false)).toBe(false);
  });
  it("sends the tap while attackers are declared, when an opponent's planeswalker can be picked to attack", () => {
    expect(tapInspects(card, false, 'COMBAT_DECLARE_ATTACKERS')).toBe(false);
    expect(tapInspects(card, false, 'MAIN1')).toBe(true);
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

describe('newCards', () => {
  it('counts what arrived, even when as many left', () => {
    expect(newCards(new Set([1, 2, 3]), [2, 3, 4])).toBe(1);
  });
  it('counts nothing for a seat never looked at', () => {
    expect(newCards(undefined, [1, 2])).toBe(0);
  });
});

describe('seatToOpen', () => {
  const base = { open: 11, seats: [11, 12, 13], active: 12, turn: 4, chosenTurn: 0, promptSeats: [] as number[], promptIsNew: false };
  it('follows the turn to an opponent', () => {
    expect(seatToOpen(base)).toBe(12);
  });
  it('stays where the player chose during this turn', () => {
    expect(seatToOpen({ ...base, chosenTurn: 4 })).toBe(11);
  });
  it('opens the one seat a new prompt is about, whatever was chosen', () => {
    expect(seatToOpen({ ...base, chosenTurn: 4, promptSeats: [13], promptIsNew: true })).toBe(13);
  });
  it('does not move for a prompt spread over several seats', () => {
    expect(seatToOpen({ ...base, chosenTurn: 4, promptSeats: [12, 13], promptIsNew: true })).toBe(11);
  });
  it('opens the first seat when none is open or the open one has gone', () => {
    expect(seatToOpen({ ...base, open: null, active: null })).toBe(11);
    expect(seatToOpen({ ...base, open: 99, active: null })).toBe(11);
  });
  it('does not follow your own turn', () => {
    expect(seatToOpen({ ...base, active: 1 })).toBe(11);
  });
});
