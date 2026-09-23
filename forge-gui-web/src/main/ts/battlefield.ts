import { reconcile } from './render';
import { createCard, updateCard, setPileCount, type CardClick } from './cards';
import { stateOf, type Model } from './model';
import { mergeInto, spreadFrom } from './motion';
import { q } from './dom';
import { changeUi, ui } from './ui';
import type { CardView } from './protocol';

// A slot is one spot on the battlefield: a card with its attachments tucked under it, or a pile of identical permanents
interface Slot {
  top: CardView;
  members: CardView[];
  attached: CardView[];
  sig: string | null;
}

export function renderBattlefield(root: HTMLElement, model: Model, cards: CardView[], onField: CardView[], select: CardClick): void {
  const slots = slotsFor(model, cards, onField);
  const isLand = (slot: Slot) => /Land/.test(stateOf(model, slot.top).Type ?? '');
  const draw = (rowEl: HTMLElement, rowSlots: Slot[]) =>
    reconcile(rowEl, rowSlots, s => s.top.$key, createSlot, (el, s) => updateSlot(el, model, s, select));
  const lands = slots.filter(isLand);
  const others = slots.filter(s => !isLand(s));
  draw(q(root, '.lands'), lands);
  draw(q(root, '.permanents'), others);
  fitCards(root, lands.length, others.length);
}

/** Below this the art stops being worth looking at, so a board wider than that scrolls after all. */
const MIN_FIT = 0.5;
/** Each row may wrap onto a second line before the cards start shrinking. */
const LINES_PER_ROW = 2;

/**
 * Shrinks the cards so a wide board stays whole rather than scrolling its oldest permanents out of sight.
 * Measured against the seat, whose size the page grid fixes, so the answer cannot feed back into itself
 * the way measuring the cards themselves would.
 */
function fitCards(root: HTMLElement, lands: number, others: number): void {
  const field = q(root, '.battlefield');
  const style = getComputedStyle(root);
  const w = parseFloat(style.getPropertyValue('--card-w')) || 88;
  const h = parseFloat(style.getPropertyValue('--card-h')) || 123;
  const columns = Math.ceil(Math.max(lands, others, 1) / LINES_PER_ROW);
  // The field's padding is room for glows and for the stack panel, not for cards
  const pad = getComputedStyle(field);
  const width = field.clientWidth - parseFloat(pad.paddingLeft) - parseFloat(pad.paddingRight);
  const height = root.clientHeight - parseFloat(pad.paddingTop) - parseFloat(pad.paddingBottom);
  // 18px is the room every slot holds for a tapped card's overhang, and 32px the two rows' room above their cards
  const byWidth = width / (columns * (w + 18));
  const byHeight = (height - 32) / (LINES_PER_ROW * 2 * h);
  root.style.setProperty('--fit', String(Math.max(MIN_FIT, Math.min(1, byWidth, byHeight))));
}

// An attachment sits under the card at the bottom of its chain, on whichever battlefield that card is
function slotsFor(model: Model, cards: CardView[], onField: CardView[]): Slot[] {
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
  return slots;
}

// Everything a player can see or act on must match, so a pile never hides a difference
function signature(model: Model, card: CardView, marks: Set<number>[]): string | null {
  if (!model.visible.has(card.$key)) return null;
  const s = stateOf(model, card);
  // A land played this turn is no different from the lands played before it, so it belongs in their pile
  return JSON.stringify([s.Name, s.ImageKey, s.Power, s.Toughness, s.Loyalty, card.Tapped, card.Counters, card.Damage,
    card.Attacking, card.Blocking, isSick(model, card), card.PhasedOut, card.Token, card.EntityAttachedTo, card.IsRingBearer,
    ...marks.map(m => m.has(card.$key))]);
}

// Only a creature is held back by summoning sickness; the engine flags other cards too
function isSick(model: Model, card: CardView): boolean {
  return !!card.Sickness && /Creature/.test(stateOf(model, card).Type ?? '');
}

function createSlot(): HTMLElement {
  const el = document.createElement('div');
  el.className = 'slot';
  return el;
}

function updateSlot(el: HTMLElement, model: Model, slot: Slot, select: CardClick): void {
  // The host comes last so it paints over what is attached to it
  const cards = [...slot.attached, slot.top];
  reconcile(el, cards, c => c.$key, () => createCard(select), (c, card) => {
    updateCard(c, model, card);
    // The engine flags creatures off the battlefield as sick too, so the mark belongs to battlefield cards only
    c.classList.toggle('sickness', isSick(model, card));
  });
  [...el.children].forEach((c, i) => (c as HTMLElement).style.setProperty('--under', String(i)));
  el.style.setProperty('--attached', String(slot.attached.length));
  el.classList.toggle('attacking', !!slot.top.Attacking);
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
