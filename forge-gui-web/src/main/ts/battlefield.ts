// One player's permanents: sorts them into rows, stacks identical ones, and sizes the cards so the board fits.

import { reconcile } from './render';
import { createCard, updateCard, setPileCount, type CardClick } from './cards';
import { combatShown, stateOf, type Model } from './model';
import { mergeInto, spreadFrom } from './motion';
import { chargingAtPlayer } from './overlay';
import { q } from './dom';
import { changeUi, ui } from './ui';
import type { CardView } from './protocol';
import { t } from './text';

// A slot is one spot on the battlefield: a card with its attachments tucked under it, or a pile of identical permanents
interface Slot {
  top: CardView;
  members: CardView[];
  attached: CardView[];
  sig: string | null;
}

/** The cards drawn behind a slot's top card: what is attached to it, or up to three copies of a pile's. */
const behind = (s: Slot): number => s.attached.length || Math.min(3, s.members.length - 1);

/**
 * Which of the four groups a permanent belongs to. Lands and the rest of the non-creature permanents share the
 * row nearest the player's own edge; creatures and the tokens they make share the row nearest the middle.
 * A token that is not a creature — a Treasure, a Clue — belongs with the other artifacts rather than beside the
 * creatures.
 *
 * Planeswalkers and battles go to the far end of the creature row, which is where Arena puts them: an attack can
 * be aimed at either, so they belong in the row a player attacks into, but neither blocks and neither is part of
 * the fight, so they keep out of the way of the creatures that are. A battle sits with whoever protects it.
 */
type Group = 'lands' | 'support' | 'creatures' | 'far';

function groupOf(model: Model, slot: Slot): Group {
  const type = stateOf(model, slot.top).Type ?? '';
  // A land made a creature fights as one, so it stands with the creatures
  if (/Creature/.test(type)) return 'creatures';
  if (/Land/.test(type)) return 'lands';
  if (/Planeswalker|Battle/.test(type)) return 'far';
  return 'support';
}

export function renderBattlefield(root: HTMLElement, model: Model, cards: CardView[], onField: CardView[], select: CardClick): void {
  const slots = slotsFor(model, cards, onField);
  const of = (group: Group) => slots.filter(s => groupOf(model, s) === group);
  const groups: Record<Group, Slot[]> = {
    // Tokens are creatures like any other, laid out after the cards so the row's order stays readable
    lands: of('lands'), support: of('support'),
    creatures: [...of('creatures').filter(s => !s.top.Token), ...of('creatures').filter(s => s.top.Token)], far: of('far'),
  };
  const charging = chargingAtPlayer(model);
  for (const [name, list] of Object.entries(groups)) {
    reconcile(q(root, `.${name}`), list, s => s.top.$key, createSlot, (el, s) => updateSlot(el, model, s, select, charging));
  }
  const stats = (name: Group): GroupStats => {
    const list = groups[name];
    return {
      el: q(root, `.${name}`),
      slots: list.length,
      steps: list.reduce((n, s) => n + behind(s), 0),
      depth: list.reduce((n, s) => Math.max(n, behind(s)), 0),
      battles: list.filter(s => /Battle/.test(stateOf(model, s.top).Type ?? '')).length,
    };
  };
  fitCards(root, [[[stats('lands')], [stats('support')]], [[stats('creatures')], [stats('far')]]]);
}

/** Below this the art stops being worth looking at, so a board wider than that scrolls after all. */
const MIN_FIT = 0.42;
/** An empty board's cards start this large and shrink as it fills, as Arena's do. */
const MAX_FIT = 1.4;
const STEP = 0.02;
/** A zone takes a second line only when that makes its cards at least this much larger, and only once they are
 *  already small, so a card coming or going does not flip it back and forth. */
const TWO_LINE_GAIN = 0.08;
const TWO_LINE_BELOW = 0.72;
/** How much of the smallest zone's size a layout may give up to keep every other zone larger. */
const SMALLEST_SLACK = 0.06;

/** One group of a row's cards as the sizing needs it: how many slots, the fans behind them, and battles. */
interface GroupStats {
  el: HTMLElement;
  slots: number;
  /** Cards fanned behind the group's hosts and pile tops, all told. */
  steps: number;
  /** The most cards behind any one slot. */
  depth: number;
  /** Battles, which lie on their side and so are wider than a slot's usual room. */
  battles: number;
}

/** The groups sized as one, sharing a card size and a line count; each is its own zone today. */
type Zone = GroupStats[];
/** The zones side by side in one of a battlefield's two rows. */
type RowZones = Zone[];

/** A zone's size and line count; raw is its size before the smallest size is applied, less than it when it overflows. */
interface Sized { fit: number; lines: number; raw: number; }

/**
 * Sizes each zone of a battlefield on its own, as Arena does: a zone that fits keeps the largest size the seat's
 * height allows, and when a row is too wide the zone taking the most room gives way first, shrinking, or taking a
 * second line when that keeps its cards larger. So three artifacts stay full size beside a sprawl of lands. The
 * rows share the seat's height, so the largest size is searched for from the top down, as Forge desktop searches
 * for its card width. Measured against the seat, whose size the page grid fixes, so the answer cannot feed back
 * into itself the way measuring the cards would.
 */
function fitCards(root: HTMLElement, rows: RowZones[]): void {
  const field = q(root, '.battlefield');
  const style = getComputedStyle(root);
  const fieldStyle = getComputedStyle(field);
  const px = (from: CSSStyleDeclaration, name: string) => parseFloat(from.getPropertyValue(name)) || 0;
  const h = px(style, '--card-h') || 123;
  const w = px(style, '--card-w') || 88;
  const air = px(style, '--slot-gap');
  const fan = px(fieldStyle, '--fan');
  const glow = px(fieldStyle, '--glow');
  const supportIndent = px(fieldStyle, '--support-indent');
  const slotW = h * 0.9 + 2 * air;
  // A group's width at a card size of 1, on one line or split over two
  const groupUnit = (g: GroupStats, lines: number) => {
    const total = g.slots * slotW + g.steps * w * fan + g.battles * (h - w);
    // Two lines hold at most half the width plus the widest slot, as the second line takes what the first leaves
    return lines === 1 ? total : total / 2 + slotW + g.depth * w * fan + (g.battles ? h - w : 0);
  };
  // Between a group's two lines, room for an attacker on the back line to step forward into, with its chevron
  const lineGap = (fit: number) => 14 + (h - w * 0.9) / 2 * fit;
  const live = (z: Zone) => z.filter(g => g.slots > 0);
  const rowGap = (r: number) => (r === 1 ? 52 : 20);
  const zoneWidth = (z: Zone, s: Omit<Sized, 'raw'>, gap: number) =>
    live(z).reduce((n, g) => n + groupUnit(g, s.lines) * s.fit, 0) + gap * Math.max(0, live(z).length - 1);
  // What is behind a card fans out to its left only, so it costs a row width and never height
  const zoneHeight = (z: Zone, s: Sized) => s.lines * h * s.fit + (s.lines - 1) * lineGap(s.fit);

  // offsetWidth, so a scrollbar appearing does not shrink the room it measures and feed back into the size
  const width = field.offsetWidth - px(fieldStyle, 'padding-left') - px(fieldStyle, 'padding-right') - 2;
  // A compact seat keeps its player's details in a row above the cards, and its own padding round both
  const header = root.classList.contains('compact') ? q(root, '.player').offsetHeight + 8 : 0;
  const seatPad = px(style, 'padding-top') + px(style, 'padding-bottom');
  // The room by the pill for an attacker's step and chevron grows with the cards (board.css), so it is worked out
  // for each size tried rather than read back while the size is still easing
  const mine = root.id === 'me';
  const fieldPad = (fit: number) => {
    const chevron = w * fit * 0.38;
    return mine ? Math.max(glow + 6, chevron - 2) + glow : glow + Math.max(glow + 20, chevron + 14);
  };
  const height = (fit: number) => root.clientHeight - header - seatPad - fieldPad(fit);

  // A zone's size for the width it may take: one line or two, the second only as TWO_LINE_GAIN allows
  const sizeFor = (z: Zone, room: number, cap: number, two: boolean, gap: number): Sized => {
    const g = live(z);
    const inner = gap * Math.max(0, g.length - 1);
    // Compared before the smallest size is applied: a zone already past it is overflowing, and a second line that
    // stops that is a gain even if both come out at the smallest size
    const fitOn = (lines: number) =>
      Math.min(cap, (room - inner) / Math.max(1, g.reduce((n, x) => n + groupUnit(x, lines), 0)));
    const one = fitOn(1);
    const floor = (fit: number) => Math.max(MIN_FIT, fit);
    if (!two) return { fit: floor(one), lines: 1, raw: one };
    const split = fitOn(2);
    const was = Number(z[0].el.dataset.lines ?? '1');
    const takeTwo = was === 2 ? split > one : split >= one + TWO_LINE_GAIN && one < TWO_LINE_BELOW;
    return takeTwo ? { fit: floor(split), lines: 2, raw: split } : { fit: floor(one), lines: 1, raw: one };
  };
  // A row's zones under a largest size: each takes the width it needs at that size, up to an equal share of the
  // row, so the widest zone gives way first and the rest keep their size. The share is found by halving
  const layRow = (r: number, cap: number, two: boolean): Sized[] => {
    const row = rows[r];
    const gap = rowGap(r);
    const zones = row.filter(z => live(z).length);
    const need = zones.map(z => zoneWidth(z, { fit: cap, lines: 1 }, gap));
    // The other permanents stand in from the row's end (board.css), clear of the stack panel's edge
    const indent = r === 0 && live(rows[0][1]).length ? supportIndent : 0;
    const gaps = gap * Math.max(0, zones.length - 1) + indent;
    const used = (share: number) => need.reduce((n, x) => n + Math.min(x, share), 0) + gaps;
    let lo = 0;
    let hi = width;
    for (let i = 0; i < 30; i++) {
      const mid = (lo + hi) / 2;
      if (used(mid) <= width) lo = mid;
      else hi = mid;
    }
    const sizes = zones.map((z, i) => sizeFor(z, Math.min(need[i], lo), cap, two, gap));
    return row.map(z => sizes[zones.indexOf(z)] ?? { fit: cap, lines: 1, raw: cap });
  };
  const rowHeight = (r: number, sizes: Sized[], cap: number) => {
    const row = rows[r];
    // An empty row still keeps 60% of a card's height (.row's min-height)
    if (!row.some(z => live(z).length)) return 0.6 * h * cap;
    return Math.max(...row.map((z, i) => (live(z).length ? zoneHeight(z, sizes[i]) : 0)));
  };

  // Every largest size and every choice of one line or two for each row is tried, and each layout that fits the
  // height is judged by its smallest zone, before the smallest size is applied, so one that overflows less wins
  const tried: { cap: number; sizes: Sized[][]; smallest: number; split: number }[] = [];
  for (let cap = MAX_FIT; cap >= MIN_FIT - 1e-9; cap -= STEP) {
    for (const twoA of [false, true]) {
      for (const twoB of [false, true]) {
        const sizes = [layRow(0, cap, twoA), layRow(1, cap, twoB)];
        // 32px is the two rows' room above their cards, and 24px the gap between them with some to spare: a board
        // filled to the pixel scrolls on the next rounding and cuts off its top row
        const tall = rowHeight(0, sizes[0], cap) + rowHeight(1, sizes[1], cap) + 32 + 24;
        if (tall > height(cap)) continue;
        const flat = sizes.flat();
        tried.push({ cap, sizes, smallest: Math.min(...flat.map(s => s.raw)), split: flat.filter(s => s.lines === 2).length });
      }
    }
  }
  // Within SMALLEST_SLACK of the best smallest zone, the larger largest size wins, so every other zone's size is not
  // given up for a sliver on the smallest; then fewer split zones, then the larger smallest zone
  const top = Math.max(...tried.map(t => t.smallest));
  const best = tried.filter(t => t.smallest >= top - SMALLEST_SLACK)
    .sort((a, b) => b.cap - a.cap || a.split - b.split || b.smallest - a.smallest)[0];
  // Past the smallest size nothing fits; the smallest cards, split where they can be, scroll rather than shrink
  const chosen = best ?? { cap: MIN_FIT, sizes: [layRow(0, MIN_FIT, true), layRow(1, MIN_FIT, true)] };
  field.classList.toggle('crowded', !best);
  root.style.setProperty('--fit', chosen.cap.toFixed(2));
  let shrinking = false;
  rows.forEach((row, r) => row.forEach((zone, z) => {
    const s = chosen.sizes[r][z];
    for (const g of zone) {
      const was = parseFloat(g.el.style.getPropertyValue('--fit'));
      if (s.fit < was - 0.001) shrinking = true;
      g.el.style.setProperty('--fit', s.fit.toFixed(3));
      g.el.dataset.lines = String(s.lines);
      g.el.classList.toggle('two', s.lines === 2);
      // A split group is held to half its width plus a slot, so it breaks into two lines in the same place each time.
      // The width is given at full size and scaled in CSS, so it shrinks with the cards rather than ahead of them
      g.el.style.setProperty('--split-w', s.lines === 2 ? `${groupUnit(g, 2)}px` : '');
      // Below this the keyword icons are too small to tell apart, so the group drops them and keeps the art
      g.el.classList.toggle('cramped', s.fit < TWO_LINE_BELOW);
    }
  }));
  // A card joining a full row is laid out at the old, larger size while the size eases down, so the row overflows for
  // a moment; a scrollbar showing for that moment would push the whole seat and drop it back. Overflow is clipped
  // until the ease is over
  if (shrinking) {
    field.classList.add('settling');
    clearTimeout(settling.get(field));
    settling.set(field, setTimeout(() => field.classList.remove('settling'), SETTLE_MS));
  }
}

/** A little longer than the --fit transition in board.css. */
const SETTLE_MS = 700;
const settling = new WeakMap<HTMLElement, ReturnType<typeof setTimeout>>();

// An attachment sits under the card at the bottom of its chain, on whichever battlefield that card is
export function slotsFor(model: Model, cards: CardView[], onField: CardView[]): Slot[] {
  const byKey = new Map(onField.map(c => [c.$key, c]));
  const hostOf = (c: CardView) => (c.EntityAttachedTo ? byKey.get(c.EntityAttachedTo.ref) : undefined);
  const rootOf = (c: CardView) => {
    let host = c;
    // A chain of attachments cannot be longer than this, and the bound stops a cycle in a broken one
    for (let i = 0; i < 10; i++) {
      const next = hostOf(host);
      if (!next) break;
      host = next;
    }
    return host;
  };
  const under = new Map<number, CardView[]>();
  for (const c of onField) {
    if (!hostOf(c)) continue;
    const host = rootOf(c).$key;
    const list = under.get(host) ?? [];
    list.push(c);
    under.set(host, list);
  }
  // A card a permanent holds in exile, such as an Oblivion Ring's, sits under it as an attachment would, as a ghost
  for (const obj of model.objects.values()) {
    const held = obj as CardView;
    const holder = held.Zone === 'Exile' && held.ExiledWith ? byKey.get(held.ExiledWith.ref) : undefined;
    if (!holder) continue;
    const host = rootOf(holder).$key;
    under.set(host, [...(under.get(host) ?? []), held]);
  }
  const hosts = cards.filter(c => !hostOf(c));
  const marks = [
    new Set(((model.prompt?.selectableMin ?? 0) > 0 ? model.prompt?.selectable ?? [] : []).map(r => r.ref)),
    new Set(model.prompt?.highlighted ?? []),
    new Set((model.playable?.cards ?? []).map(r => r.ref)),
    new Set((model.playable?.autoTap ?? []).map(r => r.ref)),
  ];
  const piles = new Map<string, Slot>();
  const slots: Slot[] = [];
  for (const c of hosts) {
    const attached = under.get(c.$key) ?? [];
    const sig = attached.length ? null : signature(model, c, marks);
    // A pile the player has opened lays its cards out one by one until they are put back
    const pile = sig && !ui.openPiles.has(sig) && piles.get(sig);
    if (pile) {
      pile.members.push(c);
      continue;
    }
    const slot: Slot = { top: c, members: [c], attached, sig };
    if (sig) piles.set(sig, slot);
    slots.push(slot);
  }
  // Cards of one name stay side by side, in the order the name first appears, so a pile that splits (a land
  // tapped out of it) splits in place rather than across the row
  const firstOf = new Map<string, number>();
  slots.forEach((slot, i) => {
    const name = stateOf(model, slot.top).Name ?? '';
    if (!firstOf.has(name)) firstOf.set(name, i);
  });
  const order = new Map(slots.map((slot, i) => [slot, i]));
  const rank = (slot: Slot) => firstOf.get(stateOf(model, slot.top).Name ?? '') ?? 0;
  // Within a name, the untapped stand first and the tapped to their right, so a land tapped out of its pile moves on
  const tapped = (slot: Slot) => Number(!!slot.top.Tapped);
  return slots.sort((a, b) => rank(a) - rank(b) || tapped(a) - tapped(b) || order.get(a)! - order.get(b)!);
}

// Everything a player can see or act on must match, so a pile never hides a difference. Nothing else may keep
// cards apart: a property the game has never set and one it has set back (a land untapped this turn, a creature
// that attacked last turn) look the same, so they read the same here. Cards pile by name, as they do on desktop.
// Two printings of one land are the same card to play, so the art they were opened in does not split them; the
// pile shows the top card's. As on desktop, a copy never piles with what it copies.
export function signature(model: Model, card: CardView, marks: Set<number>[]): string | null {
  if (!model.visible.has(card.$key)) return null;
  const s = stateOf(model, card);
  // A land played this turn is no different from the lands played before it, so it belongs in their pile
  return JSON.stringify([s.Name, s.Power ?? null, s.Toughness ?? null, s.Loyalty && s.Loyalty !== '0' ? s.Loyalty : null, !!card.Tapped,
    counters(card.Counters), card.Damage ?? 0, !!card.Attacking, !!card.Blocking, isSick(model, card), !!card.PhasedOut,
    !!card.Token, !!card.Cloned, card.EntityAttachedTo?.ref ?? null, !!card.IsRingBearer, ...marks.map(m => m.has(card.$key))]);
}

/** Counters as they show: none at all whether the game sent nothing, nothing left, or zeroes; in a fixed order. */
function counters(all: Record<string, number> | null | undefined): string {
  return Object.entries(all ?? {}).filter(([, n]) => n > 0).sort(([a], [b]) => a.localeCompare(b))
    .map(([name, n]) => `${name}:${n}`).join(',');
}

// Only a creature without haste is held back by summoning sickness. The engine's flag means only "came under your
// control this turn", as CardView.hasSickness reads it, so haste is checked here as it is there.
function isSick(model: Model, card: CardView): boolean {
  const state = stateOf(model, card);
  return !!card.Sickness && /Creature/.test(state.Type ?? '') && !state.Keywords?.some(k => k.icon === 'IMG_ABILITY_HASTE');
}

function createSlot(): HTMLElement {
  const el = document.createElement('div');
  el.className = 'slot';
  return el;
}

function updateSlot(el: HTMLElement, model: Model, slot: Slot, select: CardClick, charging: Set<number>): void {
  // The host comes last so it paints over what is attached to it
  const cards = [...slot.attached, slot.top];
  reconcile(el, cards, c => c.$key, () => createCard(select), (c, card) => {
    // Marked first, since a ghost lying face down is drawn as its sleeve
    c.classList.toggle('ghost', card.Zone === 'Exile');
    updateCard(c, model, card);
    // The engine flags creatures off the battlefield as sick too, so the mark belongs to battlefield cards only
    c.classList.toggle('sickness', card.Zone !== 'Exile' && isSick(model, card));
    // A land standing among the creatures says why, since its art still reads as a land
    const type = stateOf(model, card).Type ?? '';
    q(c, '.kind-tag').textContent = /Land/.test(type) && /Creature/.test(type) ? t('lblWebBoardLandCreature') : '';
  });
  [...el.children].forEach((c, i) => (c as HTMLElement).style.setProperty('--under', String(i)));
  el.style.setProperty('--behind', String(behind(slot)));
  el.classList.toggle('attacking', !!slot.top.Attacking && combatShown(model));
  el.classList.toggle('charging', charging.has(slot.top.$key));
  const sig = slot.sig;
  const opened = !!sig && ui.openPiles.has(sig);
  const spreadable = opened || (!!sig && slot.members.length > 1);
  const top = el.lastChild as HTMLElement;
  setPileCount(top, slot.members.length, opened);
  const count = q(top, '.count');
  count.onclick = spreadable && sig ? e => {
    e.stopPropagation();
    // The cards fan out of the pile, or fold back into it, rather than appearing beside it
    const keys = slot.members.map(c => String(c.$key));
    const from = top.getBoundingClientRect();
    changeUi(u => {
      if (u.openPiles.delete(sig)) {
        mergeInto(keys, from);
      } else {
        u.openPiles.add(sig);
        spreadFrom(keys, from);
      }
    });
  } : null;
  el.dataset.members = slot.members.map(c => c.$key).join(',');
}
