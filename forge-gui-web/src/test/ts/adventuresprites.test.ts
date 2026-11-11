import { describe, expect, it } from 'vitest';
import { Animator, Atlas, directionOf } from '../../main/ts/adventure/sprites';

const frame = (name: string, index: number, x: number) => ({ name, index, x, y: 0, w: 16, h: 16 });

describe('directionOf', () => {
  it('splits the circle into eighths as CharacterSprite.moveBy does', () => {
    expect(directionOf(1, 0)).toBe('Right');
    expect(directionOf(1, 1)).toBe('RightUp');
    expect(directionOf(0, 1)).toBe('Up');
    expect(directionOf(-1, 0)).toBe('Left');
    expect(directionOf(0, -1)).toBe('Down');
    expect(directionOf(1, -1)).toBe('RightDown');
    expect(directionOf(1, -0.1)).toBe('Right');
  });
});

describe('Animator', () => {
  const atlas = new Atlas('a.png', [
    frame('IdleRight', 1, 16), frame('IdleRight', 0, 0),
    frame('Walk', 0, 32), frame('Walk', 1, 48), frame('Walk', 2, 64),
    frame('Attack', 0, 80), frame('Attack', 1, 96),
    ...Array.from({ length: 30 }, (_, i) => frame('Death', i, 112 + i)),
  ]);

  it('orders frames by index and loops a walk at 0.2 s a frame', () => {
    const a = new Animator(atlas);
    a.set('Walk');
    expect(a.frame()?.x).toBe(32);
    a.tick(0.45);
    expect(a.frame()?.x).toBe(64);
    a.tick(0.2);
    expect(a.frame()?.x).toBe(32);
    expect(atlas.frames('IdleRight').map(f => f.x)).toEqual([0, 16]);
  });

  it('flips the right-facing frames when facing left and the atlas has no left frames', () => {
    const a = new Animator(atlas);
    a.face('Left');
    expect(a.frame()).toMatchObject({ x: 0, flip: true });
    a.face('LeftUp');
    expect(a.frame()?.flip).toBe(true);
    a.face('Up');
    expect(a.frame()?.flip).toBe(false);
  });

  it('plays an attack once and holds its last frame, restarting when set again', () => {
    const a = new Animator(atlas);
    a.set('Attack');
    a.tick(1);
    expect(a.frame()?.x).toBe(96);
    a.set('Attack');
    expect(a.frame()?.x).toBe(80);
  });

  it('caps a death animation at 3 s in total', () => {
    const a = new Animator(atlas);
    a.set('Death');
    expect(a.duration()).toBeCloseTo(3, 5);
  });

  it('keeps the current animation when the atlas lacks the one asked for', () => {
    const a = new Animator(atlas);
    a.set('Hit');
    expect(a.frame()?.x).toBe(0);
  });
});
