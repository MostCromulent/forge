// Dragging a card between the catalogue and the deck, on pointer events so the carried card can say what releasing it will do

import { copyLimit, countsInDeck } from './catalogue';
import { imageUrl } from '../images';
import type { EditorState } from '../protocol';
import { t, type TextKey } from '../text';

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
const COMMANDER_FORMATS = new Set(['Commander', 'Brawl', 'Oathbreaker', 'TinyLeaders']);
// A refusal's words follow this mark, which also tells the zone to show it as refusing
const REFUSED = '⊘';
const ADD: Record<'Main' | 'Sideboard', TextKey> = { Main: 'lblWebEditorDragAddMain', Sideboard: 'lblWebEditorDragAddSideboard' };
const MOVE: Record<'Main' | 'Sideboard', TextKey> = { Main: 'lblWebEditorDragMoveMain', Sideboard: 'lblWebEditorDragMoveSideboard' };
const REMOVE: Record<Exclude<Zone, 'catalogue'>, TextKey> = {
  Main: 'lblWebEditorDragRemoveMain', Sideboard: 'lblWebEditorDragRemoveSideboard', Commander: 'lblWebEditorDragRemoveCommander',
};
const refused = (key: TextKey, ...args: number[]) => `${REFUSED} ${t(key, ...args)}`;

/** What releasing a carried card over a zone would do. commanderAllowed says whether the card could lead the deck. */
export function verdictFor(carried: Carried, zone: Zone, state: EditorState, commanderAllowed: boolean): Verdict {
  const n = carried.count;
  if (zone === carried.from) {
    return { zone, accepts: false, verb: carried.from === 'catalogue' ? t('lblWebEditorDragDropOnSection') : t('lblWebEditorDragAlreadyHere') };
  }
  if (zone === 'catalogue') {
    return { zone, accepts: true, verb: carried.from === 'catalogue' ? '' : t(REMOVE[carried.from], n) };
  }
  if (zone === 'Commander') {
    if (state.unrestricted || !COMMANDER_FORMATS.has(state.format)) {
      return { zone, accepts: false, verb: refused('lblWebEditorDragOnlyCommanderFormats') };
    }
    if (!commanderAllowed) {
      return { zone, accepts: false, verb: refused('lblWebEditorDragNotLegalCommander') };
    }
    return { zone, accepts: true, verb: state.commanders.length ? t('lblWebEditorDragReplaceCommander') : t('lblWebEditorDragMakeCommander') };
  }
  if (carried.from !== 'catalogue') {
    return { zone, accepts: true, verb: t(MOVE[zone], n) };
  }
  if (state.limited) {
    const left = state.sideboard.find(c => c.name === carried.name)?.count ?? 0;
    return n > left ? { zone, accepts: false, verb: refused('lblWebEditorDragNoneLeft') } : { zone, accepts: true, verb: t(ADD[zone], n) };
  }
  const have = countsInDeck(state).get(carried.name) ?? 0;
  const limit = copyLimit(state, carried.name);
  if (have + n > limit) {
    return { zone, accepts: false, verb: limit === 1 ? refused('lblWebEditorDragSingleton') : refused('lblWebEditorDragAtLimit', have, limit) };
  }
  return { zone, accepts: true, verb: t(ADD[zone], n) };
}

/** Watches a press that becomes a drag only after the pointer moves a few pixels, then gives drop the accepting zone's verdict. */
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
    if (verb) verb.textContent = over ? over.verb : t('lblWebEditorDragDropOnSection');
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
  zone.classList.toggle('drop-refuse', !v.accepts && v.verb.startsWith(REFUSED));
  zone.classList.toggle('drop-remove', v.accepts && v.zone === 'catalogue');
  zone.dataset.dropHint = v.accepts ? (under ? v.verb : t('lblWebEditorDragWouldAccept')) : v.verb;
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
