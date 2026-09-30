// The card preview's rules text, sorted into the parts of a printed card. The host sends one block of text
// (CardDetailUtil, as desktop shows it): the card's own rules, then whatever the game has done to it, each on lines of
// their own. This tells the two apart and sorts the rules into keywords, abilities and plain text. A line it does not
// recognise stays a line of text, so nothing the host sends is ever lost.

import { t } from './text';

/** A stretch of text; muted is text that does not apply just now, which CardDetailUtil greys out. */
export interface Run {
  text: string;
  muted: boolean;
}

export type Line = Run[];

export type Block =
  /** Keywords as chips, with the reminder text any of them carries. */
  | { kind: 'keywords'; items: Line[]; reminders: Line[] }
  /** An activated or loyalty ability: its cost in a column, then what it does. label is an ability word or a name. */
  | { kind: 'ability'; cost: Line; label?: Line; effect: Line; loyalty?: 'plus' | 'minus' | 'zero' }
  /** One of a modal spell's modes. */
  | { kind: 'mode'; text: Line }
  /** A paragraph; label names the other half of the card it describes, such as its Adventure. */
  | { kind: 'text'; text: Line; label?: Line };

/** Something the game has done to the card that fits in a chip: counters, damage, shields. */
export interface Chip {
  kind: 'counter' | 'damage' | 'shield' | 'status';
  text: string;
}

export interface SortedText {
  /** Token, Emblem and the like: what the card is, when it is not a card. */
  tag?: string;
  blocks: Block[];
  chips: Chip[];
  /** The game's other notes on the card: what it is attached to, what was chosen for it, who owns it. */
  notes: Line[];
}

export const plain = (line: Line): string => line.map(r => r.text).join('');

/** The characters from..to of a line, keeping each run's muting. */
export function slice(line: Line, from: number, to = Infinity): Line {
  const out: Line = [];
  let at = 0;
  for (const run of line) {
    const start = Math.max(from, at), end = Math.min(to, at + run.text.length);
    if (end > start) out.push({ text: run.text.slice(start - at, end - at), muted: run.muted });
    at += run.text.length;
  }
  return out;
}

function trim(line: Line): Line {
  const text = plain(line);
  const start = text.length - text.trimStart().length;
  return slice(line, start, text.trimEnd().length);
}

const TAGS = /^(Token|Token card|Emblem|Boon|Effect)$/;

// Counters that already show in the card's corner (loyalty, defence) are left out of the chips
const CORNER_COUNTERS = /^(loyalty|defense)$/i;

/** A chip for a line of game state, or null when the line is something else. */
function chipFor(text: string): Chip | null | 'skip' {
  let m = /^(.+) counters: (-?\d+)$/.exec(text);
  if (m) return CORNER_COUNTERS.test(m[1]) ? 'skip' : { kind: 'counter', text: `${m[1]} ×${m[2]}` };
  if ((m = /^Damage: (\d+)$/.exec(text))) return { kind: 'damage', text: t('lblWebDetailDamageChip', m[1]) };
  if ((m = /^Assigned Damage: (\d+)$/.exec(text))) return { kind: 'damage', text: t('lblWebDetailAssignedChip', m[1]) };
  if ((m = /^Regeneration Shields: (\d+)$/.exec(text))) return { kind: 'shield', text: t(m[1] === '1' ? 'lblWebDetailRegenShield' : 'lblWebDetailRegenShields', m[1]) };
  if (text === 'Phased Out') return { kind: 'status', text: t('lblWebDetailPhasedOut') };
  if (text === '^Exerted^') return { kind: 'status', text: t('lblWebDetailExerted') };
  if (text === '^Detained^') return { kind: 'status', text: t('lblWebDetailDetained') };
  return null;
}

// The shapes CardDetailUtil gives the rest of what the game has done to a card. Some are wrapped in marks desktop
// colours them by (=Attached: …=, *Attached to …*, +Controlling: …+, ^Cloned via: …^), which are dropped.
const NOTE_PREFIXES = [
  'Text changed:', 'Intensity:', 'Prevent the next ', 'Draft Notes:', 'Sector:', 'Sprocket:', 'Protected by:', 'Imprinting:',
  'Exiled:', 'Exiled until this leaves the battlefield:', 'Haunted by:', 'Haunting ', 'Encoded:', 'Must block ',
  'Current Card Colors:', 'Current Storm Count:', 'Owner:',
];
const NOTE_ASIDES = /^\((chosen|noted type|named card|stored dice|in room|class level|ring level|selected)/i;
const MARKED = /^([=*+^])(.*)\1$/;

function noteFor(line: Line): Line | null {
  const text = plain(line);
  const marked = MARKED.exec(text);
  if (marked && /^(Attached|Enchanting|Controlling|Cloned via)\b/.test(marked[2])) return slice(line, 1, text.length - 1);
  if (NOTE_ASIDES.test(text)) return slice(line, 1, text.endsWith(')') ? text.length - 1 : text.length);
  return NOTE_PREFIXES.some(p => text.startsWith(p)) ? line : null;
}

// A loyalty ability's cost: +1, −2, 0, −X. Forge writes the minus as a true minus sign, but a hyphen is taken too
const LOYALTY = /^([+−–-](?:\d+|X)|0): /;

// What a cost without a mana or tap symbol starts with
const COST_VERBS = /^(Sacrifice|Discard|Pay|Exile|Remove|Tap|Untap|Return|Put|Reveal|Collect|Forage|Mill)\b/;

/** Where an activated ability's cost ends, or -1 when the line is not one. */
function costEnd(text: string): number {
  const at = text.indexOf(': ');
  if (at <= 0 || at > 60) return -1;
  const cost = text.slice(0, at);
  if (/[.]/.test(cost)) return -1;
  const bare = cost.includes(' — ') ? cost.slice(cost.lastIndexOf(' — ') + 3) : cost;
  return /\{[^}]+\}/.test(bare) || COST_VERBS.test(bare) ? at : -1;
}

/**
 * A line of keywords: short names, perhaps each with a cost, separated by commas, with no sentence in them, and perhaps
 * reminder text in brackets at the end. Returns where the names end, or -1.
 */
function keywordsEnd(text: string): number {
  const reminder = /\s*\((?:[^()]|\([^()]*\))*\)$/.exec(text);
  const head = reminder ? text.slice(0, reminder.index) : text;
  if (!head || head.length > 60 || /[.:;—]/.test(head) || !/^[A-Z]/.test(head)) return -1;
  const parts = head.split(/,\s*/);
  // Names, numbers and costs only: an apostrophe or a bracket means a sentence or a name, such as "Alice's Commander"
  return parts.every(p => p.length <= 32 && /^[A-Za-z][A-Za-z0-9 {}/+-]*$/.test(p)) ? head.length : -1;
}

/** Splits a line of keywords at its commas, keeping any cost with its keyword. */
function keywordItems(line: Line, end: number): Line[] {
  const text = plain(line).slice(0, end);
  const items: Line[] = [];
  let start = 0;
  for (const m of text.matchAll(/,\s*/g)) {
    items.push(slice(line, start, m.index));
    start = m.index + m[0].length;
  }
  items.push(slice(line, start, end));
  return items;
}

/** The reminder text after a line's keywords, without its brackets. */
function reminderOf(line: Line, end: number): Line | null {
  const text = plain(line);
  const open = text.indexOf('(', end);
  return open < 0 ? null : slice(line, open + 1, text.endsWith(')') ? text.length - 1 : text.length);
}

export function sortRulesText(lines: Line[]): SortedText {
  const sorted: SortedText = { blocks: [], chips: [], notes: [] };
  let first = true;
  for (const raw of lines) {
    const line = trim(raw);
    const text = plain(line);
    if (!text) continue;
    if (first && TAGS.test(text)) {
      sorted.tag = text;
      first = false;
      continue;
    }
    first = false;
    const chip = chipFor(text);
    if (chip === 'skip') continue;
    if (chip) {
      sorted.chips.push(chip);
      continue;
    }
    const note = noteFor(line);
    if (note) {
      sorted.notes.push(note);
      continue;
    }
    if (/^[•●]\s*/.test(text)) {
      sorted.blocks.push({ kind: 'mode', text: slice(line, (/^[•●]\s*/.exec(text) as RegExpExecArray)[0].length) });
      continue;
    }
    const loyalty = LOYALTY.exec(text);
    if (loyalty) {
      const cost = loyalty[1];
      sorted.blocks.push({ kind: 'ability', cost: slice(line, 0, cost.length), effect: slice(line, loyalty[0].length),
        loyalty: cost.startsWith('+') ? 'plus' : cost === '0' ? 'zero' : 'minus' });
      continue;
    }
    const keywords = keywordsEnd(text);
    if (keywords >= 0) {
      const last = sorted.blocks[sorted.blocks.length - 1];
      const block = last?.kind === 'keywords' ? last : { kind: 'keywords' as const, items: [], reminders: [] };
      if (block !== last) sorted.blocks.push(block);
      block.items.push(...keywordItems(line, keywords));
      const reminder = reminderOf(line, keywords);
      if (reminder) block.reminders.push(reminder);
      continue;
    }
    // The spell on the other half of the card (CardDetailUtil adds it as "Adventure — Stomp {1}{R}: …"), which has a
    // cost to cast rather than to activate
    const half = /^(Adventure|Omen|Prepared) — /.exec(text);
    if (half) {
      sorted.blocks.push({ kind: 'text', label: slice(line, 0, half[1].length), text: slice(line, half[0].length) });
      continue;
    }
    const cost = costEnd(text);
    if (cost >= 0) {
      const word = text.slice(0, cost).lastIndexOf(' — ');
      sorted.blocks.push({
        kind: 'ability',
        label: word >= 0 ? slice(line, 0, word) : undefined,
        cost: slice(line, word >= 0 ? word + 3 : 0, cost),
        effect: slice(line, cost + 2),
      });
      continue;
    }
    sorted.blocks.push({ kind: 'text', text: line });
  }
  return sorted;
}
