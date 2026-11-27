// Removing cards from a Rogue Commander run's deck: cards are chosen first, then removed together for the run's removal credits

import type { ComponentChild } from 'preact';
import { useLayoutEffect, useRef } from 'preact/hooks';
import type { EditorCard, EditorState } from '../protocol';
import { Sprite } from '../icons';
import { t, tNodes } from '../text';

/** How many copies of each card are chosen for removal, by name. */
export type Marks = Map<string, number>;

/** A removal sent to the server. Once its result arrives it is done, and stays shown until the next change. */
export interface Removal {
  n: number;
  cost: number;
  /** The state it was sent from, or once done, the state it produced. */
  at: EditorState;
  done: boolean;
}

/** The marks the deck still holds copies for. */
export function heldMarks(marks: Marks, state: EditorState): [string, number][] {
  const held = new Map(state.main.flatMap(g => g.cards).map(c => [c.name, c.count]));
  return [...marks].map(([name, n]): [string, number] => [name, Math.min(n, held.get(name) ?? 0)]).filter(([, n]) => n > 0);
}

/** Each click chooses one more copy. A click on a card with every copy chosen clears it. */
export function toggled(marks: Marks, card: EditorCard): Marks {
  const next = new Map(marks);
  const n = next.get(card.name) ?? 0;
  if (n >= card.count) next.delete(card.name);
  else next.set(card.name, n + 1);
  return next;
}

/** What follows a state change: the first state after a removal is sent is its reply, done if the deck got smaller, and anything after that clears it. */
export function settled(removal: Removal | null, state: EditorState): Removal | null {
  if (!removal || removal.at === state) return removal;
  return !removal.done && state.stats.main < removal.at.stats.main ? { ...removal, at: state, done: true } : null;
}

const icon = <Sprite k="deck" cls="rc-ic" />;
const credits = (n: number) => <>{n === 1 ? tNodes('lblWebRogueRemovalCreditOne', icon) : tNodes('lblWebRogueRemovalCreditMany', n, icon)}</>;

/** The bar under a run's deck. It explains how to choose, what the choice costs, and after a removal how to take it back. Its Undo takes back any change, as the editor's header does for other decks. */
export function RemovalBar({ state, chosen, removal, clear, remove, undo }: {
  state: EditorState; chosen: [string, number][]; removal: Removal | null; clear: () => void; remove: (n: number, cost: number) => void; undo: () => void;
}) {
  const have = state.removals?.credits ?? 0;
  const n = chosen.reduce((sum, [, k]) => sum + k, 0);
  const cost = chosen.filter(([name]) => !state.removals?.free.includes(name)).reduce((sum, [, k]) => sum + k, 0);
  const over = cost - have;
  const root = useRef<HTMLElement>(null);
  // A phone's chat chip sits on the bar's top edge rather than over its keys
  useLayoutEffect(() => {
    const page = document.documentElement;
    const watch = new ResizeObserver(() => page.style.setProperty('--removal-bar-h', `${root.current!.offsetHeight}px`));
    watch.observe(root.current!);
    return () => {
      watch.disconnect();
      page.style.removeProperty('--removal-bar-h');
    };
  }, []);
  let words: ComponentChild;
  if (!n && removal?.done) {
    const back = <button class="link" onClick={undo}>{t('lblUndo')}</button>;
    words = <p class="rm-words done">{removal.n === 1 ? tNodes('lblWebRogueRemovedOne', credits(removal.cost), back)
      : tNodes('lblWebRogueRemoved', removal.n, credits(removal.cost), back)}</p>;
  } else if (!n) {
    words = <p class="rm-words hint">{tNodes('lblWebRogueRemovalHint', credits(1))}</p>;
  } else if (over > 0) {
    words = (
      <p class="rm-words">
        <b class="warn">{tNodes('lblWebRogueRemovalTooMany', over, credits(have))}</b>
        <span>{n === 1 ? t('lblWebRogueRemovalChooseFewerOne') : t('lblWebRogueRemovalChooseFewer', n, over)}</span>
      </p>
    );
  } else {
    words = (
      <p class="rm-words">
        <b>{n === 1 ? t('lblWebRogueRemovalChosenOne') : t('lblWebRogueRemovalChosen', n)}</b>
        <span>{tNodes('lblWebRogueRemovalUses', cost, credits(have))}</span>
      </p>
    );
  }
  return (
    <section ref={root} class="removal-bar" aria-live="polite">
      {words}
      <span class="rm-keys">
        <button disabled={!state.canUndo} onClick={undo} title={`${t('lblUndo')} (Ctrl+Z)`}>&#8630; {t('lblUndo')}</button>
        {n > 0 && <>
          <button onClick={clear}>{t('lblWebRogueClear')}</button>
          <button class="primary" disabled={over > 0 || (removal !== null && !removal.done)} onClick={() => remove(n, cost)}>
            {n === 1 ? t('lblWebRogueRemoveCard') : t('lblWebRogueRemoveCards', n)}</button>
        </>}
      </span>
    </section>
  );
}
