// Dragging a card between the catalogue and the deck's sections. Pointer events rather than the browser's own drag and
// drop, so the card the pointer carries can say what releasing it will do, and every section can show before the
// release whether it takes the card. A drag is one route among several: every result is also on a button or the card
// menu. On touch a press opens the card menu instead, since a drag there is a scroll.

import { copyLimit, countsInDeck } from './catalogue';
import { imageUrl } from './images';
import type { EditorState } from './protocol';

export type Zone = 'Main' | 'Sideboard' | 'Commander' | 'catalogue';

export interface Carried {
  name: string;
  /** Where it was picked up; catalogue for a card not in the deck yet. */
  from: Zone;
  count: number;
}

export interface Verdict {
  zone: Zone;
  accepts: boolean;
  /** What release does, as the carried card says it: "add 1 to Sideboard", or why it can't. */
  verb: string;
}

const THRESHOLD_PX = 4;
const LONG_PRESS_MS = 500;
const COMMANDER_FORMATS = new Set(['Commander', 'Brawl', 'Oathbreaker', 'TinyLeaders']);

/** What releasing a carried card over a zone would do. commanderAllowed says whether the card could lead the deck. */
export function verdictFor(carried: Carried, zone: Zone, state: EditorState, commanderAllowed: boolean): Verdict {
  const n = carried.count;
  if (zone === carried.from) {
    return { zone, accepts: false, verb: carried.from === 'catalogue' ? 'drop on a section' : 'already here' };
  }
  if (zone === 'catalogue') {
    return { zone, accepts: true, verb: `remove ${n} from ${carried.from}` };
  }
  if (zone === 'Commander') {
    if (state.unrestricted || !COMMANDER_FORMATS.has(state.format)) {
      return { zone, accepts: false, verb: '⊘ only commander formats have a commander' };
    }
    if (!commanderAllowed) {
      return { zone, accepts: false, verb: '⊘ not a legal commander' };
    }
    return { zone, accepts: true, verb: state.commanders.length ? 'replace the commander' : 'make the commander' };
  }
  if (carried.from !== 'catalogue') {
    return { zone, accepts: true, verb: `move ${n} to ${zone}` };
  }
  if (state.limited) {
    const left = state.sideboard.find(c => c.name === carried.name)?.count ?? 0;
    return n > left ? { zone, accepts: false, verb: '⊘ none left in the pool' } : { zone, accepts: true, verb: `add ${n} to ${zone}` };
  }
  const have = countsInDeck(state).get(carried.name) ?? 0;
  const limit = copyLimit(state, carried.name);
  if (have + n > limit) {
    return { zone, accepts: false, verb: limit === 1 ? '⊘ already in the deck, and this format allows one' : `⊘ ${have} of ${limit} already, across all zones` };
  }
  return { zone, accepts: true, verb: `add ${n} to ${zone}` };
}

/**
 * Starts watching a press that may become a drag. Nothing happens until the pointer moves a few pixels, so a click
 * stays a click. While dragging, every [data-zone] element is marked with whether it takes the card, and the one under
 * the pointer shows the result; drop receives the verdict of the zone released over, or nothing.
 */
export function startDrag(e: PointerEvent, carried: Carried, image: string, judge: (zone: Zone) => Verdict,
  drop: (v: Verdict) => void): void {
  if (e.button !== 0 || e.pointerType === 'touch') {
    return;
  }
  const x0 = e.clientX;
  const y0 = e.clientY;
  let chip: HTMLElement | null = null;
  let over: Verdict | null = null;
  const zones = [...document.querySelectorAll<HTMLElement>('[data-zone]')];
  const source = e.currentTarget instanceof HTMLElement ? e.currentTarget : null;

  const move = (ev: PointerEvent) => {
    if (!chip) {
      if (Math.hypot(ev.clientX - x0, ev.clientY - y0) < THRESHOLD_PX) return;
      chip = makeChip(carried, image);
      source?.classList.add('dragging');
      for (const z of zones) mark(z, judge(z.dataset.zone as Zone), false);
    }
    chip.style.left = `${ev.clientX + 12}px`;
    chip.style.top = `${ev.clientY + 12}px`;
    const target = document.elementFromPoint(ev.clientX, ev.clientY)?.closest<HTMLElement>('[data-zone]') ?? null;
    over = target ? judge(target.dataset.zone as Zone) : null;
    for (const z of zones) mark(z, judge(z.dataset.zone as Zone), z === target);
    const verb = chip.querySelector('.verb');
    if (verb) verb.textContent = over ? over.verb : 'drop on a section';
    chip.classList.toggle('no', !!over && !over.accepts);
    chip.classList.toggle('rm', !!over && over.accepts && over.zone === 'catalogue');
    scrollNearEdge(target, ev.clientY);
  };
  const end = () => {
    document.removeEventListener('pointermove', move);
    document.removeEventListener('pointerup', end);
    document.removeEventListener('pointercancel', end);
    if (!chip) return;
    chip.remove();
    source?.classList.remove('dragging');
    for (const z of zones) unmark(z);
    // Swallow the click that ends the drag, so releasing over a card does not also add it
    document.addEventListener('click', ev => ev.stopPropagation(), { capture: true, once: true });
    if (over?.accepts) drop(over);
  };
  document.addEventListener('pointermove', move);
  document.addEventListener('pointerup', end);
  document.addEventListener('pointercancel', end);
}

/** Pointer handlers for a card that can be dragged, and whose menu opens on right-click or a long touch. */
export type CardHandlers = (name: string, from: Zone, image: string, count: number) => {
  onPointerDown: (e: PointerEvent) => void;
  onContextMenu: (e: MouseEvent) => void;
};

/** Opens something after a finger rests on an element, with a ring filling while it waits. A touch that moves is a scroll. */
export function longPress(e: PointerEvent, open: (x: number, y: number) => void): void {
  if (e.pointerType !== 'touch') {
    return;
  }
  const x = e.clientX;
  const y = e.clientY;
  const ring = document.createElement('div');
  ring.className = 'press-ring';
  ring.style.left = `${x - 20}px`;
  ring.style.top = `${y - 20}px`;
  document.body.append(ring);
  const timer = setTimeout(() => {
    cancel();
    open(x, y);
  }, LONG_PRESS_MS);
  const moved = (ev: PointerEvent) => {
    if (Math.hypot(ev.clientX - x, ev.clientY - y) > 8) cancel();
  };
  function cancel(): void {
    clearTimeout(timer);
    ring.remove();
    document.removeEventListener('pointermove', moved);
    document.removeEventListener('pointerup', cancel);
    document.removeEventListener('pointercancel', cancel);
  }
  document.addEventListener('pointermove', moved);
  document.addEventListener('pointerup', cancel);
  document.addEventListener('pointercancel', cancel);
}

function makeChip(carried: Carried, image: string): HTMLElement {
  const chip = document.createElement('div');
  chip.className = 'drag-chip';
  const art = document.createElement('img');
  art.src = imageUrl(image);
  art.alt = '';
  const words = document.createElement('span');
  const name = document.createElement('b');
  name.textContent = carried.count > 1 ? `${carried.count} × ${carried.name}` : carried.name;
  const verb = document.createElement('span');
  verb.className = 'verb';
  words.append(name, verb);
  chip.append(art, words);
  document.body.append(chip);
  return chip;
}

function mark(zone: HTMLElement, v: Verdict, under: boolean): void {
  zone.classList.toggle('drop-eligible', v.accepts && !under);
  zone.classList.toggle('drop-over', v.accepts && under);
  zone.classList.toggle('drop-refuse', !v.accepts && v.verb.startsWith('⊘'));
  zone.classList.toggle('drop-remove', v.accepts && v.zone === 'catalogue');
  zone.dataset.dropHint = v.accepts ? (under ? v.verb : 'would accept') : v.verb;
}

function unmark(zone: HTMLElement): void {
  zone.classList.remove('drop-eligible', 'drop-over', 'drop-refuse', 'drop-remove');
  delete zone.dataset.dropHint;
}

// A long list is reached while holding a card by resting near its top or bottom edge
function scrollNearEdge(zone: HTMLElement | null, y: number): void {
  const body = zone?.querySelector<HTMLElement>('.zone-body, .cat-grid, .cat-table');
  if (!body) return;
  const box = body.getBoundingClientRect();
  if (y < box.top + 30) body.scrollTop -= 12;
  else if (y > box.bottom - 30) body.scrollTop += 12;
}
