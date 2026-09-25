import { describe, expect, it } from 'vitest';
import { avatarModifiers, commandKind } from '../../main/ts/command';
import type { CardStateView, CardView } from '../../main/ts/protocol';

const card = (more: Partial<CardView> = {}): CardView => ({ $key: 1, ...more });
const state = (Name: string, Type: string): Partial<CardStateView> => ({ Name, Type });

describe('where a command-zone card is drawn', () => {
  // Fails if a plane, a scheme or the planar die ends up in the round-token strip, or a commander loses its tile
  it('sorts each kind of card to its own place', () => {
    expect(commandKind(card(), state('Turri Island', 'Plane — Ir'))).toBe('plane');
    expect(commandKind(card(), state('Chaotic Aether', 'Phenomenon'))).toBe('plane');
    expect(commandKind(card(), state('Nothing Can Stop Me Now', 'Ongoing Scheme'))).toBe('scheme');
    expect(commandKind(card(), state('Momir Vig, Simic Visionary Avatar', 'Vanguard'))).toBe('avatar');
    expect(commandKind(card(), state('Planar Dice', 'Effect'))).toBe('dice');
    expect(commandKind(card({ IsCommander: true }), state('Meren of Clan Nel Toth', 'Legendary Creature — Human Shaman'))).toBe('commander');
    expect(commandKind(card({ IsCommander: true }), state('Wrenn and Seven', 'Legendary Planeswalker — Wrenn'))).toBe('commander');
    expect(commandKind(card({ IsCommander: true }), state('Lightning Bolt', 'Instant'))).toBe('signature');
    expect(commandKind(card(), state('The Monarch', 'Effect'))).toBe('effect');
  });
});

describe("a Vanguard card's modifiers", () => {
  // Fails if the numbers are read through the labels, which are translated
  it('are the first two signed numbers, whatever the labels say', () => {
    expect(avatarModifiers('Hand Size: +1\r\nStarting Life: -3')).toEqual([1, -3]);
    expect(avatarModifiers('Handgröße: +0\r\nStartlebenspunkte: +4')).toEqual([0, 4]);
    expect(avatarModifiers(undefined)).toBeNull();
    expect(avatarModifiers('Flying')).toBeNull();
  });
});
