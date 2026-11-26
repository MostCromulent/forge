// Rogue Commander's screen motion, timed as the mobile client's: a new screen rises over the page, and closing it drops it away

import type { RefObject } from 'preact';
import { useLayoutEffect } from 'preact/hooks';
import { reducedMotion } from '../dom';

/** libgdx's pow3Out, which mobile eases its screens with. */
export const POW3_OUT = 'cubic-bezier(.215, .61, .355, 1)';
const SCREEN_MS = 500;
/** How dark the page under a moving screen gets, as mobile dims it to 55%. */
const DIMMED = 'brightness(.45)';

/**
 * Starts a view transition's mark for a move to a new screen, which rises over the page, or with back for the page dropping away.
 * Pass the result as eased's or easedLocal's mark. The mark is also set now, because the old page must be captured whole (rogue.css).
 */
export function screenWay(back: boolean): () => Record<string, string> {
  const way = back ? 'drop' : 'rise';
  if ('startViewTransition' in document && !reducedMotion()) document.documentElement.dataset.rogue = way;
  return () => ({ rogue: way });
}

/** How long a closed screen waits in place for the next one, which the server sends after the answer. */
const NEXT_WAIT_MS = 250;
// Set while an element that rose has just left, until the end of the task, so one that replaces it in the same draw shows without motion
let leaving = false;
let risen = 0;
// A copy of the screen that last closed, standing in its place until the next one arrives or the wait ends
let waiting: { frame: HTMLElement; timer: number } | null = null;

/**
 * Makes an element laid over its parent rise in when it mounts, dimming what is under it, and drop away when it unmounts.
 * One that replaces another, at once or within a short wait, shows without motion, as one page of a story follows another.
 * stays says the element went only for a while, such as for another tab, so it leaves without motion.
 */
export function useRise(ref: RefObject<HTMLElement>, stays: () => boolean): void {
  useLayoutEffect(() => {
    const el = ref.current!;
    risen++;
    const follows = leaving || waiting !== null;
    if (waiting) {
      clearTimeout(waiting.timer);
      waiting.frame.remove();
      waiting = null;
    }
    if (!follows && !reducedMotion()) {
      el.animate([{ transform: 'translateY(100%)' }, { transform: 'none' }], { duration: SCREEN_MS, easing: POW3_OUT });
      for (const under of el.parentElement!.children) {
        if (under !== el) under.animate([{ filter: 'none' }, { filter: DIMMED }], { duration: SCREEN_MS, easing: POW3_OUT });
      }
    }
    return () => {
      const box = el.getBoundingClientRect();
      const copy = el.cloneNode(true) as HTMLElement;
      const home = el.closest('.rg-shell') ?? document.body;
      const was = risen;
      leaving = true;
      queueMicrotask(() => {
        leaving = false;
        if (risen !== was || stays() || reducedMotion() || !home.isConnected) return;
        const frame = standIn(copy, box, home);
        waiting = { frame, timer: window.setTimeout(() => { waiting = null; dropAway(frame, copy); }, NEXT_WAIT_MS) };
      });
    };
  }, []);
}

/** A copy of a closed screen where it stood, which the page cannot press. */
function standIn(copy: HTMLElement, box: DOMRect, home: Element): HTMLElement {
  const frame = document.createElement('div');
  frame.className = 'rg-leaving';
  frame.setAttribute('aria-hidden', 'true');
  Object.assign(frame.style, { left: `${box.left}px`, top: `${box.top}px`, width: `${box.width}px`, height: `${box.height}px` });
  frame.append(copy);
  home.append(frame);
  return frame;
}

/** Drops the copy off the bottom of its frame, over the page it uncovers, which brightens from dimmed. */
function dropAway(frame: HTMLElement, copy: HTMLElement): void {
  if (!frame.isConnected) return;
  frame.animate([{ background: 'rgba(0, 0, 0, .55)' }, { background: 'transparent' }], { duration: SCREEN_MS, easing: POW3_OUT });
  copy.animate([{ transform: 'none' }, { transform: 'translateY(100%)' }], { duration: SCREEN_MS, easing: POW3_OUT, fill: 'forwards' })
    .finished.then(() => frame.remove(), () => frame.remove());
}

// A little slower than mobile's 500 and 350 ms, which read as hurried on a large screen
const MOVE_MS = 700;
const TURN_MS = 450;
const HALO_MS = 900;
/** The card or trait in a tile, which is what is dealt and flown; a tile with none, such as a phone's trait row, moves whole. */
const faceOf = (tile: HTMLElement): HTMLElement => tile.querySelector<HTMLElement>(':scope > img, :scope > .c, :scope > .rg-tcard') ?? tile;
// The parts of each set of cards already dealt, so a scene shown again after a fold or another tab does not deal again
const dealt = new WeakMap<object, Set<number>>();

/**
 * Deals tiles as mobile does, the first time part of the set key is shown: each card flies face down from the bottom middle of its
 * scrolling box and turns over, up to 140 ms after the one before. A rare tile drops in from above instead, onto a halo that pulses
 * and fades. A tile cannot be pressed until it lands.
 */
export function deal(key: object, part: number, tiles: HTMLElement[], rare: (i: number) => boolean): void {
  const parts = dealt.get(key) ?? new Set<number>();
  dealt.set(key, parts);
  if (parts.has(part) || !tiles.length || reducedMotion()) return;
  parts.add(part);
  const box = tiles[0].closest<HTMLElement>('.rg-sheet .grid, .rg-scene > .in')!;
  const view = box.getBoundingClientRect();
  const swing = getComputedStyle(document.documentElement).getPropertyValue('--rg-swing').trim() || POW3_OUT;
  const step = Math.min(140, 1400 / tiles.length);
  // Cards on their way up from below would make the box scroll further, so it stops scrolling until all have landed
  if (box.scrollHeight > box.clientHeight) box.style.scrollbarGutter = 'stable';
  box.classList.add('rg-dealing');
  tiles.forEach((tile, i) => {
    const face = faceOf(tile);
    const at = face.getBoundingClientRect();
    const wait = i * step, total = wait + MOVE_MS + TURN_MS;
    const of = (ms: number) => ms / total;
    const start = rare(i) ? `translateY(${-at.height}px)` : `translate(${view.left + view.width / 2 - at.left - at.width / 2}px, ${view.bottom - at.top}px)`;
    const move = rare(i) ? swing : POW3_OUT;
    tile.classList.add('rg-dealt');
    // Landing goes by the clock, as mobile's does, so a card moved off the page cannot leave its tile unpressable
    setTimeout(() => tile.classList.remove('rg-dealt'), total);
    if (face === tile) {
      tile.animate([
        { offset: 0, transform: start, opacity: 0 }, { offset: of(wait), transform: start, opacity: 0 },
        { offset: of(wait), transform: start, opacity: 1, easing: move }, { offset: 1, transform: 'none', opacity: 1 },
      ], { duration: total });
    } else {
      // The face shows from the middle of the turn, and a striped back in its place until then
      const landed = of(wait + MOVE_MS), turned = of(wait + MOVE_MS + TURN_MS / 2);
      face.animate([
        { offset: 0, transform: start, opacity: 0 }, { offset: of(wait), transform: start, opacity: 0, easing: move },
        { offset: landed, transform: 'none', opacity: 0 }, { offset: turned, transform: 'scaleX(0)', opacity: 0 },
        { offset: turned, transform: 'scaleX(0)', opacity: 1 }, { offset: 1, transform: 'none', opacity: 1 },
      ], { duration: total });
      const back = document.createElement('span');
      back.className = 'rg-back';
      Object.assign(back.style, { left: `${face.offsetLeft}px`, top: `${face.offsetTop}px`, width: `${face.offsetWidth}px`, height: `${face.offsetHeight}px` });
      tile.append(back);
      back.animate([
        { offset: 0, transform: start, opacity: 0 }, { offset: of(wait), transform: start, opacity: 0 },
        { offset: of(wait), transform: start, opacity: 1, easing: move }, { offset: landed, transform: 'none', opacity: 1 },
        { offset: turned, transform: 'scaleX(0)', opacity: 1 }, { offset: turned, transform: 'scaleX(0)', opacity: 0 },
        { offset: 1, transform: 'none', opacity: 0 },
      ], { duration: total, fill: 'forwards' }).finished.then(() => back.remove(), () => back.remove());
      if (rare(i)) halo(tile, face, wait + MOVE_MS / 2);
    }
  });
  setTimeout(() => {
    box.classList.remove('rg-dealing');
    box.style.scrollbarGutter = '';
  }, (tiles.length - 1) * step + MOVE_MS + TURN_MS);
}

/**
 * A warm halo behind a tile's face from wait ms on, pulsing and fading after, as mobile draws it: three rings to 1.2 times 60% of the
 * face's height. It is a circle the face's width, its outer rings drawn as shadows, so it never makes the box scroll further.
 */
function halo(tile: HTMLElement, face: HTMLElement, wait: number): void {
  const w = face.offsetWidth, h = face.offsetHeight, side = Math.min(w, h);
  const ring = document.createElement('span');
  ring.className = 'rg-halo';
  Object.assign(ring.style, { left: `${face.offsetLeft + (w - side) / 2}px`, top: `${face.offsetTop + (h - side) / 2}px`, width: `${side}px`, height: `${side}px` });
  const span = MOVE_MS + TURN_MS + HALO_MS;
  const frames = Array.from({ length: span / 50 + 1 }, (_, k) => {
    const ms = k * 50;
    const r = .6 * Math.max(w, h) * (1 + .08 * Math.sin(ms * .006));
    return {
      boxShadow: [1.2, .8667].map(f => `0 0 0 ${Math.max(0, r * f - side / 2)}px rgba(255, 140, 102, .22)`).join(', '),
      opacity: Math.min(1, ms / 200) * Math.min(1, (span - ms) / 400),
    };
  });
  const raised = getComputedStyle(face).position === 'static';
  // A positioned face paints over the halo, which comes before it
  if (raised) face.style.position = 'relative';
  tile.prepend(ring);
  const done = () => {
    ring.remove();
    if (raised) face.style.position = '';
  };
  ring.animate(frames, { duration: span, delay: wait, fill: 'backwards' }).finished.then(done, done);
}

/** Flies each tile's face, shrinking, into target, such as the Deck tab, 60 ms after the one before; then done runs. */
export function flyInto(tiles: HTMLElement[], target: HTMLElement, done: () => void): void {
  if (!tiles.length || reducedMotion()) {
    done();
    return;
  }
  const to = target.getBoundingClientRect();
  const copies = tiles.map((tile, i) => {
    const face = faceOf(tile);
    const from = face.getBoundingClientRect();
    const copy = face.cloneNode(true) as HTMLElement;
    copy.classList.add('rg-flown');
    copy.setAttribute('aria-hidden', 'true');
    Object.assign(copy.style, { left: `${from.left}px`, top: `${from.top}px`, width: `${from.width}px`, height: `${from.height}px` });
    document.body.append(copy);
    tile.style.visibility = 'hidden';
    const dx = to.left + to.width / 2 - from.left - from.width / 2, dy = to.top + to.height / 2 - from.top - from.height / 2;
    copy.animate([{ transform: 'none' }, { transform: `translate(${dx}px, ${dy}px) scale(.12)` }],
      { duration: 500, delay: i * 60, easing: POW3_OUT, fill: 'forwards' });
    return copy;
  });
  // The answer goes by the clock, so it is sent even if the page stops drawing the flight
  setTimeout(() => {
    for (const copy of copies) copy.remove();
    done();
  }, (tiles.length - 1) * 60 + 500);
}

const LIGHT_MS = 2400;
// Where five sparkles wink round a picture in turn, as fractions of its box
const SPOTS = [[-.08, .05], [1.06, .2], [-.05, .85], [1.04, .92], [.5, -.06]];

/** A part of an entrance laid over its parent, at the box given or where its class puts it. */
function layer(cls: string, parent: HTMLElement, box?: [number, number, number, number]): HTMLElement {
  const el = document.createElement('span');
  el.className = cls;
  el.setAttribute('aria-hidden', 'true');
  if (box) Object.assign(el.style, { left: `${box[0]}px`, top: `${box[1]}px`, width: `${box[2]}px`, height: `${box[3]}px` });
  parent.append(el);
  return el;
}

/**
 * Brings in a dialog's picture as mobile's notices do. With 'rays', gold rays turn behind it at 25 degrees a second, a shine sweeps across it
 * twice and five sparkles wink round it, over 2.4 s. With 'drop' it drops from above with a swing onto a halo that grows, with the sparkles.
 * frame wraps the picture, its first child. With reduced motion the rays and the halo show still.
 */
export function entrance(frame: HTMLElement, way: 'rays' | 'drop'): void {
  const pic = frame.firstElementChild as HTMLElement;
  const dialog = frame.closest<HTMLElement>('.dialog')!;
  dialog.classList.add('rg-lit');
  const x = pic.offsetLeft, y = pic.offsetTop, w = pic.offsetWidth, h = pic.offsetHeight;
  const still = reducedMotion();
  if (way === 'rays') {
    const r = Math.max(dialog.offsetWidth, dialog.offsetHeight);
    // The rays are clipped to the dialog by a layer of its size, so they never make it scroll
    const rays = layer('rg-rays', layer('rg-light', dialog), [frame.offsetLeft + x + w / 2 - r, frame.offsetTop + y + h / 2 - r, 2 * r, 2 * r]);
    if (!still) rays.animate([{ rotate: '0deg' }, { rotate: '60deg' }], { duration: LIGHT_MS, fill: 'forwards' });
  } else {
    // Three rings at 22% to 1.2 times three quarters of the picture's height, the inner disc and two shadows
    const p = .75 * h, side = 1.0667 * p;
    const halo = layer('rg-halo', frame, [x + (w - side) / 2, y + (h - side) / 2, side, side]);
    Object.assign(halo.style, { zIndex: '-1', boxShadow: [.6667, .3333].map(f => `0 0 0 ${f * p}px rgba(255, 140, 102, .22)`).join(', ') });
    if (!still) {
      halo.animate(Array.from({ length: LIGHT_MS / 50 + 1 }, (_, k) => {
        const s = k * .05;
        return { transform: `scale(${Math.min(1, s / .8) * (1 + .08 * Math.sin(s * 6))})` };
      }), { duration: LIGHT_MS, fill: 'forwards' });
      const swing = getComputedStyle(document.documentElement).getPropertyValue('--rg-swing').trim() || POW3_OUT;
      pic.animate([{ transform: `translateY(${-1.25 * h}px)` }, { transform: 'none' }], { duration: 600, easing: swing });
    }
  }
  if (still) return;
  const parts: HTMLElement[] = [];
  if (way === 'rays') {
    // A band 18% of the picture wide, leaning by 60% of its height, sweeps across it from 0.5 s and again from 1.4 s
    const clip = layer('rg-shine', frame, [x, y, w, h]);
    clip.style.borderRadius = getComputedStyle(pic).borderRadius;
    const band = layer('', clip, [0, 0, .18 * w, h]);
    const from = `translateX(${-.6 * h}px) skewX(-31deg)`, to = `translateX(${w + .6 * h}px) skewX(-31deg)`;
    band.animate([
      { offset: 0, transform: from }, { offset: 500 / 2100, transform: from }, { offset: 1200 / 2100, transform: to },
      { offset: 1400 / 2100, transform: to }, { offset: 1400 / 2100, transform: from }, { offset: 1, transform: to },
    ], { duration: 2100 });
    parts.push(clip);
  }
  // Each sparkle's arms reach 2.2 times its half width, which is 6 of mobile's units on its picture's height
  const s = h * 6 / (way === 'rays' ? 150 : 64);
  SPOTS.forEach(([fx, fy], i) => {
    const star = layer('rg-twinkle', frame, [x + w * fx - 2.2 * s, y + h * fy - 2.2 * s, 4.4 * s, 4.4 * s]);
    star.animate(Array.from({ length: 9 }, (_, k) => ({ scale: `${Math.sin(Math.PI * k / 8)}` })), { duration: 500, delay: 400 + i * 350 });
    parts.push(star);
  });
  setTimeout(() => parts.forEach(part => part.remove()), LIGHT_MS);
}

const RANK_MS = 900;

/** sin(pi t) over a stretch of the rank-up's 0.9 s, sampled, for a part that swells and settles. */
const swell = (from: number, to: number, frame: (v: number) => Keyframe): [Keyframe[], KeyframeAnimationOptions] =>
  [Array.from({ length: 9 }, (_, k) => frame(Math.sin(Math.PI * k / 8))), { duration: (to - from) * RANK_MS, delay: from * RANK_MS }];

/**
 * Celebrates an Aetherwork's new rank as mobile does over 0.9 s: the new rank's pip fills, a flare bursts on it, the tile glows and its rank
 * text pops. owed is given the Echoes paid that are still to count off the balance as they run down, pow3Out, in the first 0.36 s.
 */
export function rankUp(tile: HTMLElement, rank: number, paid: number, owed: (left: number) => void): void {
  if (reducedMotion()) return;
  const pip = tile.querySelectorAll<HTMLElement>('.rg-rank i')[rank - 1];
  const began = performance.now();
  let left = paid;
  owed(left);
  requestAnimationFrame(function step(at) {
    const p = Math.min(1, (at - began) / (.4 * RANK_MS));
    const now = Math.round(paid * (1 - p) ** 3);
    if (now !== left) owed(left = now);
    if (p < 1) requestAnimationFrame(step);
  });
  const raised = getComputedStyle(tile).position === 'static';
  if (raised) tile.style.position = 'relative';
  // Mobile's unit, as its pips are 13 wide
  const u = pip ? pip.offsetWidth / 13 : 1.5;
  const parts: HTMLElement[] = [];
  if (pip) {
    // The empty look is read from a pip without a rank, as the theme draws it
    const empty = document.createElement('i');
    pip.parentElement!.append(empty);
    const off = getComputedStyle(empty), offColour = off.backgroundColor, offLine = off.boxShadow;
    empty.remove();
    const on = getComputedStyle(pip), onColour = on.backgroundColor, onLine = on.boxShadow;
    pip.animate(Array.from({ length: 13 }, (_, k) => ({
      background: `linear-gradient(90deg, ${onColour} ${k * 100 / 12}%, ${offColour} ${k * 100 / 12}%)`, boxShadow: k < 12 ? offLine : onLine,
    })), { duration: .3 * RANK_MS, delay: .15 * RANK_MS, fill: 'backwards' });
    const box = tile.getBoundingClientRect(), at = pip.getBoundingClientRect();
    const cx = at.left + at.width / 2 - box.left - tile.clientLeft, cy = at.top + at.height / 2 - box.top - tile.clientTop;
    const flare = layer('rg-flare', tile, [cx - 16 * u, cy - 16 * u, 32 * u, 32 * u]);
    flare.animate([{ transform: 'scale(.375)', opacity: .8 }, { transform: 'none', opacity: 0 }], { duration: .33 * RANK_MS, delay: .42 * RANK_MS });
    parts.push(flare);
  }
  const glow = layer('rg-glow', tile, [-tile.clientLeft - 2 * u, -tile.clientTop - 2 * u, tile.offsetWidth + 4 * u, tile.offsetHeight + 4 * u]);
  glow.style.borderWidth = `${2.5 * u}px`;
  glow.animate(...swell(.45, 1, v => ({ opacity: v })));
  parts.push(glow);
  const text = tile.querySelector<HTMLElement>('.rk');
  text?.animate(...swell(.4, .65, v => ({ transform: `scale(${1 + .35 * v})` })));
  setTimeout(() => {
    parts.forEach(part => part.remove());
    if (raised) tile.style.position = '';
  }, RANK_MS);
}
