// Declaring a block by dragging, as Arena does: press on one of your creatures, drag, and let go over the attacker it
// blocks, with the block arrow following the pointer. It sends the clicks the engine already takes for a block, the
// attacker and then the blocker, so clicking the two in turn still works as it always has. The host runs each click
// from a client on a thread of its own, so clicks sent together can arrive in either order; each one here waits for
// the one before it to show on the board.
//
// In a game of three or more an attack is dragged the same way, onto the seat or portrait of the player it attacks,
// and the attack prompt turns to whoever you attacked last rather than to the first opponent every combat.

import { byId, q } from './dom';
import { game, me, opponents, stateOf, type Model } from './model';
import { setDragArrow } from './overlay';
import type { Actions } from './actions';
import type { CardView, KeywordText, PlayerView, Ref } from './protocol';
import { t } from './text';

/** How far the pointer moves before a press on a creature is a drag rather than a click. */
const DRAG_PX = 8;
/** How long a click has to show on the board before the drag stops waiting for it. */
const STEP_MS = 2000;

type Point = { x: number; y: number };

let current: Model | null = null;
/** A drag has just ended: the click the browser fires straight after it is not a click on a card. */
let justDragged = false;
/** The drags not yet on the board, one after another, so a quick second drag waits for the first. */
let queued: Promise<void> = Promise.resolve();

const has = (refs: (Ref | null)[] | null | undefined, key: number) => (refs ?? []).some(r => r?.ref === key);

/**
 * Whether the prompt is asking this player for blockers, so their creatures can be dragged onto attackers. The block
 * prompt lists no cards as selectable, so it is known by the step and by whom the attack is aimed at; whether a
 * creature can block is the engine's to say, as it is for a click.
 */
function declaringBlocks(model: Model): boolean {
  const mine = me(model)?.$key;
  return game(model)?.Phase === 'COMBAT_DECLARE_BLOCKERS' && !!model.prompt
    && (game(model)?.CombatView ?? []).some(b => b.defender?.ref === mine);
}

/** The opponent the attack prompt is declaring attackers at, which it highlights. */
function currentDefender(model: Model): PlayerView | undefined {
  const lit = model.prompt?.highlighted ?? [];
  return opponents(model).find(p => lit.includes(p.$key));
}

/**
 * Whether the prompt is asking this player for attackers with more than one opponent to choose from. The attack prompt
 * highlights the player it is declaring at; the priority prompt later in the same step does not.
 */
function declaringAttacks(model: Model): boolean {
  return game(model)?.Phase === 'COMBAT_DECLARE_ATTACKERS' && game(model)?.PlayerTurn?.ref === me(model)?.$key
    && opponents(model).filter(p => !p.HasLost).length > 1 && !!currentDefender(model);
}

let act: Actions | null = null;
/** Who you last attacked, by name, since each game of a match has its players anew. */
let lastDefender: string | null = null;
/** This attack prompt has been turned to lastDefender already, or was never going to be. */
let turned = false;
/** The player this browser asked the prompt to turn to, until it has. */
let turning: number | null = null;

/**
 * The engine starts every attack at the first opponent. Once per attack prompt this turns it to whoever you attacked
 * last, if they are still in; after that, whoever the prompt is at is the one to remember.
 */
function followDefender(model: Model): void {
  const at = declaringAttacks(model) ? currentDefender(model) : undefined;
  if (!at) {
    turned = false;
    turning = null;
    return;
  }
  if (!turned) {
    turned = true;
    const wanted = opponents(model).find(p => p.Name === lastDefender && !p.HasLost);
    if (wanted && wanted.$key !== at.$key && act) {
      turning = wanted.$key;
      act.selectPlayer(wanted.$key);
      return;
    }
  }
  if (turning !== null && at.$key !== turning) return;
  turning = null;
  lastDefender = at.Name ?? null;
}

/** Marks the table while blockers or attackers are being declared, so a touch on your creatures drags rather than scrolls. */
export function renderBlockDrag(model: Model): void {
  current = model;
  followDefender(model);
  const blocks = declaringBlocks(model);
  const attacks = declaringAttacks(model);
  byId('match').classList.toggle('declaring-blocks', blocks);
  byId('match').classList.toggle('declaring-attacks', attacks);
  if (!blocks && !attacks) setDragArrow(null, null);
}

/** Runs once every block dragged so far has reached the board, so an OK pressed straight after a drop includes it. */
export function afterBlockDrags(then: () => void): void {
  void queued.then(then);
}

export function initBlockDrag(actions: Actions): void {
  act = actions;
  let from: { el: HTMLElement; key: number; x: number; y: number; attack: boolean } | null = null;
  let dragging = false;
  const stop = () => {
    from = null;
    if (dragging) setDragArrow(null, null);
    dragging = false;
    document.body.classList.remove('dragging-block');
  };
  document.addEventListener('pointerdown', e => {
    const el = e.button === 0 ? (e.target as Element).closest<HTMLElement>('#me .battlefield .card') : null;
    const model = current;
    if (!el || !model) return;
    const attack = declaringAttacks(model);
    if (!attack && !declaringBlocks(model)) return;
    from = { el, key: Number(el.dataset.key), x: e.clientX, y: e.clientY, attack };
  });
  document.addEventListener('pointermove', e => {
    if (!from || !current) return;
    if (!dragging && Math.hypot(e.clientX - from.x, e.clientY - from.y) < DRAG_PX) return;
    dragging = true;
    document.body.classList.add('dragging-block');
    // Over an attacker, or an opponent to attack, the arrow lands on it; anywhere else it follows the pointer
    const over = from.attack ? defenderAt(current, e.clientX, e.clientY)?.face : attackerAt(current, e.clientX, e.clientY);
    setDragArrow(from.el, over ?? { x: e.clientX, y: e.clientY }, from.attack ? 'attack' : 'block');
  });
  document.addEventListener('pointerup', e => {
    if (!from || !current) return;
    const creature = from.key;
    const attack = from.attack;
    const wasDrag = dragging;
    stop();
    if (!wasDrag) return;
    justDragged = true;
    setTimeout(() => { justDragged = false; });
    const at = { x: e.clientX, y: e.clientY };
    let step: (() => Promise<void>) | null = null;
    if (attack) {
      const defender = defenderAt(current, at.x, at.y);
      if (defender) step = () => declare(actions, creature, defender.key);
    } else {
      const attacker = attackerAt(current, at.x, at.y);
      if (attacker) step = () => block(actions, creature, Number(attacker.dataset.key), at);
    }
    // A failed block or attack must not stop every later one, or the OK that waits for them
    if (step) queued = queued.then(step).catch(e => console.error(e));
  });
  document.addEventListener('pointercancel', stop);
  document.addEventListener('click', e => {
    if (justDragged) {
      e.stopPropagation();
      e.preventDefault();
    }
  }, true);
}

/** The attacking card under the pointer, if there is one. */
function attackerAt(model: Model, x: number, y: number): HTMLElement | null {
  const attackers = (game(model)?.CombatView ?? []).flatMap(b => b.attackers ?? []);
  for (const el of document.elementsFromPoint(x, y)) {
    const card = el.closest<HTMLElement>('#opponent .card');
    if (card && has(attackers, Number(card.dataset.key))) return card;
  }
  return null;
}

/** The opponent whose seat or portrait is under the pointer, while they are still in the game. */
function defenderAt(model: Model, x: number, y: number): { key: number; face: HTMLElement } | null {
  for (const el of document.elementsFromPoint(x, y)) {
    const seat = el.closest<HTMLElement>('#opponent .seat');
    const player = seat && opponents(model).find(p => p.$key === Number(seat.dataset.player));
    if (seat && player && !player.HasLost) return { key: player.$key, face: q(seat, '.avatar') };
  }
  return null;
}

/** Attacks the defender with the creature, as the clicks would: the player first, unless the prompt is already at them. */
async function declare(actions: Actions, attacker: number, defender: number): Promise<void> {
  const attacking = () => (game(current!)?.CombatView ?? []).some(b => b.defender?.ref === defender && has(b.attackers, attacker));
  // A click on a creature already attacking the player the prompt is at takes it out of the attack
  if (!current || attacking()) return;
  if (currentDefender(current)?.$key !== defender) {
    actions.selectPlayer(defender);
    if (!await until(() => currentDefender(current!)?.$key === defender)) return;
  }
  actions.selectCard(attacker, false, 0, 0);
  await until(attacking);
}

/** Whether the blocker is blocking the attacker, declared or still being planned. */
function blocking(blocker: number, attacker: number): boolean {
  return (game(current!)?.CombatView ?? []).some(b => has(b.attackers, attacker)
    && (has(b.blockers, blocker) || has(b.plannedBlockers, blocker)));
}

/** The attacker the blocker is blocking now, if any. */
function blocked(blocker: number): number | undefined {
  const band = (game(current!)?.CombatView ?? []).find(b => has(b.blockers, blocker) || has(b.plannedBlockers, blocker));
  return band?.attackers?.find(r => r)?.ref;
}

/** Resolves true once the test holds on the board, or false if it has not within STEP_MS. */
function until(test: () => boolean): Promise<boolean> {
  const start = performance.now();
  return new Promise(done => {
    const check = () => {
      if (current && test()) done(true);
      else if (performance.now() - start > STEP_MS) done(false);
      else setTimeout(check, 30);
    };
    check();
  });
}

/**
 * Blocks the attacker with the blocker, as the clicks would. A creature already blocking that attacker is left as it
 * is, where a click would take the block back. One blocking another attacker has that block taken back first, by
 * choosing its attacker and clicking it again, so the drag moves the block rather than being refused.
 *
 * TODO: ideally the shared input code would take a block as a single intent and leave how it is selected to each GUI;
 * until it does, a drag is translated here into the clicks the block input already understands.
 */
async function block(actions: Actions, blocker: number, attacker: number, at: Point): Promise<void> {
  if (!current || blocking(blocker, attacker)) return;
  // The prompt names the attacker being blocked by its id; one already named is not clicked again, as a click on it
  // changes nothing and would only run beside the next click on the host
  const named = (key: number) => (current?.prompt?.message ?? '').includes(`(${key})`);
  const choose = async (key: number) => {
    if (named(key)) return;
    actions.selectCard(key, false, 0, 0);
    await until(() => named(key));
  };
  const held = blocked(blocker);
  if (held !== undefined) {
    await choose(held);
    actions.selectCard(blocker, false, 0, 0);
    if (!await until(() => !blocking(blocker, held))) return;
  }
  await choose(attacker);
  actions.selectCard(blocker, false, 0, 0);
  if (!await until(() => blocking(blocker, attacker))) explainRefusal(blocker, attacker, at);
}

/**
 * Says why the engine would not take a block. The board does not carry the rules for who may block whom, but it does
 * carry each keyword's reminder, and an attacker that evades a block says so in its own ("can't be blocked except by
 * creatures with flying or reach"). Anything else gets no reason rather than a guessed one.
 */
function explainRefusal(blocker: number, attacker: number, at: Point): void {
  const model = current;
  if (!model) return;
  const card = (key: number) => model.objects.get(key) as CardView | undefined;
  const state = (key: number) => { const c = card(key); return c ? stateOf(model, c) : undefined; };
  const name = (key: number) => state(key)?.Name ?? t('lblWebBlockThatCreature');
  const keywords = (key: number): KeywordText[] => state(key)?.Keywords ?? [];
  const answers = new Set(keywords(blocker).map(k => k.title));
  const evasion = keywords(attacker).find(k => /can't be blocked/i.test(k.reminder)
    && !answers.has(k.title) && !(k.title === 'Flying' && answers.has('Reach')));
  showTip(at, t('lblWebBlockCantBlock', name(blocker), name(attacker)), evasion ? `${evasion.title}: ${evasion.reminder}` : '');
}

/** How long the reason stays by the pointer, unless the next press takes it away sooner. */
const TIP_MS = 3500;

/** A small note where the drag was let go, so the reason is read where the eye already is. */
function showTip(at: Point, title: string, reason: string): void {
  document.querySelector('.block-tip')?.remove();
  const tip = document.createElement('div');
  tip.className = 'block-tip';
  tip.append(Object.assign(document.createElement('b'), { textContent: title }));
  if (reason) tip.append(Object.assign(document.createElement('span'), { textContent: reason }));
  document.body.append(tip);
  // Beside the pointer, turned back inside the window near its edges
  const gap = 14;
  const x = at.x + gap + tip.offsetWidth > innerWidth - 8 ? at.x - gap - tip.offsetWidth : at.x + gap;
  const y = Math.min(at.y + gap, innerHeight - tip.offsetHeight - 8);
  tip.style.left = `${Math.max(8, x)}px`;
  tip.style.top = `${Math.max(8, y)}px`;
  const gone = () => {
    tip.classList.add('leaving');
    tip.addEventListener('animationend', () => tip.remove());
  };
  const timer = setTimeout(gone, TIP_MS);
  document.addEventListener('pointerdown', () => { clearTimeout(timer); tip.remove(); }, { once: true });
}
