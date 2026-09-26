// One player's permanents: sorts them into rows, stacks identical ones, and sizes the cards so the board fits.

import { reconcile } from './render';
import { createCard, updateCard, setPileCount, type CardClick } from './cards';
import { combatShown, stateOf, type Model } from './model';
import { mergeInto, spreadFrom } from './motion';
import { chargingAtPlayer } from './overlay';
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

/**
 * Which of the five groups a permanent belongs to. Lands and the rest of the non-creature permanents share the
 * row nearest the player's own edge; creatures and the tokens they make share the row nearest the middle.
 * A token that is not a creature — a Treasure, a Clue — belongs with the other artifacts rather than beside the
 * creatures.
 *
 * Planeswalkers and battles go to the far end of the creature row, which is where Arena puts them: an attack can
 * be aimed at either, so they belong in the row a player attacks into, but neither blocks and neither is part of
 * the fight, so they keep out of the way of the creatures that are. A battle sits with whoever protects it.
 */
type Group = 'lands' | 'support' | 'creatures' | 'tokens' | 'far';

function groupOf(model: Model, slot: Slot): Group {
  const type = stateOf(model, slot.top).Type ?? '';
  if (/Land/.test(type)) return 'lands';
  if (/Creature/.test(type)) return slot.top.Token ? 'tokens' : 'creatures';
  if (/Planeswalker|Battle/.test(type)) return 'far';
  return 'support';
}

export function renderBattlefield(root: HTMLElement, model: Model, cards: CardView[], onField: CardView[], select: CardClick): void {
  const slots = slotsFor(model, cards, onField);
  const of = (group: Group) => slots.filter(s => groupOf(model, s) === group);
  const groups: Record<Group, Slot[]> = {
    lands: of('lands'), support: of('support'), creatures: of('creatures'), tokens: of('tokens'), far: of('far'),
  };
  const charging = chargingAtPlayer(model);
  for (const [name, list] of Object.entries(groups)) {
    reconcile(q(root, `.${name}`), list, s => s.top.$key, createSlot, (el, s) => updateSlot(el, model, s, select, charging));
  }
  fitCards(root, groups.lands.length + groups.support.length,
    groups.creatures.length + groups.tokens.length + groups.far.length);
}

/** Below this the art stops being worth looking at, so a board wider than that scrolls after all. */
const MIN_FIT = 0.5;
/** An empty board's cards start this large and shrink as it fills, as Arena's do. */
const MAX_FIT = 1.4;

/**
 * Sizes the cards to the board: as large as they can be while every row fits the seat, which keeps a wide board
 * whole rather than scrolling its oldest permanents out of sight. Measured against the seat, whose size the page
 * grid fixes, so the answer cannot feed back into itself the way measuring the cards themselves would.
 */
function fitCards(root: HTMLElement, support: number, creatures: number): void {
  const field = q(root, '.battlefield');
  const style = getComputedStyle(root);
  const h = parseFloat(style.getPropertyValue('--card-h')) || 123;
  const air = parseFloat(style.getPropertyValue('--slot-gap')) || 0;
  // The field's padding is room for glows and for the stack panel, not for cards
  const pad = getComputedStyle(field);
  // A row's two groups keep .row's 20px gap between them, and a couple of pixels more cover rounding
  const width = field.clientWidth - parseFloat(pad.paddingLeft) - parseFloat(pad.paddingRight) - 22;
  // A compact seat keeps its player's details in a row above the cards, and that row is not the cards' room
  const header = root.classList.contains('compact') ? q(root, '.player').offsetHeight + 8 : 0;
  const height = root.clientHeight - header - parseFloat(pad.paddingTop) - parseFloat(pad.paddingBottom);
  // A slot with its room either side is as wide as a tapped card, which lies on its side at 90% (board.css), plus
  // its air. An empty row still keeps 60% of a card's height (.row's min-height)
  const lines = (count: number, fit: number) =>
    count === 0 ? 0.6 : Math.ceil(count / Math.max(1, Math.floor(width / ((h * 0.9 + 2 * air) * fit))));
  // 32px is the two rows' room above their cards, and 24px the gap between them with some to spare: a board filled
  // to the pixel scrolls on the next rounding and cuts off its top row
  // A row stays on one line, as a line that wraps breaks up the lands and the creatures; the cards shrink instead,
  // and only past the smallest size do they wrap
  const fits = (fit: number) => lines(support, fit) <= 1 && lines(creatures, fit) <= 1
    && (lines(support, fit) + lines(creatures, fit)) * h * fit + 32 + 24 <= height;
  let fit = MAX_FIT;
  while (fit > MIN_FIT && !fits(fit)) fit -= 0.02;
  fit = Math.max(MIN_FIT, fit);
  root.style.setProperty('--fit', fit.toFixed(2));
  // Below this the keyword icons are too small to tell apart, so the board drops them and keeps the art
  field.classList.toggle('cramped', fit < 0.72);
}

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
  return slots.sort((a, b) => rank(a) - rank(b) || order.get(a)! - order.get(b)!);
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

// Only a creature is held back by summoning sickness; the engine flags other cards too
function isSick(model: Model, card: CardView): boolean {
  return !!card.Sickness && /Creature/.test(stateOf(model, card).Type ?? '');
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
    updateCard(c, model, card);
    // The engine flags creatures off the battlefield as sick too, so the mark belongs to battlefield cards only
    c.classList.toggle('sickness', isSick(model, card));
  });
  [...el.children].forEach((c, i) => (c as HTMLElement).style.setProperty('--under', String(i)));
  el.style.setProperty('--attached', String(slot.attached.length));
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
