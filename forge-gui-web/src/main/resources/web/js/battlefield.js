import { reconcile } from './render.js';
import { createCard, updateCard, setPileCount } from './cards.js';
import { stateOf } from './model.js';
import { mergeInto, spreadFrom } from './motion.js';

// A slot is one spot on the battlefield: a card with its attachments tucked under it, or a pile of identical permanents
const spread = new Set();
let schedule = () => {};

export function initBattlefield(scheduleFn) {
  schedule = scheduleFn;
}

export function renderBattlefield(root, model, cards, onField, select) {
  const slots = slotsFor(model, cards, onField);
  const isLand = slot => /Land/.test(stateOf(model, slot.top).Type ?? '');
  const draw = (rowEl, rowSlots) => reconcile(rowEl, rowSlots, s => s.top.$key, createSlot, (el, s) => updateSlot(el, model, s, select));
  draw(root.querySelector('.lands'), slots.filter(isLand));
  draw(root.querySelector('.permanents'), slots.filter(s => !isLand(s)));
}

// An attachment sits under the card at the bottom of its chain, on whichever battlefield that card is
function slotsFor(model, cards, onField) {
  const byKey = new Map(onField.map(c => [c.$key, c]));
  const hostOf = c => byKey.get(c.EntityAttachedTo?.ref);
  const rootOf = c => {
    let host = c;
    // A chain of attachments cannot be longer than this, and the bound stops a cycle in a broken one
    for (let i = 0; i < 10 && hostOf(host); i++) host = hostOf(host);
    return host;
  };
  const under = new Map();
  for (const c of onField) {
    if (!hostOf(c)) continue;
    const host = rootOf(c).$key;
    if (!under.has(host)) under.set(host, []);
    under.get(host).push(c);
  }
  const hosts = cards.filter(c => !hostOf(c));
  const marks = [
    new Set(((model.prompt?.selectableMin ?? 0) > 0 ? model.prompt?.selectable ?? [] : []).map(r => r?.ref)),
    new Set((model.prompt?.highlighted ?? [])),
    new Set((model.playable?.cards ?? []).map(r => r?.ref)),
    new Set((model.playable?.autoTap ?? []).map(r => r?.ref)),
  ];
  const piles = new Map();
  const slots = [];
  for (const c of hosts) {
    const attached = under.get(c.$key) ?? [];
    const sig = attached.length ? null : signature(model, c, marks);
    // A pile the player has opened lays its cards out one by one until they are put back
    const pile = sig && !spread.has(sig) && piles.get(sig);
    if (pile) {
      pile.members.push(c);
      continue;
    }
    const slot = { top: c, members: [c], attached, sig };
    if (sig) piles.set(sig, slot);
    slots.push(slot);
  }
  return slots;
}

// Everything a player can see or act on must match, so a pile never hides a difference
function signature(model, card, marks) {
  if (!model.visible.has(card.$key)) return null;
  const s = stateOf(model, card);
  return JSON.stringify([s.Name, s.ImageKey, s.Power, s.Toughness, s.Loyalty, card.Tapped, card.Counters, card.Damage,
    card.Attacking, card.Blocking, card.Sickness, card.PhasedOut, card.Token, card.EntityAttachedTo, card.IsRingBearer,
    ...marks.map(m => m.has(card.$key))]);
}

function createSlot() {
  const el = document.createElement('div');
  el.className = 'slot';
  return el;
}

function updateSlot(el, model, slot, select) {
  // The host comes last so it paints over what is attached to it
  const cards = [...slot.attached, slot.top];
  reconcile(el, cards, c => c.$key, () => createCard(select), (c, card) => {
    updateCard(c, model, card);
    // The engine flags creatures off the battlefield as sick too, so the mark belongs to battlefield cards only
    c.classList.toggle('sickness', !!card.Sickness && /Creature/.test(stateOf(model, card).Type ?? ''));
  });
  [...el.children].forEach((c, i) => c.style.setProperty('--under', i));
  el.style.setProperty('--attached', slot.attached.length);
  el.classList.toggle('attacking', !!slot.top.Attacking);
  const spreadable = slot.sig && (slot.members.length > 1 || spread.has(slot.sig));
  setPileCount(el.lastChild, slot.members.length, spreadable && spread.has(slot.sig));
  const count = el.lastChild.querySelector('.count');
  count.onclick = spreadable ? e => {
    e.stopPropagation();
    // The cards fan out of the pile, or fold back into it, rather than appearing beside it
    const keys = slot.members.map(c => String(c.$key));
    const from = el.lastChild.getBoundingClientRect();
    if (spread.delete(slot.sig)) {
      mergeInto(keys, from);
    } else {
      spread.add(slot.sig);
      spreadFrom(keys, from);
    }
    schedule();
  } : null;
  el.dataset.members = slot.members.map(c => c.$key).join(',');
}
